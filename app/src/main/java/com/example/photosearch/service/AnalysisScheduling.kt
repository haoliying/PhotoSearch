package com.example.photosearch.service

/**
 * v3.11.2：端侧分析调度的纯函数（无 Android 依赖，便于 tools/V313Eval.kt 离线评测）。
 *
 * 演进：
 *  - v3.11.0：全局单线程串行，1 万张照片哪怕每张 1 秒也要近 3 小时；
 *  - v3.11.1：「块内 N 张并发、块间串行收尾」，但**整块屏障**导致木桶效应：
 *    一块里最慢的那张决定整块耗时，其余线程空转，照片大小差异大时实际
 *    并行度远低于 N；并发上限也只有 4，默认 2，CPU 没吃满。
 *  - v3.11.2：改成**滑动窗口**——固定 N 个 worker，任何一张完成立刻补下一张，
 *    全程保持 N 张 in-flight，消除块屏障；并发上限提高到 8，默认 4。
 *
 * 本文件同时提供两种调度的「完工时间」纯模拟，用来在离线评测里定量证明
 * 滑动窗口优于块屏障（同一组耗时数据）。
 */
object AnalysisScheduling {

    /** 默认并发数：4 路能明显吃满中端机 CPU，同时对前台搜索保持低优先级隔离。 */
    const val DEFAULT_PARALLELISM = 4
    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 8

    /**
     * v3.11.2：端侧解码的最大边长。ML Kit 内部会把输入缩放到 224~320 再推理，
     * 1024px 位图对标签精度几乎没有收益，却要多花 2~4 倍解码时间与内存。
     * 降到 768 是速度与精度的折中；颜色统计只采样 500 像素，不受影响。
     */
    const val DECODE_MAX_PX = 768

    /** 并发数收敛到 [MIN_PARALLELISM]..[MAX_PARALLELISM]。 */
    fun coerceParallelism(raw: Int): Int = raw.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)

    /**
     * v3.11.1 保留：按「块内最多 parallelism 张」切分，供回退 / 兼容评测使用。
     * v3.11.2 的运行时改走滑动窗口，不再按块等待。
     */
    fun <T> chunksOf(items: List<T>, parallelism: Int): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(coerceParallelism(parallelism))
    }

    /**
     * 块屏障调度的总完工时间：每块 parallelism 张并行，块耗时 = 块内最大耗时，
     * 块间累加。慢照片会拖住同块的快照片，造成 CPU 空转。
     */
    fun batchedMakespan(costs: List<Long>, parallelism: Int): Long {
        if (costs.isEmpty()) return 0L
        val p = coerceParallelism(parallelism)
        var total = 0L
        for (chunk in costs.chunked(p)) total += chunk.maxOrNull() ?: 0L
        return total
    }

    /**
     * 滑动窗口调度的总完工时间：维护 p 个 worker 的「剩余时间」，
     * 每次取最快完成的 worker 接下一张，任何时刻都尽量保持 p 张 in-flight。
     * 返回值同时也用于验证「完成事件数 == 照片数、事件单调不减」。
     */
    fun slidingMakespan(costs: List<Long>, parallelism: Int): Long {
        if (costs.isEmpty()) return 0L
        val p = coerceParallelism(parallelism).coerceAtMost(costs.size)
        // 每个 worker 的可用时刻（初始 0）
        val freeAt = LongArray(p) { 0L }
        var makespan = 0L
        costs.forEach { cost ->
            // 选最早空闲的 worker；平局取下标小的，保证可复现
            var slot = 0
            for (i in 1 until p) if (freeAt[i] < freeAt[slot]) slot = i
            val start = freeAt[slot]
            val finish = start + cost
            freeAt[slot] = finish
            if (finish > makespan) makespan = finish
        }
        return makespan
    }

    /**
     * 滑动窗口的完工时刻序列（用于评测「进度单调」与「不丢不重」）。
     * 每个元素是一张照片完成的时刻，按完成顺序排列。
     */
    fun slidingTimeline(costs: List<Long>, parallelism: Int): List<Long> {
        if (costs.isEmpty()) return emptyList()
        val p = coerceParallelism(parallelism).coerceAtMost(costs.size)
        val freeAt = LongArray(p) { 0L }
        val out = ArrayList<Long>(costs.size)
        costs.forEach { cost ->
            var slot = 0
            for (i in 1 until p) if (freeAt[i] < freeAt[slot]) slot = i
            val finish = freeAt[slot] + cost
            freeAt[slot] = finish
            out += finish
        }
        return out.sorted()
    }

    /** EXIF orientation → 顺时针旋转角；未定义 / 已正向返回 0。 */
    fun orientationToDegrees(orientation: Int): Float = when (orientation) {
        6 -> 90f    // ExifInterface.ORIENTATION_ROTATE_90
        3 -> 180f   // ExifInterface.ORIENTATION_ROTATE_180
        8 -> 270f   // ExifInterface.ORIENTATION_ROTATE_270
        else -> 0f
    }
}
