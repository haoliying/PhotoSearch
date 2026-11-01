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
import com.example.photosearch.R
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

        // Shared state observable from anywhere (ViewModel / Activity)
        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        // Cached analysis results: photoId -> labels
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
        val isComplete: Boolean = false
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var analyzer: ImageAnalyzer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

            updateState(isRunning = true, progress = 0, total = photos.size)

            val processedCount = AtomicInteger(0)
            val batchSize = 3
            val totalBatches = (photos.size + batchSize - 1) / batchSize

            for (batchIndex in 0 until totalBatches) {
                val start = batchIndex * batchSize
                val end = minOf(start + batchSize, photos.size)
                val batch = photos.subList(start, end)

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
                    total = photos.size
                )
                updateNotification("正在分析照片 $current / ${photos.size}", current, photos.size)
            }

            updateState(isRunning = false, isComplete = true)
            updateNotification("分析完成", photos.size, photos.size)

        } catch (e: Exception) {
            e.printStackTrace()
            updateState(isRunning = false, isComplete = true)
        } finally {
            analyzer?.close()
            analyzer = null
            // Delay a moment so user can see "complete" notification
            delay(2000)
            stopSelf()
        }
    }

    private fun updateState(
        isRunning: Boolean = _analysisState.value.isRunning,
        progress: Int = _analysisState.value.progress,
        total: Int = _analysisState.value.total,
        isComplete: Boolean = _analysisState.value.isComplete
    ) {
        _analysisState.value = AnalysisProgress(
            isRunning = isRunning,
            progress = progress,
            total = total,
            isComplete = isComplete
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
