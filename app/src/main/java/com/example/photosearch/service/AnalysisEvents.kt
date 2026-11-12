package com.example.photosearch.service

import com.example.photosearch.data.PhotoMetadata
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v3.10.0：后台分析服务 → 前端的进程内增量事件总线。
 *
 * 设计原则：**磁盘（AnalysisResultStore）才是结果的唯一权威**。服务每张落盘；
 * 这里的事件只是「运行期加速」，让 App 在前台时无需重读磁盘就能立刻看到新照片
 * 与新标签，同时又不打断正在进行的搜索：
 *  - 事件都是「增量 id 级」，前端只合并这几张、只失效这几张的文档缓存；
 *  - 事件丢失（App 被划掉、进程回收、缓冲溢出）不会造成任何错误，
 *    下次打开 App 时 loadPhotos() 从磁盘读全量，自然补齐。
 *
 * 因此这里刻意不用 replay 缓存、也不用可靠队列：宁可丢事件，也不让
 * 后台分析把几万条结果堆进内存或逼前端做全量重算。
 */
object AnalysisEvents {

    /** 一批新完成的分析结果（已由 IncrementBuffer 去重，id -> metadata）。 */
    data class ResultBatch(
        val results: Map<Long, PhotoMetadata>,
        /** 是否由相册变化自动触发（区别于用户手动「开始分析」）。 */
        val autoTriggered: Boolean
    ) {
        val size: Int get() = results.size
    }

    /** 相册内容发生变化，通知前端增量刷新列表（新照片立即显示）。 */
    data class AlbumChanged(val addedCount: Int, val removedCount: Int) {
        val changed: Boolean get() = addedCount > 0 || removedCount > 0
    }

    /** 后台守护运行状态。用 StateFlow 保证前端一订阅就能拿到当前值。 */
    data class WatchState(
        val active: Boolean = false,
        /** 本次守护期间自动分析完成的新照片数。 */
        val autoAnalyzed: Int = 0,
        /** 当前是否正在跑分析循环。 */
        val analyzing: Boolean = false,
        /** v3.11.2：用户显式暂停分析（暂停时守护仍常驻，只是不再处理照片）。 */
        val paused: Boolean = false
    )

    private val _results = MutableSharedFlow<ResultBatch>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<ResultBatch> = _results.asSharedFlow()

    private val _albumChanges = MutableSharedFlow<AlbumChanged>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val albumChanges: SharedFlow<AlbumChanged> = _albumChanges.asSharedFlow()

    private val _watchState = MutableStateFlow(WatchState())
    val watchState: StateFlow<WatchState> = _watchState.asStateFlow()

    fun emitResults(batch: ResultBatch) {
        if (batch.results.isNotEmpty()) _results.tryEmit(batch)
    }

    fun emitAlbum(change: AlbumChanged) {
        if (change.changed) _albumChanges.tryEmit(change)
    }

    fun emitWatch(state: WatchState) {
        _watchState.value = state
    }

    /** 供测试 / 服务重启时重置守护状态，避免残留旧值。 */
    fun resetWatch() {
        _watchState.value = WatchState()
    }
}
