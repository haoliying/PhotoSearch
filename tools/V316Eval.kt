import com.example.photosearch.data.*
import com.example.photosearch.search.*
import android.net.Uri

/**
 * v3.11.5 离线评测：修复「设为严格档就检索不到，但查看图片有的是 90%，也查不到」。
 *
 * 根因复现（v3.11.4）：
 *  - 详情页标签角标显示原始置信度 90%；
 *  - 检索判定用内部证据分 0.45 + 0.35*0.9 = 0.765，端侧证据上限恰好 0.80；
 *  - 严格档阈值 = max(基线, 0.80) = 0.80 -> 0.765 不达标 -> 全库几乎 0 结果；
 *  - 列表「综合匹配度」又是第三套口径（percentOf(avgScore) = 77%）。
 *
 * 修复后的核心不变量：
 *  **综合匹配度既是列表展示值、也是与准确度比较的唯一判定值，>= 才算命中。**
 *
 * 分组：
 *  A. 用户反馈场景复现与修复验证
 *  B. 一致性契约（展示 == 判定）
 *  C. toPercent 纯函数（刻度、单调、clamp）
 *  D. 默认 50% 与 v3.11.3 / v3.11.4 逐位兼容
 *  E. 单调性与档位可用性（严格档不再全空）
 *  F. 各命中通道的匹配度
 *  G. 豁免：纯筛选 / 纯排除条件
 *  H. AND / OR / RANK 组合
 *  I. localEnhance 开关
 *  J. v3.11.4 纯函数保持兼容
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

private fun M(
    desc: String = "",
    kw: List<String> = emptyList(),
    labels: List<PhotoLabel> = emptyList(),
    scene: SceneType = SceneType.UNKNOWN
) = PhotoMetadata(
    photoId = 0L, labels = labels, sceneType = scene,
    isAnalyzed = true, description = desc, vlmKeywords = kw
)

private fun P(id: Int, m: PhotoMetadata, date: Long = 1_700_000_000_000L) =
    PhotoItem(
        id.toLong(), Uri("p$id"), date, "p$id.jpg",
        labels = m.labels, metadata = m, isAnalyzed = true
    )

/** 1 强 VLM 证据 / 2 高置信端侧标签 0.90 / 3 中等 0.70 / 4 弱 0.32 / 5 无关。 */
private fun corpus(): Pair<List<PhotoItem>, Map<Long, PhotoMetadata>> {
    val metas = listOf(
        1 to M(desc = "一辆白色汽车停在路边", kw = listOf("汽车", "轿车")),
        2 to M(labels = listOf(PhotoLabel("Car", 0.90f))),
        3 to M(labels = listOf(PhotoLabel("Car", 0.70f))),
        4 to M(labels = listOf(PhotoLabel("Car", 0.32f))),
        5 to M(desc = "蓝天白云，没有别的物体", labels = listOf(PhotoLabel("Sky", 0.92f)))
    )
    val photos = metas.map { (id, m) -> P(id, m) }
    return photos to metas.associate { (id, m) -> id.toLong() to m }
}

private fun resultsAt(
    pct: Int,
    query: String = "汽车",
    photos: List<PhotoItem>? = null,
    metas: Map<Long, PhotoMetadata>? = null
): List<SearchEngine.SearchResult> {
    val (p, m) = if (photos != null && metas != null) photos to metas else corpus()
    return SearchEngine.search(
        IntentParser.parse(query), p, m, accuracyPercent = pct
    )
}

private fun idsAt(pct: Int, query: String = "汽车"): Set<Int> =
    resultsAt(pct, query).map { it.photo.id.toInt() }.toSet()

fun main() {
    val A = MatchAccuracy
    val (photos, metas) = corpus()

    println("== A. 用户反馈场景：标签 90%，严格档 80% 却查不到 ==")
    // 用单张照片隔离复现，避免其他照片干扰
    val one = listOf(P(2, metas[2L]!!))
    val oneMap = mapOf(2L to metas[2L]!!)
    val detailPct = (0.90f * 100).toInt()
    val ev = ConceptRegistry.prepare(metas[2L]!!).evidence[Concept.CAR] ?: 0f
    println("    详情页标签角标 = $detailPct%（原始置信度），概念通道证据 = $ev")
    check("A1 复现旧口径：0.90 置信度的内部证据确实 < 0.80（旧严格档阈值）",
        ev < 0.80f && kotlin.math.abs(ev - 0.765f) < 1e-4f, "evidence=$ev")
    val strict = SearchEngine.search(
        IntentParser.parse("汽车"), one, oneMap, accuracyPercent = 80
    )
    check("A2 修复后：严格 80% 档能查到这张标签 90% 的照片",
        strict.size == 1 && strict[0].photo.id == 2L, "size=${strict.size}")
    check("A3 其综合匹配度 >= 80（与门槛同源，不再显示 77%）",
        strict.isNotEmpty() && strict[0].matchPercent >= 80,
        "matchPercent=${strict.firstOrNull()?.matchPercent}")
    check("A4 极严格 90% 档同样能查到高置信标签照片",
        SearchEngine.search(IntentParser.parse("汽车"), one, oneMap, accuracyPercent = 90)
            .any { it.photo.id == 2L })
    check("A5 检测器确属概念通道命中",
        strict.firstOrNull()?.matchSource?.startsWith("concept:") == true,
        "source=${strict.firstOrNull()?.matchSource}")

    println()
    println("== B. 一致性契约：展示值 == 判定值 ==")
    for (pct in listOf(50, 55, 65, 80, 90, 95)) {
        val rs = resultsAt(pct)
        check("B1 准确度 $pct%：所有结果的 matchPercent >= $pct%",
            rs.all { it.matchPercent >= pct },
            "percents=${rs.map { it.matchPercent }}")
        check("B2 准确度 $pct%：所有结果匹配度落在 50..100",
            rs.all { it.matchPercent in 50..100 })
    }
    val r50 = resultsAt(50)
    check("B3 默认档所有结果匹配度 >= 50（不会误杀旧版结果）",
        r50.all { it.matchPercent >= 50 })
    // 幂等：把结果的展示值当作门槛重新检索，该照片必须仍在结果里
    val r80 = resultsAt(80)
    val idempotent = r80.all { r ->
        SearchEngine.search(
            IntentParser.parse("汽车"), photos, metas,
            accuracyPercent = A.coerce(r.matchPercent)
        ).any { it.photo.id == r.photo.id }
    }
    check("B4 幂等：以展示的匹配度为门槛重跑，该照片仍命中", idempotent)
    // 同一照片在不同档位下（只要都出现），匹配度不变 —— 匹配度只取决于证据，不随档位漂移
    val percentsByDoc = (50..95 step 5).mapNotNull { p ->
        resultsAt(p).firstOrNull { it.photo.id == 2L }?.matchPercent
    }
    check("B5 同一照片的综合匹配度不随准确度档位漂移",
        percentsByDoc.isNotEmpty() && percentsByDoc.distinct().size == 1,
        "values=${percentsByDoc.distinct()}")
    check("B6 结果仍按 score 降序排列（排序逻辑未改）",
        r80.map { it.score } == r80.map { it.score }.sortedDescending())

    println()
    println("== C. toPercent：50~100 相对刻度 ==")
    check("C1 通道基线 -> 50 分",
        A.toPercent(0.50f, 0.50f) == 50 && A.toPercent(0.55f, 0.55f) == 50 &&
                A.toPercent(0.62f, 0.62f) == 50)
    check("C2 端侧证据上限 0.80 -> 100 分",
        A.toPercent(0.80f, 0.50f) == 100 && A.toPercent(0.80f, 0.55f) == 100 &&
                A.toPercent(0.80f, 0.62f) == 100)
    check("C3 VLM 更强证据(0.9 / 1.0) clamp 到 100，不会溢出",
        A.toPercent(0.90f, 0.50f) == 100 && A.toPercent(1.0f, 0.55f) == 100)
    check("C4 低于基线的噪声不产生低于 50 的匹配度",
        A.toPercent(0.10f, 0.50f) == 50 && A.toPercent(-1f, 0.55f) == 50)
    check("C5 单调：证据分越高匹配度不下降",
        (0..80).map { it / 100f }.windowed(2).all { (a, b) ->
            A.toPercent(a, 0.50f) <= A.toPercent(b, 0.50f)
        })
    check("C6 任意 score >= base 时结果恒 >= 50 且 <= 100",
        listOf(0.50f, 0.55f, 0.62f).all { base ->
            (0..100).all { i ->
                val s = base + i / 100f
                A.toPercent(s, base) in 50..100
            }
        })
    check("C7 meetsPercent 是 >= 语义",
        A.meetsPercent(80, 80) && A.meetsPercent(94, 80) && !A.meetsPercent(79, 80))
    check("C8 meetsPercent 对越界准确度先收敛",
        A.meetsPercent(60, 0) && A.meetsPercent(100, 999) && !A.meetsPercent(94, 999))

    println()
    println("== D. 默认 50% 与旧版逐位兼容 ==")
    val legacy = SearchEngine.quickSearch("汽车", photos, metas).map { it.photo.id.toInt() }.toSet()
    check("D1 默认档结果与旧调用 quickSearch 完全一致",
        idsAt(A.DEFAULT_PERCENT) == legacy, "default=${idsAt(50)} legacy=$legacy")
    check("D2 显式 50% 与默认档一致", idsAt(50) == idsAt(A.DEFAULT_PERCENT))
    check("D3 越界值收敛后结果不变", idsAt(50) == idsAt(A.coerce(-100)))
    check("D4 默认档保留全部四张含汽车证据的照片（与新口径无关，兼容旧行为）",
        idsAt(50) == setOf(1, 2, 3, 4), "got=${idsAt(50)}")
    check("D5 无关照片在任何档位都不出现",
        (50..95 step 5).all { 5 !in idsAt(it) })

    println()
    println("== E. 单调性与档位可用性 ==")
    val ladder = listOf(50, 65, 80, 90, 95).map { idsAt(it) }
    println("    50%..95% = $ladder")
    check("E1 结果集随准确度单调递减（逐级子集）",
        ladder.windowed(2).all { (a, b) -> b.all { it in a } })
    check("E2 严格 80% 档不再返回空集（用户反馈的核心症状）",
        ladder[2].isNotEmpty(), "80%=${ladder[2]}")
    check("E3 极严格 90% 档仍保留最强证据照片 1",
        listOf(ladder[3], ladder[4]).all { 1 in it })
    check("E4 VLM 强证据照片 1 在所有档位保留", ladder.all { 1 in it })
    check("E5 弱证据照片 4 在 50% 保留、在 80% 被剔除",
        4 in ladder[0] && 4 !in ladder[2])
    check("E6 高置信端侧标签 2 在严格 80% 保留（修复前被误杀）",
        2 in ladder[2], "80%=${ladder[2]}")

    println()
    println("== F. 各命中通道的匹配度 ==")
    check("F1 VLM 关键词命中 -> 100 分",
        resultsAt(50).first { it.photo.id == 1L }.matchPercent == 100)
    check("F2 概念通道 0.90 置信度 -> 90+ 分",
        resultsAt(50).first { it.photo.id == 2L }.matchPercent >= 90,
        "percent=${resultsAt(50).first { it.photo.id == 2L }.matchPercent}")
    check("F3 全文通道基线处也有合法匹配度",
        A.toPercent(0.60f, 0.55f) in 50..100 && A.toPercent(0.775f, 0.55f) >= 90)
    check("F4 扩展通道基线处也有合法匹配度",
        A.toPercent(0.62f, 0.62f) == 50 && A.toPercent(0.75f, 0.62f) in 50..100)
    check("F5 多弱证据叠加分换算后仍是合法匹配度",
        A.toPercent(0.65f, 0.55f) in 50..100 && A.toPercent(0.65f, 0.55f) >= 50,
        "agg0.65 -> ${A.toPercent(0.65f, 0.55f)}")
    check("F6 叠加分越高匹配度不下降",
        A.toPercent(0.60f, 0.55f) <= A.toPercent(0.75f, 0.55f))
    // 低置信标签 + 场景信号的照片：默认档仍应能召回并带合法匹配度
    val weakMeta = M(labels = listOf(PhotoLabel("Car", 0.33f)), scene = SceneType.VEHICLE_SCENE)
    val weakPhotos = listOf(P(9, weakMeta))
    val weakMap = mapOf(9L to weakMeta)
    val weakRs = SearchEngine.search(
        IntentParser.parse("汽车"), weakPhotos, weakMap, accuracyPercent = 50
    )
    check("F7 低置信 + 场景信号照片在默认档能召回且匹配度合法",
        weakRs.isNotEmpty() && weakRs.all { it.matchPercent in 50..100 },
        "size=${weakRs.size} percents=${weakRs.map { it.matchPercent }}")
    check("F8 该照片在极严格档被合理收紧",
        SearchEngine.search(
            IntentParser.parse("汽车"), weakPhotos, weakMap, accuracyPercent = 95
        ).size <= weakRs.size)

    println()
    println("== G. 豁免：纯筛选 / 纯排除条件 ==")
    val range = TimeRange(1_600_000_000_000L, 1_800_000_000_000L, "2020~2027")
    val filterIntent = SearchIntent("按时间", emptyList(), timeRange = range)
    val f50 = SearchEngine.search(filterIntent, photos, metas, accuracyPercent = 50)
    val f95 = SearchEngine.search(filterIntent, photos, metas, accuracyPercent = 95)
    check("G1 纯时间筛选在高准确度下仍返回全部照片",
        f50.map { it.photo.id }.toSet() == f95.map { it.photo.id }.toSet() &&
                f95.size == photos.size)
    check("G2 纯筛选结果固定按 100% 展示", f95.all { it.matchPercent == 100 })
    val neg50 = SearchEngine.search(IntentParser.parse("没有汽车"), photos, metas, accuracyPercent = 50)
    val neg95 = SearchEngine.search(IntentParser.parse("没有汽车"), photos, metas, accuracyPercent = 95)
    check("G3 纯排除条件查询不受准确度影响且非空",
        neg50.map { it.photo.id }.toSet() == neg95.map { it.photo.id }.toSet() && neg95.isNotEmpty(),
        "neg95=${neg95.map { it.photo.id }}")
    check("G4 排除条件豁免后按 100% 展示（不会因 0 分被门槛误杀）",
        neg95.all { it.matchPercent == 100 })
    check("G5 排除条件确实挡掉含汽车的强证据照片",
        neg95.none { it.photo.id == 1L || it.photo.id == 2L })
    check("G6 叠加内容词时高准确度仍能收紧结果",
        resultsAt(95).size <= resultsAt(50).size)

    println()
    println("== H. AND / OR / RANK 组合 ==")
    val andIntent = SearchIntent(
        "汽车和白色",
        listOf(
            SearchCondition(ConditionType.OBJECT, listOf("汽车"), concept = Concept.CAR),
            SearchCondition(ConditionType.COMPOSITE, listOf("白色"))
        ),
        logicOperator = LogicOperator.AND
    )
    val and50 = SearchEngine.search(andIntent, photos, metas, accuracyPercent = 50)
    val and95 = SearchEngine.search(andIntent, photos, metas, accuracyPercent = 95)
    check("H1 AND 模式调高准确度不增加结果、不抛异常",
        and95.size <= and50.size && and95.all { r -> and50.any { it.photo.id == r.photo.id } })
    val orIntent = SearchIntent(
        "汽车或者猫",
        listOf(
            SearchCondition(ConditionType.OBJECT, listOf("汽车"), concept = Concept.CAR),
            SearchCondition(ConditionType.OBJECT, listOf("猫"), concept = Concept.CAT)
        ),
        logicOperator = LogicOperator.OR
    )
    val or50 = SearchEngine.search(orIntent, photos, metas, accuracyPercent = 50)
    val or90 = SearchEngine.search(orIntent, photos, metas, accuracyPercent = 90)
    check("H2 OR 模式：结果匹配度不低于门槛（不被未命中条件的 0 分拉低）",
        or50.all { it.matchPercent >= 50 } && or90.all { it.matchPercent >= 90 },
        "or50=${or50.map { it.matchPercent }} or90=${or90.map { it.matchPercent }}")
    check("H3 OR 模式调高准确度不增加结果",
        or90.size <= or50.size && or90.all { r -> or50.any { it.photo.id == r.photo.id } })
    val rankIntent = SearchIntent(
        "咖啡", listOf(SearchCondition(ConditionType.COMPOSITE, listOf("咖啡"))),
        logicOperator = LogicOperator.RANK
    )
    val rank50 = SearchEngine.search(rankIntent, photos, metas, accuracyPercent = 50)
    val rank95 = SearchEngine.search(rankIntent, photos, metas, accuracyPercent = 95)
    check("H4 RANK 模式调高准确度不增加结果", rank95.size <= rank50.size)
    check("H5 空意图在任何档位返回空",
        (50..95 step 5).all {
            SearchEngine.search(
                SearchIntent("", emptyList()), photos, metas, accuracyPercent = it
            ).isEmpty()
        })

    println()
    println("== I. localEnhance 开关 ==")
    val off50 = SearchEngine.search(
        IntentParser.parse("汽车"), photos, metas, localEnhance = false, accuracyPercent = 50
    ).map { it.photo.id.toInt() }.toSet()
    val off95 = SearchEngine.search(
        IntentParser.parse("汽车"), photos, metas, localEnhance = false, accuracyPercent = 95
    ).map { it.photo.id.toInt() }.toSet()
    check("I1 关闭本地增强时准确度仍生效",
        off95.all { it in off50 } && off95.size <= off50.size, "off50=$off50 off95=$off95")
    check("I2 关闭本地增强时严格档仍能查到高置信标签",
        2 in SearchEngine.search(
            IntentParser.parse("汽车"), one, oneMap,
            localEnhance = false, accuracyPercent = 80
        ).map { it.photo.id.toInt() }.toSet())

    println()
    println("== J. v3.11.4 纯函数保持兼容 ==")
    check("J1 常量与档位未变",
        A.MIN_PERCENT == 50 && A.MAX_PERCENT == 95 && A.DEFAULT_PERCENT == 50 &&
                A.STEP_PERCENT == 5 && A.PRESETS.map { it.percent } == listOf(50, 65, 80, 90))
    check("J2 threshold / meets / asFraction 语义不变",
        A.threshold(0.55f, 50) == 0.55f && A.threshold(0.55f, 80) == 0.80f &&
                A.meets(0.80f, 80) && !A.meets(0.7999f, 80) && A.asFraction(65) == 0.65f)
    check("J3 percentOf 旧展示函数仍可用",
        A.percentOf(0.80f) == 80 && A.percentOf(1.5f) == 100)
    check("J4 coerce / labelOf / summary 不变",
        A.coerce(-1) == 50 && A.coerce(999) == 95 && A.labelOf(80) == "严格" &&
                A.summary(50).contains("旧版一致") && A.summary(65).contains("65%"))
    check("J5 EVIDENCE_CEILING 与端侧证据上限一致",
        A.EVIDENCE_CEILING == 0.80f &&
                kotlin.math.abs((0.45f + 0.35f * 1.0f) - A.EVIDENCE_CEILING) < 1e-6f &&
                kotlin.math.abs((0.55f + 0.25f * 1.0f) - A.EVIDENCE_CEILING) < 1e-6f)

    println()
    println("---- V316Eval: $passed / $total passed ----")
    if (passed != total) throw AssertionError("V316Eval 有失败项: ${total - passed}")
}
