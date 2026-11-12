package com.example.photosearch.service

/**
 * v3.11.1：端侧分析提速的调度纯函数。
 *
 * 背景：v3.11.0 及以前的批量分析是全局单线程串行（newSingleThreadExecutor +
 * 逐张 for 循环），即使每张只要 1 秒，一万张也要近 3 小时；而 ML Kit 的
 * Labeler / ObjectDetector / FaceDetector 本身线程安全，完全可以多张并行。
 *
 * 本对象只放**无 Android 依赖**的纯逻辑，方便 tools/V312Eval.kt 离线评测：
 *  - [coerceParallelism]：并发数收敛；
 *  - [chunksOf]：把待分析队列切成「块内并发、块间串行收尾」的批次；
 *  - [orientationToDegrees]：EXIF 方向常量 → 旋转角（不依赖 androidx，
 *    常量值与 ExifInterface 对应：ROTATE_90=6、ROTATE_180=3、ROTATE_270=8）。
 */
object AnalysisScheduling {

    /** 默认并发数：两路并行已能把吞吐翻倍，同时对前端干扰可控。 */
    const val DEFAULT_PARALLELISM = 2
    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 4

    /** 并发数收敛到 [MIN_PARALLELISM]..[MAX_PARALLELISM]。 */
    fun coerceParallelism(raw: Int): Int = raw.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)

    /**
     * 按「块内最多 parallelism 张」切分待分析队列：
     * - 块内多张并发分析（ML Kit 检测器线程安全、结果互不干扰）；
     * - 块间串行做进度 / 通知 / 增量事件，前端仍只看到批处理节奏；
     * - 并发数 = 1 时每张一块，行为与 v3.11.0 的逐张串行完全一致（可回退）。
     */
    fun <T> chunksOf(items: List<T>, parallelism: Int): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(coerceParallelism(parallelism))
    }

    /** EXIF orientation → 顺时针旋转角；未定义 / 已正向返回 0。 */
    fun orientationToDegrees(orientation: Int): Float = when (orientation) {
        6 -> 90f    // ExifInterface.ORIENTATION_ROTATE_90
        3 -> 180f   // ExifInterface.ORIENTATION_ROTATE_180
        8 -> 270f   // ExifInterface.ORIENTATION_ROTATE_270
        else -> 0f
    }
}
