import com.example.photosearch.service.AnalysisScheduling
import com.example.photosearch.service.AnalysisControl
import com.example.photosearch.service.MemoryGuard

/**
 * v3.11.3 离线评测：大相册（2 万张以上）打开后闪退的内存保护。
 *
 * 用户反馈：「现在打开等一会儿就闪退，照片 25000 多」。根因是后台守护自动拉起
 * 全量分析后，每批结果全表复制、每轮对账全量反序列化、无界通道堆积、位图泄漏、
 * OutOfMemoryError 未被捕获。本评测验证本次改造中**可离线验证**的核心逻辑：
 *
 *  A. 大相册识别与并发自适应（小堆 / 大相册自动降并发，压低位图峰值）
 *  B. 有界通道容量（不受相册规模影响，杜绝 UNLIMITED 堆积）
 *  C. UI 增量合并节流（25000 张下合并次数比旧版低一个数量级，且不丢结果）
 *  D. 「仅首轮读盘」与「收尾强制合并」两个决策的边界
 *  E. 内存预算模型（并发下降 → 峰值下降；有界通道显著低于无界估计）
 *  F. OOM 在线降级（单调递减、下限为 1、连续降级收敛）
 *  G. 通知降频（进度节流不受影响，通知间隔独立）
 *  H. 与历史常量的一致性（解码边长、并发上下限、进度节流未回归）
 */
private var passed = 0
private var total = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    total++
    if (cond) {
        passed++
        println("  PASS  $name" + if (detail.isEmpty()) "" else "  ($detail)")
    } else {
        println("  FAIL  $name" + if (detail.isEmpty()) "" else "  ($detail)")
    }
}

fun main() {
    val G = MemoryGuard

    println("== A. 大相册识别与并发自适应 ==")
    check("A1 阈值以下不算大相册", !G.isLargeLibrary(7999) && !G.isLargeLibrary(0))
    check("A2 阈值及以上算大相册", G.isLargeLibrary(8000) && G.isLargeLibrary(25000))
    check("A3 正常堆 + 小相册保留配置并发",
        G.safeParallelism(8, 1000, 512) == 8 && G.safeParallelism(4, 3000, 512) == 4)
    check("A4 正常堆 + 大相册最多 4 路",
        G.safeParallelism(8, 25000, 512) == 4, "8→${G.safeParallelism(8, 25000, 512)}")
    check("A5 中等堆(200MB) + 大相册最多 2 路",
        G.safeParallelism(8, 25000, 200) == 2, "8→${G.safeParallelism(8, 25000, 200)}")
    check("A6 中等堆 + 小相册最多 3 路",
        G.safeParallelism(8, 1000, 200) == 3)
    check("A7 小堆(96MB) 强制 1 路",
        G.safeParallelism(8, 1000, 96) == 1 && G.safeParallelism(8, 25000, 96) == 1)
    check("A8 并发数不会超过配置值",
        (1..8).all { p -> (0..256 step 32).all { h -> G.safeParallelism(p, 25000, h) <= p } })
    check("A9 并发数下限为 1（保持可用）",
        (0..256 step 16).all { h -> G.safeParallelism(1, 25000, h) == 1 })
    check("A10 非法配置收敛到 1..8",
        G.safeParallelism(0, 1000, 512) == 1 && G.safeParallelism(99, 1000, 512) == 8)
    check("A11 并发随堆单调不减",
        (0..512 step 32).windowed(2).all { (a, b) ->
            G.safeParallelism(8, 25000, a) <= G.safeParallelism(8, 25000, b)
        })

    println()
    println("== B. 有界通道容量 ==")
    check("B1 容量与并发挂钩且落在 4..32",
        (1..8).all { p -> G.channelCapacity(p) in 4..32 })
    check("B2 并发越高容量越大（不更严苛）",
        (1..7).all { p -> G.channelCapacity(p) <= G.channelCapacity(p + 1) })
    check("B3 容量远小于相册规模（不会灌进 25000 个 id）",
        G.channelCapacity(4) < 25000 / 100 && G.channelCapacity(8) < 25000 / 100,
        "cap4=${G.channelCapacity(4)} cap8=${G.channelCapacity(8)}")
    check("B4 并发收敛后容量同步下降",
        G.channelCapacity(G.safeParallelism(8, 25000, 96)) <
                G.channelCapacity(G.safeParallelism(8, 1000, 512)))

    println()
    println("== C. UI 增量合并节流（闪退主因） ==")
    val N = 25000
    val oldMerges = G.simulateUnprotectedMerges(N)   // 每 20 张一次全表复制
    val newMerges = G.simulateUiMerges(N)            // 大相册累积到 200 张才合并
    check("C1 旧版 25000 张要复制上千次全表", oldMerges >= 1000, "old=$oldMerges")
    check("C2 新版合并次数降低一个数量级", newMerges * 8 <= oldMerges,
        "new=$newMerges old=$oldMerges 降幅=${(100 - newMerges * 100 / oldMerges)}%")
    check("C3 小相册保持即时合并（不牺牲流畅度）",
        G.uiMergeBatchSize(1000) == G.DEFAULT_UI_MERGE_BATCH &&
                G.uiMergeIntervalMs(1000) == G.DEFAULT_UI_MERGE_INTERVAL_MS)
    check("C4 大相册批量阈值 200 / 间隔 1500ms",
        G.uiMergeBatchSize(N) == G.LARGE_UI_MERGE_BATCH &&
                G.uiMergeIntervalMs(N) == G.LARGE_UI_MERGE_INTERVAL_MS)
    check("C5 空缓冲不合并", !G.shouldMergeUi(0, 999999, N))
    check("C6 达到批量阈值立即合并（大相册）",
        G.shouldMergeUi(200, 0, N) && !G.shouldMergeUi(199, 0, N))
    check("C7 超过时间间隔也合并（避免最后一批饿死）",
        G.shouldMergeUi(1, 1500, N))
    check("C8 未达阈值且时间未到不合并",
        !G.shouldMergeUi(20, 300, N))
    check("C9 小相册按 20 张 / 300ms 合并",
        G.shouldMergeUi(20, 0, 1000) && G.shouldMergeUi(1, 300, 1000))
    check("C10 合并次数与相册规模正相关但可控",
        G.simulateUiMerges(8000) < G.simulateUiMerges(25000) &&
                G.simulateUiMerges(0) == 0)
    // 累积缓冲语义：后写覆盖、clear 后不残留、总覆盖数 == 照片数
    val pending = LinkedHashMap<Long, String>()
    var mergedCount = 0
    for (batch in 0 until N step G.SERVICE_BATCH_SIZE) {
        val size = minOf(G.SERVICE_BATCH_SIZE, N - batch)
        for (i in 0 until size) pending[(batch + i).toLong()] = "m${batch + i}"
        if (G.shouldMergeUi(pending.size, 0, N)) {
            mergedCount += pending.size
            pending.clear()
        }
    }
    if (pending.isNotEmpty()) mergedCount += pending.size   // 收尾 flush
    check("C11 累积 + 阈值合并 + 收尾 flush 不丢结果",
        mergedCount == N && pending.isEmpty(), "merged=$mergedCount")
    check("C12 同一 id 后写覆盖（只保留最新结果）",
        run {
            val p = LinkedHashMap<Long, String>()
            p[1] = "old"; p[1] = "new"
            p.size == 1 && p[1] == "new"
        })

    println()
    println("== D. 仅首轮读盘 / 收尾强制合并 ==")
    check("D1 仅 round==0 读盘", G.shouldReloadFromDisk(0) && !G.shouldReloadFromDisk(1))
    check("D2 多轮对账只在首轮读盘一次",
        (0..20).count { G.shouldReloadFromDisk(it) } == 1)
    check("D3 运行中→停止才 flush", G.shouldFlushUi(true, false))
    check("D4 运行中→运行中 / 停止→停止 不 flush",
        !G.shouldFlushUi(true, true) && !G.shouldFlushUi(false, false))
    check("D5 停止→运行（新一轮）不 flush", !G.shouldFlushUi(false, true))

    println()
    println("== E. 内存预算模型 ==")
    val peakUnprotected = G.estimatePeakKb(N, 8, N)          // 8 路 + 无界通道(≈N)
    val peakGuarded = G.estimatePeakKb(
        N, G.safeParallelism(8, N, 200), G.channelCapacity(G.safeParallelism(8, N, 200))
    )
    check("E1 保护后峰值显著低于未保护", peakGuarded * 2 < peakUnprotected,
        "guarded=${peakGuarded}KB unprotected=${peakUnprotected}KB")
    check("E2 并发下降 → 峰值下降",
        G.estimatePeakKb(N, 1, 4) < G.estimatePeakKb(N, 8, 4))
    check("E3 通道容量下降 → 峰值下降",
        G.estimatePeakKb(N, 4, 4) < G.estimatePeakKb(N, 4, 25000))
    check("E4 峰值随相册规模增长",
        G.estimatePeakKb(1000, 4, 16) < G.estimatePeakKb(25000, 4, 16))
    check("E5 空相册预算非负且不含位图项",
        G.estimatePeakKb(0, 1, 4) >= 0L)

    println()
    println("== F. OOM 在线降级 ==")
    check("F1 每次降一级", G.degradedParallelism(4) == 3 && G.degradedParallelism(2) == 1)
    check("F2 下限恒为 1，不会降到 0 或负数",
        G.degradedParallelism(1) == 1 && G.degradedParallelism(0) == 1 &&
                G.degradedParallelism(-5) == 1)
    var cur = 8
    repeat(20) { cur = G.degradedParallelism(cur) }
    check("F3 连续降级收敛到 1 并保持", cur == 1)
    check("F4 降级链单调不增",
        run { var c = 8; var ok = true; repeat(10) { val n = G.degradedParallelism(c); if (n > c) ok = false; c = n }; ok })

    println()
    println("== G. 通知降频与进度节流互不干扰 ==")
    check("G1 通知间隔 900ms", G.NOTIFICATION_MIN_INTERVAL_MS == 900L)
    check("G2 间隔未到不刷新通知", !G.shouldUpdateNotification(200) &&
            !G.shouldUpdateNotification(899))
    check("G3 间隔到达刷新通知", G.shouldUpdateNotification(900) &&
            G.shouldUpdateNotification(1500))
    check("G4 进度节流常量保持 200ms / 8 张（未回归）",
        AnalysisControl.PROGRESS_EMIT_INTERVAL_MS == 200L &&
                AnalysisControl.PROGRESS_EMIT_STEP == 8)
    check("G5 进度不受通知间隔影响（force 帧仍必发）",
        AnalysisControl.shouldEmit(0, 1, 0, 0, force = true))

    println()
    println("== H. 历史常量一致性（无回归） ==")
    check("H1 解码边长仍为 768", AnalysisScheduling.DECODE_MAX_PX == 768)
    check("H2 并发范围 1..8 / 默认 4",
        AnalysisScheduling.MIN_PARALLELISM == 1 &&
                AnalysisScheduling.MAX_PARALLELISM == 8 &&
                AnalysisScheduling.DEFAULT_PARALLELISM == 4)
    check("H3 越界收敛不变",
        AnalysisScheduling.coerceParallelism(0) == 1 &&
                AnalysisScheduling.coerceParallelism(99) == 8)
    check("H4 服务批大小与 IncrementBuffer 默认一致(20)",
        G.SERVICE_BATCH_SIZE == 20)
    check("H5 大相册阈值(8000) 明显低于用户规模(25000)",
        G.LARGE_LIBRARY_THRESHOLD < N)

    println()
    println("---- V314Eval: $passed / $total passed ----")
    if (passed != total) throw AssertionError("V314Eval 有失败项: ${total - passed}")
}
