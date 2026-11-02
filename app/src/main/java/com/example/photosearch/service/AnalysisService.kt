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
import com.example.photosearch.data.AnalysisResultStore
import com.example.photosearch.data.AppConfig
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoLabel
import com.example.photosearch.data.PhotoRepository
import com.example.photosearch.ml.ImageAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

class AnalysisService : Service() {

    companion object {
        const val CHANNEL_ID = "analysis_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_RESUME = "com.example.photosearch.action.RESUME"

        // Shared state observable from anywhere (ViewModel / Activity)
        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        // In-memory cache of analyzed labels (also persisted to disk)
        val analyzedLabels = mutableMapOf<Long, List<PhotoLabel>>()

        // All photos loaded by the service
        var allPhotos: List<PhotoItem> = emptyList()
            private set

        fun isRunning(): Boolean = _analysisState.value.isRunning

        fun reset() {
            _analysisState.value = AnalysisProgress()
            analyzedLabels.clear()
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
    private var analyzer: ImageAnalyzer? = null
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
        analyzer = ImageAnalyzer(applicationContext)
        val segmentSize = AppConfig.getSegmentSize(applicationContext)

        try {
            // Load photos
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
                // Resume from saved progress
                analyzedLabels.putAll(saved.results)
                startIndex = saved.analyzedCount
                isResumed = true
                updateNotification("恢复分析 ${startIndex} / ${photos.size}", startIndex, photos.size)
            } else {
                // Fresh start
                if (saved != null && saved.totalPhotos != photos.size) {
                    // Photo count changed, clear old results
                    resultStore.clear()
                }
                startIndex = 0
                isResumed = false
            }

            updateState(isRunning = true, progress = startIndex, total = photos.size, isResumed = isResumed)

            val processedCount = AtomicInteger(startIndex)

            // Process in configurable segments
            var segmentStart = startIndex
            while (segmentStart < photos.size) {
                val segmentEnd = minOf(segmentStart + segmentSize, photos.size)
                val segment = photos.subList(segmentStart, segmentEnd)

                // Process this segment in smaller batches for UI updates
                val batchSize = 3
                val totalBatches = (segment.size + batchSize - 1) / batchSize

                for (batchIndex in 0 until totalBatches) {
                    val batchStart = batchIndex * batchSize
                    val batchEnd = minOf(batchStart + batchSize, segment.size)
                    val batch = segment.subList(batchStart, batchEnd)

                    val results = batch.map { photo ->
                        val labels = analyzer!!.analyzePhoto(photo.uri, applicationContext)
                        photo.id to labels
                    }

                    results.forEach { (id, labels) ->
                        analyzedLabels[id] = labels
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

                // Save progress after each segment completes
                resultStore.saveSegment(
                    totalPhotos = photos.size,
                    analyzedCount = segmentEnd,
                    isComplete = segmentEnd >= photos.size,
                    results = analyzedLabels.toMap()
                )

                segmentStart = segmentEnd
            }

            updateState(isRunning = false, isComplete = true, isResumed = false)
            updateNotification("分析完成 ${photos.size} / ${photos.size}", photos.size, photos.size)

        } catch (e: Exception) {
            e.printStackTrace()
            // Save progress even on error so we can resume
            if (analyzedLabels.isNotEmpty() && allPhotos.isNotEmpty()) {
                resultStore.saveSegment(
                    totalPhotos = allPhotos.size,
                    analyzedCount = analyzedLabels.size,
                    isComplete = false,
                    results = analyzedLabels.toMap()
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
