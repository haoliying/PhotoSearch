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
import androidx.core.app.NotificationCompat
import com.example.photosearch.MainActivity
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

class AnalysisService : Service() {

    companion object {
        const val CHANNEL_ID = "analysis_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_RESUME = "com.example.photosearch.action.RESUME"

        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        // In-memory cache of analyzed metadata (also persisted to disk)
        val analyzedMetadata = mutableMapOf<Long, PhotoMetadata>()

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
        val isResumed: Boolean = false
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var analyzer: EnhancedImageAnalyzer? = null
    private lateinit var resultStore: AnalysisResultStore

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
        analyzer = EnhancedImageAnalyzer(applicationContext)
        val segmentSize = AppConfig.getSegmentSize(applicationContext)

        try {
            updateState(isRunning = true, progress = 0, total = 0)
            val photos = repository.loadAllPhotos()
            allPhotos = photos

            if (photos.isEmpty()) {
                updateState(isRunning = false, isComplete = true)
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

            updateState(isRunning = true, progress = startIndex, total = photos.size, isResumed = isResumed)

            val processedCount = AtomicInteger(startIndex)

            var segmentStart = startIndex
            while (segmentStart < photos.size) {
                val segmentEnd = minOf(segmentStart + segmentSize, photos.size)
                val segment = photos.subList(segmentStart, segmentEnd)

                val batchSize = 3
                val totalBatches = (segment.size + batchSize - 1) / batchSize

                for (batchIndex in 0 until totalBatches) {
                    val batchStart = batchIndex * batchSize
                    val batchEnd = minOf(batchStart + batchSize, segment.size)
                    val batch = segment.subList(batchStart, batchEnd)

                    val results = batch.map { photo ->
                        val metadata = analyzer!!.analyzePhoto(photo.uri, applicationContext)
                        photo.id to metadata.copy(photoId = photo.id)
                    }

                    results.forEach { (id, metadata) ->
                        analyzedMetadata[id] = metadata
                    }

                    val current = processedCount.addAndGet(batch.size)
                    updateState(
                        isRunning = true,
                        progress = current,
                        total = photos.size,
                        isResumed = isResumed
                    )
                    updateNotification("正在分析照片 $current / ${photos.size}", current, photos.size)
                }

                // Save progress after each segment
                resultStore.saveSegment(
                    totalPhotos = photos.size,
                    analyzedCount = segmentEnd,
                    isComplete = segmentEnd >= photos.size,
                    metadataMap = analyzedMetadata.toMap()
                )

                segmentStart = segmentEnd
            }

            updateState(isRunning = false, isComplete = true, isResumed = false)
            updateNotification("分析完成 ${photos.size} / ${photos.size}", photos.size, photos.size)

        } catch (e: Exception) {
            e.printStackTrace()
            if (analyzedMetadata.isNotEmpty() && allPhotos.isNotEmpty()) {
                resultStore.saveSegment(
                    totalPhotos = allPhotos.size,
                    analyzedCount = analyzedMetadata.size,
                    isComplete = false,
                    metadataMap = analyzedMetadata.toMap()
                )
            }
            updateState(isRunning = false, isComplete = false)
        } finally {
            analyzer?.close()
            analyzer = null
            delay(2000)
            stopSelf()
        }
    }

    private fun updateState(
        isRunning: Boolean = _analysisState.value.isRunning,
        progress: Int = _analysisState.value.progress,
        total: Int = _analysisState.value.total,
        isComplete: Boolean = _analysisState.value.isComplete,
        isResumed: Boolean = _analysisState.value.isResumed
    ) {
        _analysisState.value = AnalysisProgress(
            isRunning = isRunning,
            progress = progress,
            total = total,
            isComplete = isComplete,
            isResumed = isResumed
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
