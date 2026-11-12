import com.example.photosearch.service.AnalysisScheduling
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v3.11.1 离线评测：端侧分析提速（块内并发 + 单 fd 复用的调度纯函数）。
 *
 * 背景：v3.11.0 的批量分析是全局单线程串行，用户反馈「不用大模型时非常慢」。
 * 本评测验证提速改造中**可离线验证**的核心逻辑：
 *  A. 并发数收敛（1..4，默认 2）；
 *  B. 分块策略：块内最多 N 张、不丢不重、并发 1 等价旧版逐张串行；
 *  C. EXIF 方向常量 → 旋转角（与 ExifInterface 数值对应，无 androidx 依赖）；
 *  D. 并发调度模拟（线程池与 AnalysisService.runPlan 同构）：
 *     每张恰好分析一次、完成即落盘、乱序完成不丢结果、块间按序收尾；
 *  E. 真并发验证：2 路任务确实能同时运行；
 *  F. 取消语义：已完成的照片结果保留、被取消的不写入（磁盘是唯一权威）；
 *  G. 分块为 O(n)，10 万张照片无性能退化。
 *
 * 编译与运行（与历史评测同一套 K2JVMCompiler + kotlin-stdlib，Kotlin 1.9.22）：
 *   java -cp kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 *     -no-stdlib -no-reflect -cp kotlin-stdlib.jar -d out \
 *     AnalysisScheduling.kt V312Eval.kt
 *   java -cp out:kotlin-stdlib.jar V312EvalKt
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

    section("A. 并发数收敛")
    check("A1 1..4 原样返回", listOf(1, 2, 3, 4).all { A.coerceParallelism(it) == it })
    // v3.11.2 把并发上限由 4 提到 8、默认由 2 提到 4（滑动窗口取代块屏障）。
    // 分块纯函数的语义未变，这里改为引用常量而非硬编码，保持历史评测可复跑。
    check(
        "A2 越界收敛到边界",
        A.coerceParallelism(0) == 1 && A.coerceParallelism(-7) == 1 &&
                A.coerceParallelism(5) == 5 &&
                A.coerceParallelism(100) == A.MAX_PARALLELISM &&
                A.coerceParallelism(Int.MIN_VALUE) == 1 &&
                A.coerceParallelism(Int.MAX_VALUE) == A.MAX_PARALLELISM
    )
    check(
        "A3 默认与上界（v3.11.2 上调）",
        A.DEFAULT_PARALLELISM == 4 && A.MIN_PARALLELISM == 1 && A.MAX_PARALLELISM == 8 &&
                A.coerceParallelism(A.DEFAULT_PARALLELISM) == A.DEFAULT_PARALLELISM
    )

    section("B. 分块策略")
    val ids = (1L..10L).toList()
    val c3 = A.chunksOf(ids, 3)
    check("B1 块大小不超过并发数", c3.all { it.size <= 3 })
    check("B2 不丢不重（拼接等价原队列）", c3.flatten() == ids)
    check("B3 10 张 / 3 路 → [3,3,3,1]", c3.map { it.size } == listOf(3, 3, 3, 1))
    check(
        "B4 并发 1 → 每张一块（等价 v3.11.0 逐张串行）",
        A.chunksOf(ids, 1).size == 10 && A.chunksOf(ids, 1).all { it.size == 1 }
    )
    check("B5 空列表 → 无块", A.chunksOf(emptyList<Long>(), 4).isEmpty())
    check("B6 张数少于并发数 → 单块", A.chunksOf(listOf(1L, 2L), 4).size == 1)
    check("B7 非法并发数按收敛处理",
        A.chunksOf(ids, 0).all { it.size <= 1 } &&
                A.chunksOf(ids, 99).all { it.size <= A.MAX_PARALLELISM })

    section("C. EXIF 方向映射")
    check("C1 ROTATE_90(6) → 90°", A.orientationToDegrees(6) == 90f)
    check("C2 ROTATE_180(3) → 180°", A.orientationToDegrees(3) == 180f)
    check("C3 ROTATE_270(8) → 270°", A.orientationToDegrees(8) == 270f)
    check(
        "C4 正向/未定义/翻转 → 0°",
        intArrayOf(1, 0, 2, 4, 5, 7).all { A.orientationToDegrees(it) == 0f }
    )

    section("D. 并发调度模拟（与 runPlan 同构：块内并发 + 完成即落盘）")
    val bigIds = (1L..200L).toList()
    val executed = Collections.synchronizedList(mutableListOf<Long>())
    val persisted = Collections.synchronizedList(mutableListOf<Long>())
    val chunkOrder = Collections.synchronizedList(mutableListOf<Long>())
    val rnd = java.util.Random(42)
    val pool = Executors.newFixedThreadPool(3)
    val chunks = A.chunksOf(bigIds, 3)
    try {
        for (chunk in chunks) {
            val futures = chunk.map { id ->
                pool.submit {
                    executed.add(id)
                    Thread.sleep(rnd.nextInt(12).toLong())   // 乱序完成
                    persisted.add(id)                          // 与 appendResult 同契约
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }   // 块间串行收尾
            chunkOrder.add(chunk.first())
        }
    } finally {
        pool.shutdown()
    }
    check(
        "D1 每张恰好分析一次",
        executed.size == 200 && executed.toSet() == bigIds.toSet(),
        "executed=${executed.size}"
    )
    check(
        "D2 完成即落盘、乱序不丢不重",
        persisted.sorted() == bigIds,
        "persisted=${persisted.size}"
    )
    check(
        "D3 块间按分块顺序收尾（进度/事件仍是批处理节奏）",
        chunkOrder == chunks.map { it.first() },
        "块序 ${chunkOrder.take(3)}..."
    )

    section("E. 真并发验证")
    val pool2 = Executors.newFixedThreadPool(2)
    val barrier = CyclicBarrier(2)
    try {
        // 注意：submit{} 的 lambda 解析为 Runnable（返回值不生效），
        // 这里以「两个任务都在超时内无异常完成」作为汇合成功判据——
        // 若不能并发，两个 barrier.await(3s) 会在超时后以异常告终。
        val f1 = pool2.submit { barrier.await(3, TimeUnit.SECONDS) }
        val f2 = pool2.submit { barrier.await(3, TimeUnit.SECONDS) }
        val met = try {
            f1.get(5, TimeUnit.SECONDS)
            f2.get(5, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }
        check("E1 2 路任务可同时运行（barrier 成功汇合）", met)
    } finally {
        pool2.shutdown()
    }

    section("F. 取消语义：已完成即落盘、被取消的不写入")
    val pool3 = Executors.newFixedThreadPool(2)
    val persistedF = Collections.synchronizedList(mutableListOf<Int>())
    try {
        val f1 = pool3.submit {
            Thread.sleep(50)
            persistedF.add(1)
        }
        val f2 = pool3.submit {
            Thread.sleep(8000)
            persistedF.add(2)
        }
        f1.get(3, TimeUnit.SECONDS)   // 第一张完成且已落盘
        f2.cancel(true)               // 模拟协程取消
        Thread.sleep(150)
        check("F1 已完成的照片结果保留（磁盘唯一权威）", 1 in persistedF)
        check("F2 被取消的照片不写入", 2 !in persistedF)
    } finally {
        pool3.shutdownNow()
    }

    section("G. 性能")
    val bigList = (1L..100_000L).toList()
    val t0 = System.currentTimeMillis()
    repeat(10) { A.chunksOf(bigList, 4) }
    val dt = System.currentTimeMillis() - t0
    check("G1 10 万张分块 ×10 次 < 500ms（O(n)）", dt < 500, "实际 ${dt}ms")

    println()
    println("---- V312Eval: $passed / $total passed ----")
    if (passed != total) kotlin.system.exitProcess(1)
}
