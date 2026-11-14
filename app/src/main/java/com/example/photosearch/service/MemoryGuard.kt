package com.example.photosearch.service

/**
 * v3.11.3：大相册（2 万张以上）打开后「等一会儿就闪退」的防护逻辑。
 * 纯 Kotlin、无 Android / 协程依赖，供 tools/V314Eval.kt 离线评测。
 *
 * 背景（25000 张照片实测反馈）：v3.11.2 把并发提到 4 路并让守护持续后台分析后，
 * 打开 App 会自动拉起全量分析，随后：
 *  - 服务循环每轮 resultStore.load() 全量反序列化 25000 行 JSON；
 *  - 前端 absorbIncrement 每批（20 张 / 300ms）都做一次 25000 元素的全表复制，
 *    StateFlow 同时持有新旧两份大列表，Compose 的 items(key=id) 全量 diff；
 *  - Channel.UNLIMITED 无背压，结果通道会堆积完整 PhotoMetadata；
 *  - 4 路并发 Bitmap(768²·ARGB_8888) 与静态 analyzedMetadata + VM metadataCache 双份常驻。
 * 峰值堆突破上限后抛 OutOfMemoryError，而所有 catch 都是 catch(Exception)，
 * Error 直接冒泡、进程被杀 —— 表现就是「打开等一会儿就闪退」。
 *
 * 本模块把「按相册规模 / 可用堆做自适应限流」的决策集中成纯函数：
 *  1. [safeParallelism]：小堆 / 大相册自动降并发，压低 native Bitmap 峰值；
 *  2. [channelCapacity]：结果通道改有界，杜绝 UNLIMITED 堆积；
 *  3. [uiMergeIntervalMs] / [uiMergeBatchSize] / [shouldMergeUi]：
 *     大相册把前端全表合并从「每 300ms 一次」降到「每 1.5s 或 200 张一次」，
 *     分配量降低一个数量级；元数据与文档缓存仍即时 O(k) 更新，不影响搜索；
 *  4. [shouldUpdateNotification]：通知 Binder 降频，避免高频 TransactionTooLarge；
 *  5. [degradedParallelism]：捕获 OOM 后在线降级而不是崩溃；
 *  6. [estimatePeakKb] / [simulateUiMerges]：定量模型，评测用来断言预算收敛。
 */
object MemoryGuard {

    /** 超过该规模启用「大相册保护」：合并节流、降并发、有界通道。 */
    const val LARGE_LIBRARY_THRESHOLD = 8000

    const val DEFAULT_UI_MERGE_INTERVAL_MS = 300L
    const val LARGE_UI_MERGE_INTERVAL_MS = 1500L
    const val DEFAULT_UI_MERGE_BATCH = 20
    const val LARGE_UI_MERGE_BATCH = 200

    /** 通知栏最小刷新间隔；进度 StateFlow 仍按 200ms 更新，UI 不受影响。 */
    const val NOTIFICATION_MIN_INTERVAL_MS = 900L

    /** 服务的增量批大小（与 AnalysisService.RESULT_BATCH_MAX 对齐）。 */
    const val SERVICE_BATCH_SIZE = 20

    fun isLargeLibrary(photoCount: Int): Boolean = photoCount >= LARGE_LIBRARY_THRESHOLD

    /**
     * 依据「用户配置并发数 + 相册规模 + 应用可用堆」给出实际并发数。
     * 大相册 / 小堆时主动降并发，减少同时在途的 Bitmap(768²·4B≈2.25MB) 数量。
     */
    fun safeParallelism(requested: Int, photoCount: Int, heapMb: Int): Int {
        val p = requested.coerceIn(1, 8)
        return when {
            heapMb < 128 -> minOf(p, 1)
            heapMb < 256 -> minOf(p, if (isLargeLibrary(photoCount)) 2 else 3)
            else -> if (isLargeLibrary(photoCount)) minOf(p, 4) else p
        }
    }

    /**
     * 结果 / 任务通道容量：与并发挂钩的有界值，形成背压。
     * 不再使用 Channel.UNLIMITED —— 后者会让 25000 张的 id 或结果一次性堆进内存。
     */
    fun channelCapacity(parallelism: Int): Int = (parallelism * 4).coerceIn(4, 32)

    fun uiMergeIntervalMs(photoCount: Int): Long =
        if (isLargeLibrary(photoCount)) LARGE_UI_MERGE_INTERVAL_MS else DEFAULT_UI_MERGE_INTERVAL_MS

    fun uiMergeBatchSize(photoCount: Int): Int =
        if (isLargeLibrary(photoCount)) LARGE_UI_MERGE_BATCH else DEFAULT_UI_MERGE_BATCH

    /** 是否该把累积的增量结果合并进 UI 列表（全表复制只在此时发生一次）。 */
    fun shouldMergeUi(buffered: Int, elapsedMs: Long, photoCount: Int): Boolean {
        if (buffered <= 0) return false
        return buffered >= uiMergeBatchSize(photoCount) ||
                elapsedMs >= uiMergeIntervalMs(photoCount)
    }

    fun shouldUpdateNotification(elapsedMs: Long): Boolean =
        elapsedMs >= NOTIFICATION_MIN_INTERVAL_MS

    /** 捕获 OOM 后在线降一级并发，永远不低于 1（保持可用，不崩溃）。 */
    fun degradedParallelism(current: Int): Int = (current - 1).coerceAtLeast(1)

    /**
     * v3.11.3：磁盘全量 JSON 反序列化只在循环首轮做一次，之后复用进程内
     * analyzedMetadata 镜像。旧版每轮对账都重新解析 25000 行，是大相册 OOM 主因。
     */
    fun shouldReloadFromDisk(round: Int): Boolean = round == 0

    /** 分析由「运行中」转为「停止」（完成 / 暂停 / 中断）时，强制合并残余增量。 */
    fun shouldFlushUi(wasRunning: Boolean, isRunning: Boolean): Boolean =
        wasRunning && !isRunning

    /**
     * 峰值内存粗估（单位 KB）：元数据 + UI 列表快照 + 在途 Bitmap + 通道内结果。
     * 用于评测断言「大相册启用保护后预算显著下降」，不作为运行时精确计量。
     */
    fun estimatePeakKb(photoCount: Int, parallelism: Int, channelCapacity: Int): Long {
        val metadata = photoCount * 2L                              // 每条 PhotoMetadata ≈ 2KB
        val uiSnapshots = photoCount * 16L / 1024                   // 新旧两份列表引用数组
        val bitmaps = parallelism.toLong() * 768L * 768L * 4L / 1024L // ARGB_8888
        val inflight = channelCapacity * 2L                         // 通道内结果
        return metadata + uiSnapshots + bitmaps + inflight
    }

    /**
     * 模拟一次全量分析期间前端「全表合并」的次数。
     * 服务每 SERVICE_BATCH_SIZE 张发一批，前端累积到 [uiMergeBatchSize] 才合并一次。
     * 大相册下次数应远小于「每批都合并」（= photoCount / SERVICE_BATCH_SIZE）。
     */
    fun simulateUiMerges(photoCount: Int): Int {
        if (photoCount <= 0) return 0
        val per = uiMergeBatchSize(photoCount)
        // 每个合并帧覆盖 per 张结果；服务批大小不会超过 per（大相册 200 > 20）
        return (photoCount + per - 1) / per
    }

    /** 未启用保护时的合并次数：每批（20 张）都复制一次全表。 */
    fun simulateUnprotectedMerges(photoCount: Int): Int =
        if (photoCount <= 0) 0 else (photoCount + SERVICE_BATCH_SIZE - 1) / SERVICE_BATCH_SIZE
}
