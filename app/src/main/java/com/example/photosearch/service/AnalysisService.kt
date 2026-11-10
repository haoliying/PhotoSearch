package com.example.photosearch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.10.0：**常驻后台守护 + 相册变化监听 + 增量自动分析 + 检索隔离**。
 *
 * v3.9.0 及以前是一次性前台服务：跑完一轮就 stopSelf()、START_NOT_STICKY，
 * 既不常驻也不感知相册变化，新拍的照片只能靠手动刷新或下次回到前台才发现；
 * 而且服务完成时会让 ViewModel 做「全量合并 + docCache.clear() + 全库检索」，
 * 若常驻并不断有小批次完成，会反复清空几万张索引、卡住前端搜索。
 *
 * 现在：
 *  - START_STICKY 常驻，注册 [MediaStoreObserver] 监听相册；分析循环跑完不 stopSelf，
 *    继续保持监听；开机/升级由 [com.example.photosearch.receiver.BootReceiver] 尽力恢复，
 *    App 回到前台兜底拉起（MainActivity.onResume）。
 *  - 相册变化 → 重新查 MediaStore → [AnalysisPlanner.diff] 求差 → [AnalysisPlanner.plan]
 *    只挑新增/未完成的照片分析，每张立即 appendResult 落盘（磁盘是唯一权威）。
 *  - 分析结果通过 [AnalysisEvents] 以「增量批次」通知前端；[IncrementBuffer] 负责
 *    去重、后写覆盖与节流。前端只合并这几张、只失效这几张的文档缓存，
 *    **绝不全量 clear / 全库重跑检索** —— 后台分析不影响前端搜索。
 *  - 分析循环跑在低优先级单线程（THREAD_PRIORITY_BACKGROUND），
 *    检索在 Dispatchers.Default，主线程只做 O(1) 状态赋值，三者互不抢占、无共享锁。
 *  - 常驻通知带「停止守护」action，用户随时可关；设置页也有开关。
 */
class AnalysisService : Service() {

    companion object {
        const val CHANNEL_ID = "analysis_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_WATCH_START = "com.example.photosearch.action.WATCH_START"
        const val ACTION_SCAN = "com.example.photosearch.action.SCAN"
        const val ACTION_REANALYZE_ALL = "com.example.photosearch.action.REANALYZE_ALL"
        const val ACTION_STOP = "com.example.photosearch.action.STOP"
        /** 兼容旧调用点（v3.9.0 的 resume）。 */
        const val ACTION_RESUME = "com.example.photosearch.action.RESUME"

        private const val TAG = "AnalysisService"
        private const val PROGRESS_EMIT_STEP = 20
        private const val RESULT_BATCH_MAX = 20
        private const val RESULT_BATCH_INTERVAL_MS = 300L

        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        /** 进程内镜像：仅供同进程冷启动快速读取，真正权威是磁盘。 */
        val analyzedMetadata = ConcurrentHashMap<Long, PhotoMetadata>()

        /** 服务是否处于常驻守护状态（区别于「分析循环是否正在跑」）。 */
        @Volatile
        var watchActive: Boolean = false
            private set

        fun isRunning(): Boolean = _analysisState.value.isRunning
        fun isWatchActive(): Boolean = watchActive

        fun reset() {
            _analysisState.value = AnalysisProgress()
            analyzedMetadata.clear()
        }

        // --- 外部请求入口（从 App 前台调用，避免后台启动前台服务限制） ---

        fun requestWatch(context: Context) = start(context, ACTION_WATCH_START)
        fun requestScan(context: Context) = start(context, ACTION_SCAN)
        fun requestReanalyzeAll(context: Context) = start(context, ACTION_REANALYZE_ALL)

        fun requestStop(context: Context) {
            if (!watchActive) return
            try {
                context.startService(Intent(context, AnalysisService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "停止守护失败", e)
            }
        }

        private fun start(context: Context, action: String) {
            try {
                val i = Intent(context, AnalysisService::class.java).setAction(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 后台启动受限时置 pending，等 App 回到前台兜底
                AppConfig.setWatchPending(context, true)
                Log.w(TAG, "启动守护受限，等待下次进入应用恢复", e)
            }
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 分析专用低优先级线程：把 Linux nice 调到后台级，避免几万张照片的
     * 图片解码 / JSON 落盘与主线程、检索线程（Dispatchers.Default）抢 CPU。
     * 这是「后台分析不影响前端搜索」的第一层隔离。
     */
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "ps-analysis").apply { isDaemon = true }
    }
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()

    private lateinit var resultStore: AnalysisResultStore
    private lateinit var observer: MediaStoreObserver

    private var analysisJob: Job? = null
    private var onDeviceAnalyzer: EnhancedImageAnalyzer? = null
    private var vlmAnalyzer: VisionLanguageAnalyzer? = null

    /** 上一次对账的相册 id 快照，用于检测运行期新增/删除。 */
    @Volatile
    private var lastAlbumIds: Set<Long> = emptySet()
    /** 服务本次启动后是否已完成过至少一次对账（首次对账不通知 UI 增量刷新）。 */
    @Volatile
    private var hasReconciledOnce = false
    /** 本次守护期间累计分析完成的张数（通知与 UI 展示用）。 */
    @Volatile
    private var autoAnalyzedCount = 0
    /** 本轮循环是否正常跑完；决定中断兜底是否写 markProgress。 */
    @Volatile
    private var completed = false
    @Volatile
    private var vlmDegraded = false

    private var lastEmittedProgress = -1
    private var lastEmitTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        resultStore = AnalysisResultStore(applicationContext)
        observer = MediaStoreObserver(serviceScope) {
            // ContentObserver 防抖后回调：只做增量对账，不重新启动服务
            dispatchAnalysis(forceFull = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 契约：startForegroundService 启动后必须尽快进入前台。
        // 三参数重载（带 foregroundServiceType）是 API 29+ 才有的方法，
        // 在 API 26~28 上直接调用会 NoSuchMethodError，这里按版本分支。
        val notification = buildNotification("后台守护中 · 新照片将自动分析", 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val wasActive = watchActive
        val watchEnabled = AppConfig.isBackgroundWatchEnabled(this)
        watchActive = true
        AppConfig.markWatchStarted(this)
        AppConfig.setWatchPending(this, false)
        // 守护关闭时不注册相册监听、跑完一轮即退出（退化为 v3.9.0 的一次性分析）
        if (watchEnabled) observer.register(contentResolver) else observer.unregister(contentResolver)
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(
                active = true,
                autoAnalyzed = autoAnalyzedCount,
                analyzing = analysisJob?.isActive == true
            )
        )

        when (intent?.action) {
            ACTION_STOP -> {
                stopWatch()
                return START_NOT_STICKY
            }
            ACTION_REANALYZE_ALL -> dispatchAnalysis(forceFull = true, clearFirst = true)
            ACTION_SCAN -> dispatchAnalysis(forceFull = false)
            ACTION_WATCH_START, ACTION_RESUME, null -> {
                // 首次拉起（含 START_STICKY 系统重启）做一次全量对账续跑；
                // 服务已常驻时什么都不做 —— ContentObserver 已在监听，避免每次
                // onResume 都重复全量读盘。
                if (!wasActive) dispatchAnalysis(forceFull = false)
            }
        }
        // 守护开启才常驻：被系统回收后由系统重启，磁盘结果是权威，重启即自动续跑；
        // 守护关闭时返回 NOT_STICKY，服务跑完一轮自行退出。
        return if (watchEnabled) START_STICKY else START_NOT_STICKY
    }

    /**
     * 串行调度分析。已有循环在跑且非 forceFull 时直接跳过（循环末尾会再对账一次，
     * 不会漏掉分析期间新增的照片），避免并发分析同一批、也避免高频唤醒。
     */
    private fun dispatchAnalysis(forceFull: Boolean = false, clearFirst: Boolean = false) {
        val active = analysisJob?.isActive == true
        if (active && !forceFull) return
        if (active) analysisJob?.cancel()
        analysisJob = serviceScope.launch(analysisDispatcher) {
            runUntilSettled(clearFirst)
        }
    }

    /**
     * 对账 + 分析，直到相册里没有待分析照片为止。
     * 跑完一轮后再次循环：若分析期间又有新照片进来，下一轮会检出并继续，
     * 否则 plan.complete 直接返回 —— 服务本身不退出，继续靠 observer 监听。
     */
    private suspend fun runUntilSettled(clearFirst: Boolean) {
        if (clearFirst) {
            resultStore.clear()
            analyzedMetadata.clear()
            lastAlbumIds = emptySet()
            hasReconciledOnce = false
            autoAnalyzedCount = 0
            completed = false
            AnalysisEvents.resetWatch()
            AnalysisEvents.emitWatch(AnalysisEvents.WatchState(active = true))
        }

        val useVlm = AppConfig.isVlmEnabled(this@AnalysisService) &&
                AppConfig.hasApiKey(this@AnalysisService)
        val metaFlushStep = AppConfig.getSegmentSize(this@AnalysisService).coerceIn(10, 200)
        val repository = PhotoRepository(this@AnalysisService)

        onDeviceAnalyzer = EnhancedImageAnalyzer(this@AnalysisService)
        vlmAnalyzer = if (useVlm) VisionLanguageAnalyzer(this@AnalysisService) else null

        try {
            var round = 0
            while (currentCoroutineContext().isActive) {
                val photos = repository.loadAllPhotos()
                val ids = photos.map { it.id }

                val delta = AnalysisPlanner.diff(lastAlbumIds, ids)
                lastAlbumIds = ids.toHashSet()
                // 只对「运行期变化」通知前端增量刷新；首次全量对账由 App 自己 loadPhotos 覆盖
                if (hasReconciledOnce && delta.changed) {
                    AnalysisEvents.emitAlbum(
                        AnalysisEvents.AlbumChanged(delta.added.size, delta.removed.size)
                    )
                }
                hasReconciledOnce = true

                val stored = resultStore.load()
                val analyzed: Map<Long, PhotoMetadata> = stored?.metadataMap ?: emptyMap()
                if (round == 0) {
                    analyzedMetadata.clear()
                    analyzedMetadata.putAll(analyzed)
                }

                val plan = AnalysisPlanner.plan(ids, analyzed, useVlm)

                if (plan.complete) {
                    resultStore.markComplete(photos.size, analyzed.size)
                    emitIdle(photos.size, useVlm)
                    completed = true
                    // 守护关闭：退化为一次性分析（v3.9.0 行为），跑完即退出，不常驻
                    if (!AppConfig.isBackgroundWatchEnabled(this@AnalysisService)) {
                        stopAfterOneShot()
                    }
                    return
                }

                val byId = HashMap<Long, PhotoItem>(photos.size * 2)
                photos.forEach { byId[it.id] = it }

                runPlan(photos, byId, plan, useVlm, metaFlushStep,
                    autoTriggered = hasReconciledOnce && round > 0)
                round++
                // 继续 while：把分析期间新增的照片也纳入下一次对账
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "后台分析中断", e)
            updateState(isRunning = false, isComplete = false)
        } finally {
            if (!completed && analyzedMetadata.isNotEmpty()) {
                resultStore.markProgress(analyzedMetadata.size, analyzedMetadata.size)
            }
            onDeviceAnalyzer?.close()
            onDeviceAnalyzer = null
            vlmAnalyzer = null
            AnalysisEvents.emitWatch(
                AnalysisEvents.WatchState(active = watchActive, autoAnalyzed = autoAnalyzedCount)
            )
        }
    }

    /** 跑一批 pending：每张落盘 + 增量事件 + 节流进度。 */
    private suspend fun runPlan(
        photos: List<PhotoItem>,
        byId: Map<Long, PhotoItem>,
        plan: AnalysisPlanner.Plan,
        useVlm: Boolean,
        metaFlushStep: Int,
        autoTriggered: Boolean
    ) {
        var done = plan.doneCount
        var sinceFlush = 0
        var degradedNotified = false
        val vlmErrorCount = AtomicInteger(0)
        vlmDegraded = false

        val buffer = ArrayList<Pair<Long, PhotoMetadata>>(RESULT_BATCH_MAX)
        var lastFlushMs = System.currentTimeMillis()

        updateState(
            isRunning = true, progress = done, total = photos.size, isComplete = false,
            isResumed = plan.resumed, vlmEnabled = useVlm, vlmErrors = 0
        )
        val startText = if (plan.resumed) {
            "继续分析：还剩 ${plan.pendingCount} 张（已完成 ${plan.doneCount}）"
        } else {
            "开始分析 ${plan.pendingCount} 张照片"
        }
        updateNotification(startText, done, photos.size)
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(active = true, autoAnalyzed = autoAnalyzedCount, analyzing = true)
        )

        try {
            for (id in plan.pendingIds) {
                // 服务停止 / 被 forceFull 打断时立即退出；此前每张都已单独落盘
                currentCoroutineContext().ensureActive()
                val photo = byId[id] ?: continue

                if (vlmDegraded && !degradedNotified) {
                    degradedNotified = true
                    updateNotification("AI 频繁出错，本轮改用本地分析（下次自动补齐）", done, photos.size)
                }

                val metadata = analyzeOne(photo, useVlm, vlmErrorCount)
                resultStore.appendResult(id, metadata)          // 单张落盘，O(1)，唯一权威
                analyzedMetadata[id] = metadata
                buffer += id to metadata

                done++
                sinceFlush++
                autoAnalyzedCount++
                if (sinceFlush >= metaFlushStep) {
                    resultStore.markProgress(photos.size, done)
                    sinceFlush = 0
                }

                val now = System.currentTimeMillis()
                if (IncrementBuffer.shouldFlush(
                        buffer.size, lastFlushMs, now, RESULT_BATCH_MAX, RESULT_BATCH_INTERVAL_MS
                    )
                ) {
                    val batch = IncrementBuffer.coalesce(buffer)
                    buffer.clear()
                    // 增量事件：前端只合并这批 id、只失效这批文档缓存，绝不全量重算
                    AnalysisEvents.emitResults(AnalysisEvents.ResultBatch(batch, autoTriggered))
                    AnalysisEvents.emitWatch(
                        AnalysisEvents.WatchState(true, autoAnalyzedCount, analyzing = true)
                    )
                    lastFlushMs = now
                }

                val statusText = if (useVlm) {
                    "后台分析 $done / ${photos.size}" +
                            if (vlmErrorCount.get() > 0) " (AI错误:${vlmErrorCount.get()})" else ""
                } else {
                    "后台分析照片 $done / ${photos.size}"
                }
                emitProgress(done, photos.size, plan.resumed, useVlm, vlmErrorCount.get(), statusText)
            }

            if (buffer.isNotEmpty()) {
                AnalysisEvents.emitResults(
                    AnalysisEvents.ResultBatch(IncrementBuffer.coalesce(buffer), autoTriggered)
                )
                buffer.clear()
            }

            resultStore.markComplete(photos.size, done)
            updateState(
                isRunning = false, isComplete = true, progress = done, total = photos.size,
                isResumed = false, vlmEnabled = useVlm, vlmErrors = vlmErrorCount.get()
            )
            val completeMsg = when {
                useVlm && vlmErrorCount.get() == 0 -> "已分析 ${done} 张照片（含AI描述）"
                useVlm -> "已分析 ${done} 张，${vlmErrorCount.get()} 张缺AI描述，下次自动补齐"
                else -> "已分析 ${done} 张（仅本地标签）"
            }
            updateNotification(completeMsg, done, photos.size)
            completed = true
        } catch (e: CancellationException) {
            // 已落盘的结果即使事件没发出也不会丢：下次打开 App 会从磁盘 load() 补齐
            if (buffer.isNotEmpty()) {
                AnalysisEvents.emitResults(
                    AnalysisEvents.ResultBatch(IncrementBuffer.coalesce(buffer), autoTriggered)
                )
            }
            resultStore.markProgress(photos.size, done)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "单批分析异常", e)
            resultStore.markProgress(photos.size, done)
            updateState(isRunning = false, isComplete = false)
        }
    }

    /** 无待分析照片：更新状态，通知切回「守护中」，但服务不退。 */
    private fun emitIdle(total: Int, useVlm: Boolean) {
        updateState(
            isRunning = false, isComplete = true, progress = total, total = total,
            isResumed = false, vlmEnabled = useVlm, vlmErrors = 0
        )
        updateNotification(
            if (autoAnalyzedCount > 0) "后台守护中 · 本次已分析 $autoAnalyzedCount 张，新照片将自动分析"
            else "后台守护中 · 新照片将自动分析",
            0, 0
        )
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(active = true, autoAnalyzed = autoAnalyzedCount, analyzing = false)
        )
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
                    Log.w(TAG, "AI分析失败 [${photo.id}]: ${vlmResult.error}")
                }
            } catch (e: Exception) {
                vlmErrorCount.incrementAndGet()
                Log.e(TAG, "AI异常 [${photo.id}]", e)
            }
            if (vlmErrorCount.get() >= 20) {
                vlmDegraded = true
                Log.w(TAG, "AI 连续失败 20 次，本轮降级为本地分析")
            }
        }

        return onDevice.copy(
            photoId = photo.id,
            description = description,
            vlmKeywords = vlmKeywords
        )
    }

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
            isRunning = true, progress = current, total = total, isComplete = false,
            isResumed = isResumed, vlmEnabled = vlmEnabled, vlmErrors = vlmErrors
        )
        updateNotification(statusText, current, total)
    }

    private fun stopWatch() {
        observer.unregister(contentResolver)
        analysisJob?.cancel()
        watchActive = false
        completed = true
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(active = false, autoAnalyzed = autoAnalyzedCount)
        )
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 守护关闭时的一次性收尾：注销监听、退出前台、停止服务（不取消正在结束的循环）。 */
    private fun stopAfterOneShot() {
        observer.unregister(contentResolver)
        watchActive = false
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(active = false, autoAnalyzed = autoAnalyzedCount)
        )
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            isRunning = isRunning, progress = progress, total = total,
            isComplete = isComplete, isResumed = isResumed,
            vlmEnabled = vlmEnabled, vlmErrors = vlmErrors
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "照片分析", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台自动分析守护与进度通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AnalysisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("智能相册搜索 · 后台守护")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止守护", stopIntent)

        if (total > 0) builder.setProgress(total, progress, false)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress, total))
    }

    override fun onDestroy() {
        observer.unregister(contentResolver)
        watchActive = false
        if (!completed && analyzedMetadata.isNotEmpty()) {
            try {
                resultStore.markProgress(analyzedMetadata.size, analyzedMetadata.size)
            } catch (_: Exception) { }
        }
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(active = false, autoAnalyzed = autoAnalyzedCount)
        )
        serviceScope.cancel()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
