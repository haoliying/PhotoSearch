package com.example.photosearch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.photosearch.MainActivity
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import com.example.photosearch.ml.VisionLanguageAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.9.0：断点续分析。
 *
 * 旧版用「已分析张数」当相册下标来续跑，还要求照片总数完全一致，一旦新增/删除照片、
 * MediaStore 排序变化、分析完成再点一次，就会 clear() 或从头重跑；加上每 200 张才落盘，
 * 前台服务被系统限额杀掉时整段进度全部回退 —— 几万张照片几乎永远跑不完。
 *
 * 现在：
 *  - 由 [AnalysisPlanner] 按 photoId 差集算出「本次待分析」列表，已落盘的绝不重跑；
 *  - 每分析完一张立即 append 一行结果，进程被强杀最多丢当前这一张；
 *  - 计数标记按间隔刷新，恢复时以行文件里的 id 为唯一权威；
 *  - 云端 AI 描述缺失的占位结果会在下一次开启 AI 时自动补齐；
 *  - 中断 / 取消 / 正常停止都在 finally 与 onDestroy 里写入未完成标记。
 */
class AnalysisService : Service() {

    companion object {
        const val CHANNEL_ID = "analysis_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_RESUME = "com.example.photosearch.action.RESUME"
        private const val TAG = "AnalysisService"

        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        /** v3.8.0：Service 写、ViewModel 读，必须用并发 Map。 */
        val analyzedMetadata = ConcurrentHashMap<Long, PhotoMetadata>()

        /** 进度发射节流：每 20 张或 400ms，避免几万张照片时刷爆 UI。 */
        private const val PROGRESS_EMIT_STEP = 20

        var allPhotos: List<PhotoItem> = emptyList()
            private set

        fun isRunning(): Boolean = _analysisState.value.isRunning

        fun reset() {
            _analysisState.value = AnalysisProgress()
            analyzedMetadata.clear()
            allPhotos = emptyList()
        }
    }

    data class AnalysisProgress(
        val isRunning: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val isComplete: Boolean = false,
        val isResumed: Boolean = false,
        val vlmEnabled: Boolean = false,
        val vlmErrors: Int = 0
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var onDeviceAnalyzer: EnhancedImageAnalyzer? = null
    private var vlmAnalyzer: VisionLanguageAnalyzer? = null
    private lateinit var resultStore: AnalysisResultStore
    private var lastEmittedProgress = -1
    private var lastEmitTime = 0L
    /** 本轮是否已正常跑完；决定 finally / onDestroy 是否写「未完成」标记。 */
    @Volatile
    private var completed = false
    /** VLM 连续失败时本轮降级为本地分析，避免几万张照片各退避几秒。 */
    @Volatile
    private var vlmDegraded = false

    /**
     * v3.8.0：合并「状态流 + 通知」两次更新并节流。
     * 进度语义在 v3.9.0 改为「相册里已完整分析的张数」，而不是本轮处理序号。
     */
    private fun emitProgress(
        current: Int,
        total: Int,
        isResumed: Boolean,
        vlmEnabled: Boolean,
        vlmErrors: Int,
        statusText: String
    ) {
        val now = System.currentTimeMillis()
        if (current - lastEmittedProgress < PROGRESS_EMIT_STEP && now - lastEmitTime < 400L) return
        lastEmittedProgress = current
        lastEmitTime = now
        updateState(
            isRunning = true,
            progress = current,
            total = total,
            isComplete = false,
            isResumed = isResumed,
            vlmEnabled = vlmEnabled,
            vlmErrors = vlmErrors
        )
        updateNotification(statusText, current, total)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        resultStore = AnalysisResultStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在准备分析…", 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        )

        serviceScope.launch {
            runAnalysis()
        }

        // 不用 START_STICKY：后台静默重启前台服务在 Android 12+ 会受限，
        // 且云端 AI 分析会偷跑流量。改由 App 回到前台时自动续（见 MainActivity）。
        return START_NOT_STICKY
    }

    private suspend fun runAnalysis() {
        val repository = PhotoRepository(applicationContext)
        onDeviceAnalyzer = EnhancedImageAnalyzer(applicationContext)

        val useVlm = AppConfig.isVlmEnabled(applicationContext) && AppConfig.hasApiKey(applicationContext)
        if (useVlm) {
            vlmAnalyzer = VisionLanguageAnalyzer(applicationContext)
        }
        // 原「分段大小」现在只用于刷新 meta 计数的间隔，不再决定结果落盘粒度
        val metaFlushStep = AppConfig.getSegmentSize(applicationContext).coerceIn(10, 200)

        try {
            updateState(
                isRunning = true, progress = 0, total = 0, isComplete = false,
                isResumed = false, vlmEnabled = useVlm, vlmErrors = 0
            )
            val photos = repository.loadAllPhotos()
            allPhotos = photos

            if (photos.isEmpty()) {
                resultStore.markComplete(0, 0)
                updateState(
                    isRunning = false, isComplete = true, progress = 0, total = 0,
                    isResumed = false, vlmEnabled = useVlm, vlmErrors = 0
                )
                updateNotification("相册为空，无需分析", 0, 0)
                completed = true
                return
            }

            val saved = resultStore.load()
            val analyzed: Map<Long, PhotoMetadata> = saved?.metadataMap ?: emptyMap()

            // companion 是进程级静态 Map：Service 在同一进程内重启时先清干净，
            // 保证内存状态与磁盘进度完全一致，避免把旧结果当成「本轮新分析」。
            analyzedMetadata.clear()
            analyzedMetadata.putAll(analyzed)

            val plan = AnalysisPlanner.plan(photos.map { it.id }, analyzed, useVlm)

            if (plan.complete) {
                resultStore.markComplete(photos.size, analyzed.size)
                updateState(
                    isRunning = false, isComplete = true, progress = photos.size, total = photos.size,
                    isResumed = false, vlmEnabled = useVlm, vlmErrors = 0
                )
                updateNotification("分析完成，共 ${photos.size} 张照片", photos.size, photos.size)
                completed = true
                return
            }

            val isResumed = plan.resumed
            val byId = HashMap<Long, PhotoItem>(photos.size * 2)
            photos.forEach { byId[it.id] = it }

            updateState(
                isRunning = true, progress = plan.doneCount, total = photos.size,
                isComplete = false, isResumed = isResumed, vlmEnabled = useVlm, vlmErrors = 0
            )
            val startText = if (isResumed) {
                "继续分析：还剩 ${plan.pendingCount} 张（已完成 ${plan.doneCount}）"
            } else {
                "开始分析 ${plan.pendingCount} 张照片"
            }
            updateNotification(startText, plan.doneCount, photos.size)
            if (plan.retryVlmCount > 0) {
                Log.i(TAG, "本次补齐 ${plan.retryVlmCount} 张缺少 AI 描述的照片")
            }

            var done = plan.doneCount
            var sinceFlush = 0
            var degradedNotified = false
            val vlmErrorCount = AtomicInteger(0)
            vlmDegraded = false

            for (id in plan.pendingIds) {
                // 服务被停止时立刻退出；此前每一张都已单独落盘，不会回退
                currentCoroutineContext().ensureActive()
                val photo = byId[id] ?: continue

                if (vlmDegraded && !degradedNotified) {
                    degradedNotified = true
                    updateNotification("VLM 频繁出错，本轮改用本地分析（下次自动补齐 AI 描述）", done, photos.size)
                }
                val metadata = analyzeOne(photo, useVlm, vlmErrorCount)
                analyzedMetadata[id] = metadata
                resultStore.appendResult(id, metadata)          // 单张落盘，O(1)

                done++
                sinceFlush++
                if (sinceFlush >= metaFlushStep) {
                    resultStore.markProgress(photos.size, done)
                    sinceFlush = 0
                }

                val statusText = if (useVlm) {
                    "分析中 $done / ${photos.size}" +
                            if (vlmErrorCount.get() > 0) " (VLM错误:${vlmErrorCount.get()})" else ""
                } else {
                    "正在分析照片 $done / ${photos.size}"
                }
                emitProgress(done, photos.size, isResumed, useVlm, vlmErrorCount.get(), statusText)
            }

            resultStore.markComplete(photos.size, done)
            updateState(
                isRunning = false, isComplete = true, progress = done, total = photos.size,
                isResumed = false, vlmEnabled = useVlm, vlmErrors = vlmErrorCount.get()
            )
            val completeMsg = when {
                useVlm && vlmErrorCount.get() == 0 ->
                    "分析完成（含AI描述）${done}/${photos.size}"
                useVlm ->
                    "分析完成，但 ${vlmErrorCount.get()} 张缺少AI描述：可检查模型与 API Key 后再次进入应用自动补齐"
                else ->
                    "分析完成（仅本地标签）：开启 AI 描述可显著提升搜索效果"
            }
            updateNotification(completeMsg, done, photos.size)
            completed = true

        } catch (e: CancellationException) {
            // 不吞掉取消异常，交给 finally 写未完成标记
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "分析中断", e)
            updateState(
                isRunning = false, isComplete = false,
                progress = analyzedMetadata.size, total = allPhotos.size,
                isResumed = false, vlmEnabled = useVlm, vlmErrors = 0
            )
        } finally {
            if (!completed && allPhotos.isNotEmpty()) {
                // 中断 / 异常 / 取消：记录未完成，下次启动按 id 差集自动续
                resultStore.markProgress(allPhotos.size, analyzedMetadata.size)
            }
            onDeviceAnalyzer?.close()
            onDeviceAnalyzer = null
            vlmAnalyzer = null
            stopSelf()
        }
    }

    /** 分析单张：本地标签必写，云端 AI 失败时写占位结果（下次开启 AI 自动补齐）。 */
    private suspend fun analyzeOne(
        photo: PhotoItem,
        useVlm: Boolean,
        vlmErrorCount: AtomicInteger
    ): PhotoMetadata {
        val onDevice = try {
            onDeviceAnalyzer!!.analyzePhoto(photo.uri, applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "本地分析失败 [${photo.id}]", e)
            PhotoMetadata(photoId = photo.id, isAnalyzed = true)
        }

        var description = ""
        var vlmKeywords = emptyList<String>()
        if (useVlm && !vlmDegraded) {
            try {
                val vlmResult = vlmAnalyzer!!.analyzePhoto(photo.uri)
                if (vlmResult.success) {
                    description = vlmResult.description
                    vlmKeywords = vlmResult.keywords
                } else {
                    vlmErrorCount.incrementAndGet()
                    Log.w(TAG, "VLM分析失败 [${photo.id}]: ${vlmResult.error}")
                }
            } catch (e: Exception) {
                vlmErrorCount.incrementAndGet()
                Log.e(TAG, "VLM异常 [${photo.id}]", e)
            }
            if (vlmErrorCount.get() >= 20) {
                // 连续失败不再逐张重试：本轮剩余照片只写本地标签，
                // 这些占位结果会在下次开启 AI 时由 AnalysisPlanner 自动补齐。
                vlmDegraded = true
                Log.w(TAG, "VLM 连续失败 20 次，本轮降级为本地分析")
            }
        }

        return onDevice.copy(
            photoId = photo.id,
            description = description,
            vlmKeywords = vlmKeywords
        )
    }

    private fun updateState(
        isRunning: Boolean = _analysisState.value.isRunning,
        progress: Int = _analysisState.value.progress,
        total: Int = _analysisState.value.total,
        isComplete: Boolean = _analysisState.value.isComplete,
        isResumed: Boolean = _analysisState.value.isResumed,
        vlmEnabled: Boolean = _analysisState.value.vlmEnabled,
        vlmErrors: Int = _analysisState.value.vlmErrors
    ) {
        _analysisState.value = AnalysisProgress(
            isRunning = isRunning,
            progress = progress,
            total = total,
            isComplete = isComplete,
            isResumed = isResumed,
            vlmEnabled = vlmEnabled,
            vlmErrors = vlmErrors
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "照片分析",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "智能照片分析进度通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, total: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("智能相册搜索")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            builder.setProgress(total, progress, false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, total))
    }

    override fun onDestroy() {
        // 进程正常结束但本轮未跑完时兜底；强杀场景靠单张 appendResult 保证不丢
        if (!completed && allPhotos.isNotEmpty()) {
            try {
                resultStore.markProgress(allPhotos.size, analyzedMetadata.size)
            } catch (_: Exception) { }
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
