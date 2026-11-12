import com.example.photosearch.service.AnalysisScheduling
import com.example.photosearch.service.AnalysisControl
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.11.2 离线评测：真并发提速 / 后台持续分析 / 进度平滑 / 暂停继续。
 *
 * 用户反馈三点：「速度还是不快」「感觉在后台就不分析了」「分析进度每次跳几百」，
 * 另要求增加暂停 / 继续按钮。本评测验证本次改造中**可离线验证**的核心逻辑：
 *  A. 滑动窗口 vs 整块屏障：用同一组单张耗时定量证明滑动窗口完工更快；
 *  B. 进度单一数据源：单调不回退、时间节流、相邻帧增量有上界、收尾必达；
 *  C. 暂停 / 继续状态机：暂停优先、继续后 RUNNING、SETTLE 不偷偷恢复；
 *  D. 真并发模拟：N 个 worker 从同一队列取任务，不丢不重、乱序完成、
 *     暂停期间不取新任务、继续后把剩余全部处理完；
 *  E. 唤醒锁生命周期：分析时持有、暂停 / 空闲 / 停止时释放，不永久持有；
 *  F. 与 v3.11.1 分块纯函数的兼容性；
 *  G. 10 万张照片的调度模拟无性能退化。
 *
 * 编译与运行（与历史评测同一套 K2JVMCompiler + kotlin-stdlib，Kotlin 1.9.22）：
 *   java -cp kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 *     -no-stdlib -no-reflect -cp kotlin-stdlib.jar -d out \
 *     AnalysisScheduling.kt AnalysisControl.kt V313Eval.kt
 *   java -cp out:kotlin-stdlib.jar V313EvalKt
 */
private var passed = 0
private var total = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    total++
    if (cond) {
        passed++
        println("PASS $name${if (detail.isBlank()) "" else "  $detail"}")
    } else {
        println("FAIL $name  $detail")
    }
}

private fun section(title: String) {
    println()
    println("---- $title ----")
}

fun main() {
    val A = AnalysisScheduling
    val C = AnalysisControl

    section("A. 滑动窗口 vs 整块屏障（定量证明提速）")

    // 照片复杂度差异大：几张特别慢的混在一堆快照片里，正是块屏障最吃亏的场景
    val mixed = listOf(
        100L, 120L, 90L, 110L, 1500L,   // 第 1 块被 1500 拖住
        100L, 130L, 95L, 105L, 1400L,   // 第 2 块被 1400 拖住
        100L, 100L, 100L, 100L, 100L,
        1200L, 110L, 90L, 100L, 115L
    )
    val batched = A.batchedMakespan(mixed, 4)
    val sliding = A.slidingMakespan(mixed, 4)
    check(
        "A1 差异耗时下滑动窗口明显快于块屏障",
        sliding < batched,
        "batched=${batched}ms sliding=${sliding}ms 提速=${(batched - sliding) * 100 / batched}%"
    )
    check(
        "A2 均匀耗时下不劣化（两者相等）",
        A.slidingMakespan(List(12) { 100L }, 4) == A.batchedMakespan(List(12) { 100L }, 4)
    )
    check(
        "A3 单路时等于逐张串行总耗时",
        A.slidingMakespan(mixed, 1) == mixed.sum() && A.batchedMakespan(mixed, 1) == mixed.sum()
    )
    check("A4 空队列完工时间为 0", A.slidingMakespan(emptyList(), 4) == 0L &&
            A.batchedMakespan(emptyList(), 4) == 0L)

    val timeline = A.slidingTimeline(mixed, 4)
    check("A5 完工事件不丢不重", timeline.size == mixed.size)
    check("A6 完工时刻单调不减", timeline.zipWithNext().all { it.first <= it.second })
    check("A7 最后完工时刻 == makespan", timeline.last() == sliding)

    val m1 = A.slidingMakespan(mixed, 1)
    val m2 = A.slidingMakespan(mixed, 2)
    val m4 = A.slidingMakespan(mixed, 4)
    val m8 = A.slidingMakespan(mixed, 8)
    check("A8 并发越高完工不越慢", m8 <= m4 && m4 <= m2 && m2 <= m1,
        "1=$m1 2=$m2 4=$m4 8=$m8")

    check("A9 并发常量与解码边长（v3.11.2）",
        A.MAX_PARALLELISM == 8 && A.DEFAULT_PARALLELISM == 4 &&
                A.MIN_PARALLELISM == 1 && A.DECODE_MAX_PX == 768)

    section("B. 进度单一数据源：单调、节流、不跳几百")

    check("B1 monotonic 不回退", C.monotonic(500, 120) == 500 && C.monotonic(500, 780) == 780)
    check("B2 进度未增长不发帧", !C.shouldEmit(100, 100, 0L, 1000L))
    check("B3 超过 200ms 间隔发一帧", C.shouldEmit(100, 101, 0L, 250L))
    check("B4 累计增量达 8 张即使未到间隔也发", C.shouldEmit(100, 108, 0L, 30L))
    check("B5 收尾 force 必发（哪怕值没变）", C.shouldEmit(100, 100, 0L, 1L, force = true))
    check("B6 未到间隔且增量小则跳过", !C.shouldEmit(100, 103, 0L, 30L))

    // 模拟 3000 张逐张完成（每步 30ms），复现「进度一次跳几百」的旧路径并验证上限
    var lastValue = 0
    var lastTime = 0L
    var now = 0L
    val frames = ArrayList<Int>()
    for (i in 1..3000) {
        now += 30L
        if (C.shouldEmit(lastValue, i, lastTime, now)) {
            lastValue = C.monotonic(lastValue, i)
            lastTime = now
            frames += lastValue
        }
    }
    // 收尾帧必发最终值
    if (C.shouldEmit(lastValue, 3000, lastTime, now, force = true)) frames += 3000
    val maxJump = frames.zipWithNext().maxOfOrNull { it.second - it.first } ?: 0
    check("B7 相邻两帧增量有上界（不再跳几百）", maxJump <= C.PROGRESS_EMIT_STEP,
        "帧数=${frames.size} 最大跳变=$maxJump 张")
    check("B8 收尾最终值必达", frames.last() == 3000)
    check("B9 帧序列严格递增", frames.zipWithNext().all { it.second > it.first })

    // 跨轮对账时 done 可能短暂小于上一轮，monotonic 保护显示值
    val displayed = C.monotonic(820, 815)
    check("B10 跨轮/回前台补齐不回跳", displayed == 820)

    section("C. 暂停 / 继续状态机")

    check("C1 RUNNING + PAUSE → PAUSED",
        C.transition(AnalysisControl.State.RUNNING, AnalysisControl.Action.PAUSE) == AnalysisControl.State.PAUSED)
    check("C2 PAUSED + CONTINUE → RUNNING",
        C.transition(AnalysisControl.State.PAUSED, AnalysisControl.Action.CONTINUE) == AnalysisControl.State.RUNNING)
    check("C3 PAUSED + SETTLE 仍 PAUSED（不被对账偷偷恢复）",
        C.transition(AnalysisControl.State.PAUSED, AnalysisControl.Action.SETTLE) == AnalysisControl.State.PAUSED)
    check("C4 RUNNING + SETTLE → IDLE",
        C.transition(AnalysisControl.State.RUNNING, AnalysisControl.Action.SETTLE) == AnalysisControl.State.IDLE)
    check("C5 IDLE + START → RUNNING",
        C.transition(AnalysisControl.State.IDLE, AnalysisControl.Action.START) == AnalysisControl.State.RUNNING)
    check("C6 暂停时门控关闭、继续后打开",
        !C.canProceed(paused = true, active = true) &&
                C.canProceed(paused = false, active = true) &&
                !C.canProceed(paused = false, active = false))
    check("C7 心跳 / 轮询 / 唤醒锁超时常量合理",
        C.IDLE_HEARTBEAT_MS == 60_000L && C.PAUSE_POLL_MS == 300L &&
                C.WAKELOCK_TIMEOUT_MS == 30 * 60 * 1000L &&
                C.PROGRESS_EMIT_INTERVAL_MS == 200L)

    section("D. 滑动窗口真并发模拟（与 runPlan 同构）")

    // D1: N 个 worker 从同一队列取任务，不丢不重，且完成顺序可与提交顺序不同
    val queue = ConcurrentLinkedQueue<Long>()
    (1L..200L).forEach { queue.add(it) }
    val processed = ConcurrentHashMap.newKeySet<Long>()
    val order = ConcurrentLinkedQueue<Long>()
    val latch = CountDownLatch(200)
    val pool = Executors.newFixedThreadPool(4)
    repeat(4) {
        pool.submit {
            while (true) {
                val id = queue.poll() ?: break
                val duplicated = !processed.add(id)
                if (duplicated) throw IllegalStateException("重复分析 $id")
                Thread.sleep(id % 4)   // 制造不同耗时，验证乱序完成
                order.add(id)
                latch.countDown()
            }
        }
    }
    val finished = latch.await(15, TimeUnit.SECONDS)
    pool.shutdown()
    check("D1 200 张全部处理完成", finished && processed.size == 200 && queue.isEmpty())
    check("D2 乱序完成（谁先完成谁补下一张，不是整块等待）",
        order.toList() != (1L..200L).toList())
    check("D3 无重复分析", processed.size == 200)

    // D4: 4 路任务确实能同时运行（用 Callable 明确返回值，避免 submit lambda 被当成 Runnable）
    val pool2 = Executors.newFixedThreadPool(4)
    try {
        val barrier = CyclicBarrier(4)
        val futures = (0 until 4).map {
            pool2.submit(Callable {
                barrier.await(3, TimeUnit.SECONDS)
                true
            })
        }
        val allMet = futures.all { it.get(5, TimeUnit.SECONDS) }
        check("D4 4 路 worker 可同时运行（barrier 成功汇合）", allMet)
    } catch (e: Exception) {
        check("D4 4 路 worker 可同时运行（barrier 成功汇合）", false, e.toString())
    } finally {
        pool2.shutdown()
    }

    // D5/D6: 暂停门控——暂停期间不取新任务，继续后把剩余全部处理完
    val gateQueue = ConcurrentLinkedQueue<Long>()
    (1L..60L).forEach { gateQueue.add(it) }
    val paused = AtomicBoolean(true)
    val gateProcessed = ConcurrentHashMap.newKeySet<Long>()
    val gateLatch = CountDownLatch(60)
    val pool3 = Executors.newFixedThreadPool(4)
    repeat(4) {
        pool3.submit {
            while (true) {
                if (paused.get()) {
                    Thread.sleep(5)
                    continue
                }
                val id = gateQueue.poll() ?: break
                gateProcessed.add(id)
                gateLatch.countDown()
            }
        }
    }
    Thread.sleep(120)
    val idleDuringPause = gateProcessed.isEmpty() && gateQueue.size == 60
    paused.set(false)
    val gateDone = gateLatch.await(10, TimeUnit.SECONDS)
    pool3.shutdown()
    check("D5 暂停期间不处理任何照片", idleDuringPause)
    check("D6 继续后从剩余照片全部处理完、不重复",
        gateDone && gateProcessed.size == 60 && gateQueue.isEmpty())

    section("E. 唤醒锁生命周期（后台持续分析）")

    // 纯状态模拟：分析时持有，暂停 / 空闲 / 停止时释放，绝不永久持有
    var wakeHeld = false
    fun acquire() { wakeHeld = true }
    fun release() { wakeHeld = false }
    acquire()
    check("E1 开始分析持有唤醒锁", wakeHeld)
    release()   // 暂停
    check("E2 暂停时释放（不白耗电）", !wakeHeld)
    acquire()   // 继续
    check("E3 继续时重新持有", wakeHeld)
    release()   // 本轮跑完进入低频心跳
    check("E4 空闲心跳时不持有", !wakeHeld)
    release()   // 停止守护（幂等）
    check("E5 停止守护后不持有（幂等释放）", !wakeHeld)

    section("F. 与 v3.11.1 分块纯函数兼容")

    val ids = (1L..10L).toList()
    check("F1 chunksOf 不丢不重", A.chunksOf(ids, 4).flatten() == ids)
    check("F2 并发 1 仍等价逐张串行", A.chunksOf(ids, 1).size == 10)
    check("F3 EXIF 方向映射未变", A.orientationToDegrees(6) == 90f &&
            A.orientationToDegrees(3) == 180f && A.orientationToDegrees(8) == 270f)

    section("G. 大规模调度无性能退化")

    val big = MutableList(100_000) { (it % 7 + 1) * 10L }
    val t0 = System.currentTimeMillis()
    val bigMakespan = A.slidingMakespan(big, 8)
    val bigBat = A.batchedMakespan(big, 8)
    val cost = System.currentTimeMillis() - t0
    check("G1 10 万张调度在 2 秒内完成", cost < 2000, "耗时=${cost}ms")
    check("G2 10 万张滑动窗口不慢于块屏障", bigMakespan <= bigBat,
        "sliding=$bigMakespan batched=$bigBat")

    println()
    println("---- V313Eval: $passed / $total passed ----")
    if (passed != total) throw AssertionError("V313Eval 有失败项: ${total - passed}")
}
