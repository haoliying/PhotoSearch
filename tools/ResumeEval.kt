import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.service.AnalysisPlanner

/**
 * v3.9.0 断点续分析离线评测。
 *
 * 模拟「分析 N 张 → 中途被系统终止 → 再次启动」的完整链路：
 * 每张分析完立即落盘（FakeStore.append），重启后用 AnalysisPlanner 重算，
 * 断言已完成的照片绝不重跑、未完成的必跑、照片增删/排序变化不会作废已有结果。
 * 不依赖 Android，可在 JVM 上直接运行。
 */
private var passed = 0
private var failed = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name  $detail")
    }
}

private fun meta(id: Long, description: String = "中文描述-$id", analyzed: Boolean = true) =
    PhotoMetadata(photoId = id, description = description, isAnalyzed = analyzed)

/** 模拟行式结果文件：每 append 一张即视为已持久化。 */
private class FakeStore {
    val rows = LinkedHashMap<Long, PhotoMetadata>()
    var isComplete = false
    var totalPhotos = 0

    fun append(id: Long, m: PhotoMetadata) {
        rows[id] = m
    }

    fun plan(ids: List<Long>, vlm: Boolean) = AnalysisPlanner.plan(ids, rows, vlm)

    fun clear() {
        rows.clear()
        isComplete = false
        totalPhotos = 0
    }
}

/** 模拟一次 Service 运行：按计划逐张分析并立即落盘，可在 processedLimit 张后「被杀」。 */
private fun runOnce(
    store: FakeStore,
    photoIds: List<Long>,
    vlm: Boolean,
    processedLimit: Int = Int.MAX_VALUE,
    descriptionFor: (Long) -> String = { "中文描述-$it" }
): AnalysisPlanner.Plan {
    val plan = store.plan(photoIds, vlm)
    var processed = 0
    for (id in plan.pendingIds) {
        if (processed >= processedLimit) break   // 模拟系统终止 / 用户退出
        store.append(id, meta(id, descriptionFor(id)))
        processed++
    }
    store.totalPhotos = photoIds.size
    store.isComplete = processed >= plan.pendingCount
    return plan
}

fun main() {
    println("=== v3.9.0 断点续分析评测 ===")

    // ---------- 1. 首次分析 ----------
    run {
        val store = FakeStore()
        val ids = (1L..1000L).toList()
        val plan = store.plan(ids, vlm = false)
        check("1.1 首次分析全部入队", plan.pendingCount == 1000, "实际=${plan.pendingCount}")
        check("1.2 首次不算续跑", !plan.resumed && plan.doneCount == 0)
        check("1.3 不变量 done+pending==total", plan.doneCount + plan.pendingCount == plan.totalCount)
    }

    // ---------- 2. 中断后重启：核心诉求 ----------
    run {
        val store = FakeStore()
        val ids = (1L..1000L).toList()
        runOnce(store, ids, vlm = false, processedLimit = 300)

        val before = store.rows.keys.toSet()
        val plan = store.plan(ids, vlm = false)
        check("2.1 已完成 300 张", plan.doneCount == 300, "实际=${plan.doneCount}")
        check("2.2 只剩 700 张待分析", plan.pendingCount == 700, "实际=${plan.pendingCount}")
        check("2.3 已落盘的绝不重跑", plan.pendingIds.none { it in before })
        check("2.4 标记为续跑", plan.resumed && !plan.complete)
        check("2.5 待分析 id 连续且不漏", plan.pendingIds == (301L..1000L).toList())
        check("2.6 不变量仍成立", plan.doneCount + plan.pendingCount == plan.totalCount)

        // 再跑完剩余
        runOnce(store, ids, vlm = false)
        val done = store.plan(ids, vlm = false)
        check("2.7 全部完成", done.complete && done.pendingCount == 0)
        check("2.8 完成后再次启动不重跑", done.doneCount == 1000 && !done.resumed)
    }

    // ---------- 3. 逐张中断：任意位置都不回退 ----------
    run {
        val ids = (1L..500L).toList()
        var allOk = true
        var detail = ""
        for (stopAt in listOf(1, 7, 49, 128, 333, 499)) {
            val store = FakeStore()
            runOnce(store, ids, vlm = false, processedLimit = stopAt)
            if (store.rows.size != stopAt) { allOk = false; detail = "stopAt=$stopAt 落盘=${store.rows.size}" }
            val plan = store.plan(ids, vlm = false)
            if (plan.doneCount != stopAt || plan.pendingCount != 500 - stopAt) {
                allOk = false; detail = "stopAt=$stopAt done=${plan.doneCount} pending=${plan.pendingCount}"
            }
            if (plan.pendingIds.any { it in store.rows.keys }) {
                allOk = false; detail = "stopAt=$stopAt 出现重复分析"
            }
        }
        check("3.1 任意中断点都精确停在下一张", allOk, detail)
    }

    // ---------- 4. 照片数变化不再清空结果（旧版致命缺陷） ----------
    run {
        val store = FakeStore()
        val ids = (1L..1000L).toList()
        runOnce(store, ids, vlm = false, processedLimit = 400)
        val doneIds = store.rows.keys.toSet()

        // 新增 5 张照片插在中间与末尾
        val withNew = ids.toMutableList().apply {
            add(3, 2001L); add(500, 2002L); addAll(listOf(2003L, 2004L, 2005L))
        }
        val plan = store.plan(withNew, vlm = false)
        check("4.1 新增 5 张后总数 1005", plan.totalCount == 1005)
        check("4.2 已完成 400 张不重跑", plan.pendingIds.none { it in doneIds })
        check("4.3 待分析 = 600 旧 + 5 新", plan.pendingCount == 605, "实际=${plan.pendingCount}")
        check("4.4 5 张新照片都在队列里",
            listOf(2001L, 2002L, 2003L, 2004L, 2005L).all { it in plan.pendingIds })

        // 删除 2 张已分析的、2 张未分析的
        val removed = withNew.filter { it !in setOf(10L, 20L, 700L, 800L) }
        val p2 = store.plan(removed, vlm = false)
        check("4.5 删除照片不影响其他 id", p2.pendingIds.none { it in doneIds } && p2.totalCount == 1001)
        check("4.6 删除未分析照片后待分析减少", p2.pendingCount == 603, "实际=${p2.pendingCount}")
    }

    // ---------- 5. 排序变化不产生错位 ----------
    run {
        val store = FakeStore()
        val ids = (1L..1000L).toList()
        runOnce(store, ids, vlm = false, processedLimit = 400)
        val shuffled = ids.shuffled(java.util.Random(42))
        val plan = store.plan(shuffled, vlm = false)
        check("5.1 乱序后已完成数不变", plan.doneCount == 400, "实际=${plan.doneCount}")
        check("5.2 乱序后不重复分析", plan.pendingIds.none { it in store.rows.keys })
        check("5.3 乱序后不漏分析", plan.pendingCount == 600)
    }

    // ---------- 6. isComplete 标记不再是续跑门槛 ----------
    run {
        val store = FakeStore()
        val ids = (1L..10L).toList()
        runOnce(store, ids, vlm = false)
        store.isComplete = true
        val withNew = ids + 99L
        val plan = store.plan(withNew, vlm = false)
        check("6.1 已标记完成但出现新照片时仍会增量分析",
            plan.pendingIds == listOf(99L) && plan.resumed,
            "实际=${plan.pendingIds}")
        check("6.2 旧照片不被重跑", plan.doneCount == 10)
    }

    // ---------- 7. 云端 AI 描述缺失自动补齐 ----------
    run {
        val store = FakeStore()
        val ids = (1L..20L).toList()
        // 全部都有本地标签，但 3 张 AI 描述为空（限流 / 断网造成的占位）
        ids.forEach { id ->
            store.append(id, meta(id, description = if (id in setOf(4L, 9L, 17L)) "" else "描述-$id"))
        }
        val withVlm = store.plan(ids, vlm = true)
        check("7.1 开启 AI 时 3 张空描述进入补齐队列",
            withVlm.pendingIds == listOf(4L, 9L, 17L), "实际=${withVlm.pendingIds}")
        check("7.2 retryVlmCount 统计正确", withVlm.retryVlmCount == 3)
        check("7.3 补齐队列也算续跑", withVlm.resumed && withVlm.doneCount == 17)

        val noVlm = store.plan(ids, vlm = false)
        check("7.4 关闭 AI 时不重复分析占位结果", noVlm.complete && noVlm.pendingCount == 0)

        // 补齐后不再反复重试
        listOf(4L, 9L, 17L).forEach { store.append(it, meta(it, description = "补齐-$it")) }
        check("7.5 补齐后不再重试", store.plan(ids, vlm = true).complete)
    }

    // ---------- 8. 去重与异常数据 ----------
    run {
        val store = FakeStore()
        val dup = listOf(1L, 1L, 2L, 2L, 3L)
        val p1 = store.plan(dup, vlm = false)
        check("8.1 相册重复 id 被去重", p1.totalCount == 3 && p1.pendingIds == listOf(1L, 2L, 3L))
        check("8.2 空相册视为完成", store.plan(emptyList(), vlm = false).complete)

        store.append(1L, meta(1L, analyzed = false))   // 未标记完成的坏行
        val p2 = store.plan(listOf(1L, 2L), vlm = false)
        check("8.3 isAnalyzed=false 的坏行不算已完成", p2.pendingIds.contains(1L))
    }

    // ---------- 9. 重新分析全部 = 清空后从头（用户主动要求时） ----------
    run {
        val store = FakeStore()
        val ids = (1L..100L).toList()
        runOnce(store, ids, vlm = false, processedLimit = 60)
        store.clear()
        val plan = store.plan(ids, vlm = false)
        check("9.1 清空后从头分析", plan.pendingCount == 100 && !plan.resumed && plan.doneCount == 0)
    }

    // ---------- 10. 几万张照片的性能 ----------
    run {
        val store = FakeStore()
        val ids = (1L..30_000L).toList()
        // 已分析 20,000 张
        for (id in 1L..20_000L) store.append(id, meta(id))
        val t0 = System.currentTimeMillis()
        val plan = store.plan(ids, vlm = true)
        val cost = System.currentTimeMillis() - t0
        check("10.1 3 万张照片计划耗时 < 500ms", cost < 500, "实际=${cost}ms")
        check("10.2 只排 1 万张未分析", plan.pendingCount == 10_000 && plan.doneCount == 20_000)
        check("10.3 大库下不重复分析", plan.pendingIds.none { it in store.rows.keys })
        println("  INFO  3万张计划耗时 ${cost}ms，pending=${plan.pendingCount}")
    }

    println("=== 通过: $passed / ${passed + failed} ===")
    if (failed > 0) {
        println("!!! FAILED: $failed")
        kotlin.system.exitProcess(1)
    } else {
        println("ALL ASSERTIONS PASSED")
    }
}
