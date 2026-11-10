package com.example.photosearch.service

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v3.10.0：监听系统相册变化。
 *
 * ContentObserver 只回调「某处变了」，不携带具体增删；由外部对账逻辑重新查询
 * MediaStore 并用 [AnalysisPlanner.diff] 求差，只分析真正新增的照片。
 *
 * 防抖：拍照、批量下载、截图常连续触发多次 onChange，1.5s 内合并为一次对账，
 * 避免刚插入一张就反复全量查询 / 反复唤醒分析循环。
 *
 * 用主线程的 Handler 接收回调，但回调里立刻切到传入的协程作用域做防抖，
 * 不占用主线程；注销时一并取消未触发的防抖任务。
 */
class MediaStoreObserver(
    private val scope: CoroutineScope,
    private val onChangeDebounced: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    @Volatile
    private var registered = false
    private var debounceJob: Job? = null

    fun register(resolver: ContentResolver) {
        if (registered) return
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        resolver.registerContentObserver(uri, true, this)
        registered = true
    }

    fun unregister(resolver: ContentResolver) {
        if (!registered) return
        debounceJob?.cancel()
        debounceJob = null
        try {
            resolver.unregisterContentObserver(this)
        } catch (_: Exception) {
            // 服务被系统回收等场景，忽略
        }
        registered = false
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        scheduleDebounce()
    }

    override fun onChange(selfChange: Boolean) {
        scheduleDebounce()
    }

    private fun scheduleDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            onChangeDebounced()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L
    }
}
