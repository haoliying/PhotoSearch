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

    /**
     * v3.8.0：合并「状态流 + 通知」两次更新并节流。
     * 旧版每分析 1 张（本地模式 3 张）就 emit 一次 StateFlow，ViewModel 收到后
     * 会全量 map 一次照片列表并跑一次全库检索 —— 3 万张照片下这是主要假死来源。
     */
    private fun emitProgress(
        force: Boolean,
        current: Int,
        total: Int,
        isResumed: Boolean,
        vlmEnabled: Boolean,
        vlmErrors: Int,
        statusText: String
    ) {
        val now = System.currentTimeMillis()
        if (!force && current - lastEmittedProgress < PROGRESS_EMIT_STEP && now - lastEmitTime < 400L) return
        lastEmittedProgress = current
        lastEmitTime = now
        updateState(
            isRunning = true,
            progress = current,
            total = total,
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
        startForeground(NOTIFICATION_ID, buildNotification("正在分析照片…", 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        )

        serviceScope.launch {
            runAnalysis()
        }

        return START_NOT_STICKY
    }

    private suspend fun runAnalysis() {
        val repository = PhotoRepository(applicationContext)
        onDeviceAnalyzer = EnhancedImageAnalyzer(applicationContext)

        val useVlm = AppConfig.isVlmEnabled(applicationContext) && AppConfig.hasApiKey(applicationContext)
        if (useVlm) {
            vlmAnalyzer = VisionLanguageAnalyzer(applicationContext)
        }

        val segmentSize = AppConfig.getSegmentSize(applicationContext)

        try {
            updateState(isRunning = true, progress = 0, total = 0, vlmEnabled = useVlm)
            val photos = repository.loadAllPhotos()
            allPhotos = photos

            if (photos.isEmpty()) {
                updateState(isRunning = false, isComplete = true, vlmEnabled = useVlm)
                stopSelf()
                return
            }

            // Check for saved progress to resume
            val saved = resultStore.load()
            val startIndex: Int
            val isResumed: Boolean

            if (saved != null && !saved.isComplete && saved.totalPhotos == photos.size) {
                analyzedMetadata.putAll(saved.metadataMap)
                startIndex = saved.analyzedCount
                isResumed = true
                updateNotification("恢复分析 ${startIndex} / ${photos.size}", startIndex, photos.size)
            } else {
                if (saved != null && saved.totalPhotos != photos.size) {
                    resultStore.clear()
                }
                startIndex = 0
                isResumed = false
            }

            updateState(isRunning = true, progress = startIndex, total = photos.size, isResumed = isResumed, vlmEnabled = useVlm)

            val processedCount = AtomicInteger(startIndex)
            val vlmErrorCount = AtomicInteger(0)

            var segmentStart = startIndex
            while (segmentStart < photos.size) {
                val segmentEnd = minOf(segmentStart + segmentSize, photos.size)
                val segment = photos.subList(segmentStart, segmentEnd)

                // Process one at a time when VLM is enabled (to avoid API rate limits)
                // Process in batches when only using on-device analysis
                if (useVlm) {
                    for (photo in segment) {
                        try {
                            // On-device analysis (fast, offline)
                            val onDeviceMetadata = onDeviceAnalyzer!!.analyzePhoto(photo.uri, applicationContext)

                            // VLM analysis (slow, requires network, much more accurate)
                            var description = ""
                            var vlmKeywords = listOf<String>()
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
                                // Rate limit protection: if too many errors, skip VLM for remaining
                                if (vlmErrorCount.get() > 10) {
                                    updateNotification("VLM API 频繁出错，切换到本地分析", processedCount.get(), photos.size)
                                    delay(5000) // Back off
                                }
                            }

                            val metadata = onDeviceMetadata.copy(
                                photoId = photo.id,
                                description = description,
                                vlmKeywords = vlmKeywords
                            )
                            analyzedMetadata[photo.id] = metadata
                        } catch (e: Exception) {
                            Log.e(TAG, "分析照片失败 [${photo.id}]", e)
                            // 即使本地分析失败也写入占位结果，保证照片在检索时不会被直接丢弃
                            analyzedMetadata[photo.id] = PhotoMetadata(
                                photoId = photo.id, isAnalyzed = true
                            )
                        }

                        val current = processedCount.incrementAndGet()
                        val statusText = if (useVlm) {
                            "分析中 $current / ${photos.size}" + if (vlmErrorCount.get() > 0) " (VLM错误:${vlmErrorCount.get()})" else ""
                        } else {
                            "正在分析照片 $current / ${photos.size}"
                        }
                        emitProgress(false, current, photos.size, isResumed, useVlm,
                            vlmErrorCount.get(), statusText)
                    }
                } else {
                    // On-device only: batch processing
                    val batchSize = 3
                    val totalBatches = (segment.size + batchSize - 1) / batchSize

                    for (batchIndex in 0 until totalBatches) {
                        val batchStart = batchIndex * batchSize
                        val batchEnd = minOf(batchStart + batchSize, segment.size)
                        val batch = segment.subList(batchStart, batchEnd)

                        val results = batch.map { photo ->
                            val metadata = onDeviceAnalyzer!!.analyzePhoto(photo.uri, applicationContext)
                            photo.id to metadata.copy(photoId = photo.id)
                        }

                        results.forEach { (id, metadata) ->
                            analyzedMetadata[id] = metadata
                        }

                        val current = processedCount.addAndGet(batch.size)
                        emitProgress(false, current, photos.size, isResumed, useVlm, 0,
                            "正在分析照片 $current / ${photos.size}")
                    }
                }

                // Save progress after each segment
                resultStore.saveSegment(
                    totalPhotos = photos.size,
                    analyzedCount = segmentEnd,
                    isComplete = segmentEnd >= photos.size,
                    metadataMap = analyzedMetadata
                )

                segmentStart = segmentEnd
            }

            updateState(isRunning = false, isComplete = true, progress = photos.size,
                total = photos.size, isResumed = false, vlmEnabled = useVlm, vlmErrors = vlmErrorCount.get())
            val completeMsg = when {
                useVlm && vlmErrorCount.get() == 0 ->
                    "分析完成（含AI描述）${photos.size}/${photos.size}"
                useVlm ->
                    "分析完成，但 ${vlmErrorCount.get()} 张缺少AI描述：请检查设置里的模型与 API Key，然后点「重新分析全部」"
                else ->
                    "分析完成（仅本地标签）：开启 AI 描述可显著提升搜索效果"
            }
            updateNotification(completeMsg, photos.size, photos.size)

        } catch (e: Exception) {
            e.printStackTrace()
            if (analyzedMetadata.isNotEmpty() && allPhotos.isNotEmpty()) {
                resultStore.saveSegment(
                    totalPhotos = allPhotos.size,
                    analyzedCount = analyzedMetadata.size,
                    isComplete = false,
                    metadataMap = analyzedMetadata
                )
            }
            updateState(isRunning = false, isComplete = false, vlmEnabled = useVlm)
        } finally {
            onDeviceAnalyzer?.close()
            onDeviceAnalyzer = null
            vlmAnalyzer = null
            delay(2000)
            stopSelf()
        }
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
        serviceScope.cancel()
        super.onDestroy()
    }
}
