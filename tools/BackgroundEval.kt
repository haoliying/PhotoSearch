import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.search.ConceptRegistry
import com.example.photosearch.search.IntentParser
import com.example.photosearch.search.SearchEngine
import com.example.photosearch.service.AnalysisPlanner
import com.example.photosearch.service.IncrementBuffer

/**
 * v3.10.0 离线评测：后台守护 + 相册变化监听 + 增量事件 + 检索隔离。
 *
 * 验证目标：
 *  A. AnalysisPlanner.diff 正确检出「新增/删除/无变化/乱序/重复」；
 *  B. 相册新增照片时 AnalysisPlanner.plan 只把新增的排进 pending（不重跑旧的）；
 *  C. IncrementBuffer 合并去重、后写覆盖、分批与节流边界；
 *  D. 【核心】增量失效只清这几张文档缓存，其余仍命中（对比 clear() 全清），
 *     且检索语义在增量前后完全一致 —— 后台分析不影响前端搜索；
 *  E. 事件丢失/分批/逐张回放结果一致（磁盘为唯一权威的运行期等价性）；
 *  F. 3 万张照片的计划与合并耗时。
 *
 * 编译与运行（与其余评测同一套 BASE + Android 桩，Kotlin 1.9.22）：
 *   java -cp kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 *     -d out $BASE BackgroundEval.kt
 *   java -cp out:kotlin-stdlib.jar BackgroundEvalKt
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

private fun meta(id: Long, desc: String): PhotoMetadata =
    PhotoMetadata(photoId = id, description = desc, isAnalyzed = true)

private fun photo(id: Long): PhotoItem =
    PhotoItem(id, android.net.Uri("content://media/$id"), 0L, "IMG_$id.jpg")

fun main() {
    // ------------------------------------------------------------------
    // A. 相册变化检测 diff
    // ------------------------------------------------------------------
    val base = (1L..10L).toList()
    val d0 = AnalysisPlanner.diff(emptySet(), base)
    check("A1 首次对账：全部视为新增", d0.added == base && d0.removed.isEmpty() && d0.currentCount == 10,
        "added=${d0.added.size}")

    val d1 = AnalysisPlanner.diff(base.toSet(), base)
    check("A2 无变化：changed=false", !d1.changed && d1.added.isEmpty() && d1.removed.isEmpty())

    val d2 = AnalysisPlanner.diff(base.toSet(), base + listOf(101L, 102L, 103L))
    check("A3 新增 3 张", d2.added == listOf(101L, 102L, 103L) && d2.removed.isEmpty(),
        "added=${d2.added}")

    val d3 = AnalysisPlanner.diff(base.toSet(), base - listOf(4L, 7L))
    check("A4 删除 2 张", d3.removed.toSet() == setOf(4L, 7L) && d3.added.isEmpty(),
        "removed=${d3.removed}")

    val d4 = AnalysisPlanner.diff(base.toSet(), (base - listOf(2L, 3L)) + listOf(201L))
    check("A5 同时增删", d4.removed.toSet() == setOf(2L, 3L) && d4.added == listOf(201L))

    val d5 = AnalysisPlanner.diff(base.toSet(), base.reversed())
    check("A6 仅排序变化不算增删", !d5.changed, "added=${d5.added.size}, removed=${d5.removed.size}")

    val d6 = AnalysisPlanner.diff(base.toSet(), base + listOf(5L, 5L, 5L))
    check("A7 重复 id 去重且不算新增", !d6.changed && d6.currentCount == 10,
        "count=${d6.currentCount}")

    val d7 = AnalysisPlanner.diff(setOf(1L, 2L, 3L), listOf(2L, 3L, 4L))
    check("A8 部分重叠", d7.added == listOf(4L) && d7.removed == listOf(1L))

    // ------------------------------------------------------------------
    // B. 相册变化 → 只分析新增 / 未完成
    // ------------------------------------------------------------------
    val analyzed10 = base.associateWith { meta(it, "已分析照片") }
    val p1 = AnalysisPlanner.plan(base + listOf(101L, 102L, 103L), analyzed10, false)
    check("B1 全部已分析 + 新增 3 张 → 只分析这 3 张",
        p1.pendingIds == listOf(101L, 102L, 103L), "pending=${p1.pendingIds}")
    check("B2 新增后 done+pending==total", p1.doneCount + p1.pendingCount == p1.totalCount,
        "${p1.doneCount}+${p1.pendingCount}/${p1.totalCount}")

    val p2 = AnalysisPlanner.plan(base - listOf(4L, 7L), analyzed10, false)
    check("B3 删除照片不触发重跑", p2.complete && p2.pendingIds.isEmpty(),
        "pending=${p2.pendingIds}")

    val p3 = AnalysisPlanner.plan(emptyList(), emptyMap(), false)
    check("B4 空相册 complete", p3.complete && p3.totalCount == 0)

    val half = base.take(4).associateWith { meta(it, "已分析") }
    val p4 = AnalysisPlanner.plan(base, half, false)
    check("B5 中断续跑：pending 恰为未分析的 6 张", p4.pendingIds == base.drop(4),
        "pending=${p4.pendingIds}")
    check("B6 续跑标记 resumed=true", p4.resumed && !p4.complete)

    val p5 = AnalysisPlanner.plan(listOf(5L, 1L, 9L, 3L, 101L, 2L), analyzed10, false)
    check("B7 新照片插在中间：按相册顺序且只挑新增",
        p5.pendingIds == listOf(101L), "pending=${p5.pendingIds}")

    // 开启 AI 时补齐「无描述」占位结果
    val withPlaceholder = analyzed10.toMutableMap()
    withPlaceholder[3L] = meta(3L, "")
    val p6 = AnalysisPlanner.plan(base, withPlaceholder, true)
    check("B8 开启 AI 时把无描述占位纳入补齐", p6.pendingIds == listOf(3L) && p6.retryVlmCount == 1,
        "pending=${p6.pendingIds}, retry=${p6.retryVlmCount}")
    check("B9 关闭 AI 时不重跑占位结果", AnalysisPlanner.plan(base, withPlaceholder, false).complete)

    // ------------------------------------------------------------------
    // C. IncrementBuffer：合并 / 分批 / 节流
    // ------------------------------------------------------------------
    check("C1 coalesce 空输入", IncrementBuffer.coalesce(emptyList()).isEmpty())

    val evts = listOf(1L to meta(1, "a"), 2L to meta(2, "b"), 1L to meta(1, "a2"))
    val c2 = IncrementBuffer.coalesce(evts)
    check("C2 同 id 后写覆盖", c2.size == 2 && c2[1L]?.description == "a2",
        "desc=${c2[1L]?.description}")
    check("C3 合并保持首次出现顺序", c2.keys.toList() == listOf(1L, 2L), "keys=${c2.keys}")

    val many = (1L..65L).map { it to meta(it, "d$it") }
    val bs = IncrementBuffer.batches(many, 20)
    check("C4 65 张切成 4 批", bs.size == 4, "batches=${bs.size}")
    check("C5 每批不超过 maxSize 且总数守恒",
        bs.all { it.size <= 20 } && bs.sumOf { it.size } == 65,
        "sizes=${bs.map { it.size }}")
    check("C6 空事件不产生批次", IncrementBuffer.batches(emptyList(), 20).isEmpty())

    check("C7 达到条数上限立即 flush", IncrementBuffer.shouldFlush(20, 1000L, 1000L, 20, 300L))
    check("C8 未达上限但超间隔也 flush", IncrementBuffer.shouldFlush(5, 1000L, 1500L, 20, 300L))
    check("C9 未达上限且未超间隔不 flush", !IncrementBuffer.shouldFlush(5, 1000L, 1100L, 20, 300L))
    check("C10 缓冲为空不 flush", !IncrementBuffer.shouldFlush(0, 1000L, 9000L, 20, 300L))

    val inv = IncrementBuffer.idsToInvalidate(c2)
    check("C11 idsToInvalidate 覆盖全部 key", inv.toSet() == c2.keys.toSet())
    check("C12 idsToInvalidate 空批次", IncrementBuffer.idsToInvalidate(emptyMap()).isEmpty())

    // ------------------------------------------------------------------
    // D. 【核心】索引隔离：增量失效不清空缓存，检索语义不变
    // ------------------------------------------------------------------
    val n = 1000
    val ids = (1L..n.toLong()).toList()
    val photos = ids.map { photo(it) }
    val metas = ids.associateWith { id ->
        meta(id, if (id % 3L == 0L) "红色汽车 轿车 停在路边" else "风景 树木 山川 湖泊")
    }.toMutableMap()
    val intent = IntentParser.parse("汽车")

    fun searchIds(cache: ConceptRegistry.SearchDocCache): Set<Long> =
        SearchEngine.search(intent, photos, metas, emptyMap(), cache).map { it.photo.id }.toSet()

    val carIds = ids.filter { it % 3L == 0L }.toSet()
    val cache = ConceptRegistry.SearchDocCache()
    // 预热：模拟前端已经搜过一次，1000 张文档全部进缓存
    val before = searchIds(cache)
    check("D1 预热后检索结果正确", before == carIds, "size=${before.size}, expect=${carIds.size}")
    check("D2 预热后缓存命中 1000 张", cache.size() == n, "cache=${cache.size()}")

    // 后台分析改写了 5 张（原本不命中汽车 → 现在命中），前端按增量事件吸收
    val changedIds = listOf(1L, 2L, 4L, 5L, 7L)
    val batchEvents = changedIds.map { it to meta(it, "黑色汽车 轿车") }
    val batch = IncrementBuffer.coalesce(batchEvents)
    metas.putAll(batch)                                  // 对应 absorbIncrement 的 putAll
    cache.invalidate(IncrementBuffer.idsToInvalidate(batch))   // 只失效这 5 张，绝不全量 clear

    check("D3 增量失效后缓存仍保留其余 995 张（未全清）",
        cache.size() == n - changedIds.size, "cache=${cache.size()}, expect=${n - changedIds.size}")

    val after = searchIds(cache)
    check("D4 增量更新后检索语义正确（新增 5 张命中）",
        after == carIds + changedIds.toSet(), "size=${after.size}, expect=${(carIds + changedIds).size}")
    check("D5 增量失效后缓存重新补满（5 张现算）", cache.size() == n, "cache=${cache.size()}")

    // 对照：旧版「完成即全量 sync」会 clear 掉整个缓存
    cache.clear()
    check("D6 对照：clear() 会清空全部索引（旧逻辑开销来源）", cache.size() == 0)
    val rebuilt = searchIds(cache)
    check("D7 clear 后重建结果仍一致（语义等价，但代价是全库重算）",
        rebuilt == after && cache.size() == n, "cache=${cache.size()}")

    // 连续多批增量：缓存始终维持高位，不出现「每批全清」
    var minCache = Int.MAX_VALUE
    var stable = true
    for (round in 1..20) {
        val batchIds = ((round * 20 + 1)..(round * 20 + 20)).map { it.toLong() }
        val e = batchIds.map { it to meta(it, "汽车 照片 $round") }
        val b = IncrementBuffer.coalesce(e)
        metas.putAll(b)
        cache.invalidate(IncrementBuffer.idsToInvalidate(b))
        val s = searchIds(cache)
        if (!s.containsAll(batchIds)) stable = false
        minCache = minOf(minCache, cache.size())
    }
    check("D8 连续 20 批增量期间缓存未全清", minCache > 900, "minCache=$minCache")
    check("D9 连续增量期间检索结果持续正确", stable)

    // ------------------------------------------------------------------
    // E. 事件丢失 / 分批 / 逐张回放等价（磁盘为唯一权威）
    // ------------------------------------------------------------------
    val raw = (1L..500L).map { it to meta(it, "v1-$it") } +
            (1L..100L).map { it to meta(it, "v2-$it") }   // 前 100 张被改写
    val direct = LinkedHashMap<Long, PhotoMetadata>()
    raw.forEach { (id, m) -> direct[id] = m }            // 逐张 apply（相当于磁盘全量）
    val viaBatches = LinkedHashMap<Long, PhotoMetadata>()
    IncrementBuffer.batches(raw, 20).forEach { b -> viaBatches.putAll(b) }
    check("E1 分批 apply 与逐张 apply 结果完全一致", direct == viaBatches,
        "direct=${direct.size}, batches=${viaBatches.size}")

    // 模拟丢掉 3 批事件：下次冷启动从磁盘 load() 全量补齐
    val lost = LinkedHashMap<Long, PhotoMetadata>()
    IncrementBuffer.batches(raw, 20).drop(3).forEach { b -> lost.putAll(b) }
    val recovered = LinkedHashMap<Long, PhotoMetadata>(lost)
    recovered.putAll(direct)                             // load() 覆盖式补齐
    check("E2 丢事件后由磁盘全量补齐，结果一致", recovered == direct,
        "recovered=${recovered.size}")

    check("E3 合并幂等：coalesce(coalesce(x)) == coalesce(x)",
        IncrementBuffer.coalesce(IncrementBuffer.coalesce(raw).toList()) ==
                IncrementBuffer.coalesce(raw))

    // ------------------------------------------------------------------
    // F. 3 万张照片的计划 / 合并性能
    // ------------------------------------------------------------------
    val bigN = 30_000
    val bigIds = (1L..bigN.toLong()).toList()
    val bigAnalyzed = bigIds.take(bigN - 25).associateWith { meta(it, "已分析") }
    val t0 = System.currentTimeMillis()
    val bigPlan = AnalysisPlanner.plan(bigIds, bigAnalyzed, false)
    val planMs = System.currentTimeMillis() - t0
    check("F1 3 万张计划只挑出 25 张待分析",
        bigPlan.pendingIds.size == 25 && bigPlan.doneCount == bigN - 25,
        "pending=${bigPlan.pendingIds.size}, ${planMs}ms")
    check("F2 3 万张计划耗时 < 2000ms", planMs < 2000, "${planMs}ms")

    val bigEvents = bigIds.map { it to meta(it, "d") }
    val t1 = System.currentTimeMillis()
    val bigBatches = IncrementBuffer.batches(bigEvents, 20)
    val mergeMs = System.currentTimeMillis() - t1
    check("F3 3 万张切成 1500 批", bigBatches.size == 1500, "batches=${bigBatches.size}")
    check("F4 3 万张分批合并耗时 < 2000ms", mergeMs < 2000, "${mergeMs}ms")

    val t2 = System.currentTimeMillis()
    val bigCurrent = (bigIds - setOf(9L)) + listOf(99_999L)
    val bigDiff = AnalysisPlanner.diff(bigIds.toSet(), bigCurrent)
    val diffMs = System.currentTimeMillis() - t2
    check("F5 3 万张变化检测只得到 1 增 1 删",
        bigDiff.added == listOf(99_999L) && bigDiff.removed == listOf(9L) &&
                bigDiff.currentCount == bigN,
        "added=${bigDiff.added}, removed=${bigDiff.removed}, ${diffMs}ms")

    // ------------------------------------------------------------------
    println("通过: $passed/$total")
    if (passed == total) println("ALL ASSERTIONS PASSED")
    else throw AssertionError("${total - passed} assertions failed")
}
