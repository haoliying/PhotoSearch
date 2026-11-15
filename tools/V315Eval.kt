import com.example.photosearch.data.*
import com.example.photosearch.search.*
import android.net.Uri

/**
 * v3.11.4 离线评测：搜索准确度（标签与关键字匹配度门槛）。
 *
 * 用户诉求：「增加准确度调整，标签和搜索关键字的匹配度要大于等于准确度才算匹配」。
 * 本评测验证本次改造中**可离线验证**的核心逻辑：
 *
 *  A. 常量、范围收敛与档位
 *  B. threshold(base, pct) = max(通道基线, 准确度) 的语义与单调性
 *  C. meets(score, pct) 的 >= 语义与浮点边界容差
 *  D. 匹配度展示 / 档位名称 / 说明文案
 *  E. 默认 50% 与 v3.11.3 逐位兼容（旧调用、coerce 越界值）
 *  F. 真实检索：调高准确度后结果单调收紧、强证据保留、弱证据被剔除
 *  G. 豁免：时间/地点纯筛选、排除条件不受准确度影响
 *  H. SearchResult.matchPercent 展示正确
 *  I. 与 localEnhance / 三种逻辑运算符组合不回归
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

/** 四张照片：1 强 VLM 证据 / 2 中等标签 / 3 弱标签 / 4 完全无关。 */
private fun corpus(): Pair<List<PhotoItem>, Map<Long, PhotoMetadata>> {
    val metas = listOf(
        1 to M(desc = "一辆白色汽车停在路边", kw = listOf("汽车", "轿车")),
        2 to M(labels = listOf(PhotoLabel("Car", 0.70f))),
        3 to M(labels = listOf(PhotoLabel("Car", 0.32f))),
        4 to M(desc = "蓝天白云，没有别的物体", labels = listOf(PhotoLabel("Sky", 0.92f)))
    )
    val photos = metas.map { (id, m) -> P(id, m) }
    return photos to metas.associate { (id, m) -> id.toLong() to m }
}

private fun idsAt(pct: Int, query: String = "汽车"): Set<Int> {
    val (photos, metaMap) = corpus()
    return SearchEngine.search(
        intent = IntentParser.parse(query),
        photos = photos,
        metadataMap = metaMap,
        accuracyPercent = pct
    ).map { it.photo.id.toInt() }.toSet()
}

fun main() {
    val A = MatchAccuracy

    println("== A. 常量、范围收敛与档位 ==")
    check("A1 基线 50 / 上限 95 / 默认 50 / 步长 5",
        A.MIN_PERCENT == 50 && A.MAX_PERCENT == 95 &&
                A.DEFAULT_PERCENT == 50 && A.STEP_PERCENT == 5)
    check("A2 越界与异常值收敛",
        A.coerce(-100) == 50 && A.coerce(0) == 50 && A.coerce(1000) == 95 &&
                A.coerce(63) == 63, "coerce(-100)=${A.coerce(-100)} coerce(1000)=${A.coerce(1000)}")
    check("A3 默认值等于基线（保证升级零差异）", A.DEFAULT_PERCENT == A.MIN_PERCENT)
    check("A4 百分比换算",
        A.asFraction(50) == 0.5f && A.asFraction(80) == 0.8f && A.asFraction(95) == 0.95f)
    check("A5 四档且递增",
        A.PRESETS.size == 4 && A.PRESETS.map { it.percent } == listOf(50, 65, 80, 90))
    check("A6 档位数值都在合法范围内",
        A.PRESETS.all { it.percent in A.MIN_PERCENT..A.MAX_PERCENT })

    println()
    println("== B. threshold = max(通道基线, 准确度) ==")
    // v3.11.3 的四条通道基线
    val CONCEPT_BASE = 0.50f
    val TEXT_BASE = 0.55f
    val EXPAND_BASE = 0.62f
    check("B1 默认 50%：概念通道阈值不变", A.threshold(CONCEPT_BASE, 50) == CONCEPT_BASE)
    check("B2 默认 50%：全文 / 叠加通道阈值不变", A.threshold(TEXT_BASE, 50) == TEXT_BASE)
    check("B3 默认 50%：扩展通道阈值不变", A.threshold(EXPAND_BASE, 50) == EXPAND_BASE)
    check("B4 默认 50% 与 v3.11.3 四通道逐一相等（逐位兼容）",
        listOf(CONCEPT_BASE, TEXT_BASE, TEXT_BASE, EXPAND_BASE).all {
            A.threshold(it, A.DEFAULT_PERCENT) == it
        })
    check("B5 调高到 80%：所有通道抬到 0.80",
        A.threshold(CONCEPT_BASE, 80) == 0.80f &&
                A.threshold(TEXT_BASE, 80) == 0.80f &&
                A.threshold(EXPAND_BASE, 80) == 0.80f)
    check("B6 准确度介于基线之间时取准确度",
        A.threshold(EXPAND_BASE, 65) == 0.65f && A.threshold(CONCEPT_BASE, 65) == 0.65f)
    check("B7 准确度低于基线时保留基线（不放宽、不引入噪声）",
        A.threshold(EXPAND_BASE, 50) == 0.62f && A.threshold(TEXT_BASE, 55) == 0.55f)
    check("B8 单调：准确度升高阈值不下降",
        listOf(CONCEPT_BASE, TEXT_BASE, EXPAND_BASE).all { base ->
            (50..95 step 5).windowed(2).all { (a, b) ->
                A.threshold(base, a) <= A.threshold(base, b)
            }
        })
    check("B9 任意组合都不低于基线、也不低于准确度",
        listOf(0.3f, 0.5f, 0.55f, 0.62f, 0.9f).all { base ->
            (50..95 step 5).all { p ->
                A.threshold(base, p) >= base && A.threshold(base, p) + 1e-6f >= A.asFraction(p)
            }
        })
    check("B10 阈值上限不超过 1（不会把全部结果清空到无解）",
        (50..95).all { A.threshold(0.62f, it) <= 1.0f })

    println()
    println("== C. meets：匹配度 >= 准确度才算匹配 ==")
    check("C1 等于边界算匹配", A.meets(0.80f, 80) && A.meets(0.50f, 50))
    check("C2 低于边界不算匹配", !A.meets(0.7999f, 80) && !A.meets(0.4999f, 50))
    check("C3 高于边界算匹配", A.meets(1.0f, 95) && A.meets(0.9f, 65))
    check("C4 浮点累加误差不误杀（0.1+0.45 应达到 55%）",
        A.meets(0.1f + 0.45f, 55), "sum=${0.1f + 0.45f}")
    check("C5 固定分数下，准确度越高越难达标",
        (50..95 step 5).windowed(2).all { (a, b) ->
            !A.meets(0.62f, b) || A.meets(0.62f, a)
        })
    check("C6 单调：分数越高越容易达标",
        (0..100 step 5).windowed(2).all { (a, b) ->
            !A.meets(a / 100f, 70) || A.meets(b / 100f, 70)
        })

    println()
    println("== D. 展示层：匹配度 / 档位 / 说明 ==")
    check("D1 percentOf 四舍五入并 clamp 到 0..100",
        A.percentOf(0.80f) == 80 && A.percentOf(0f) == 0 && A.percentOf(1.5f) == 100)
    check("D2 labelOf 映射四档",
        A.labelOf(50) == "标准" && A.labelOf(65) == "较准" &&
                A.labelOf(80) == "严格" && A.labelOf(90) == "极严格" &&
                A.labelOf(95) == "极严格")
    check("D3 默认说明强调与旧版一致", A.summary(50).contains("旧版一致"))
    check("D4 非默认说明带出具体门槛值",
        A.summary(65).contains("65%") && A.summary(80).contains("80%"))
    check("D5 越界值也能安全生成说明",
        A.summary(-5).isNotBlank() && A.summary(999).isNotBlank())

    println()
    println("== E. 默认 50% 与 v3.11.3 逐位兼容（真实检索） ==")
    val baseDefault = idsAt(A.DEFAULT_PERCENT)
    val explicit50 = idsAt(50)
    val lowerBound = idsAt(A.coerce(0))
    check("E1 默认档结果非空且只含相关照片",
        baseDefault.isNotEmpty() && 4 !in baseDefault, "got=$baseDefault")
    check("E2 显式 50% 与默认档结果完全一致", baseDefault == explicit50)
    check("E3 越界值收敛后结果一致（旧配置不会改变行为）", baseDefault == lowerBound)
    val (photosE, metasE) = corpus()
    val legacyCall = SearchEngine.quickSearch("汽车", photosE, metasE)
        .map { it.photo.id.toInt() }.toSet()
    check("E4 旧调用 quickSearch（不带准确度参数）结果一致",
        legacyCall == baseDefault, "quick=$legacyCall")

    println()
    println("== F. 真实检索：调高准确度单调收紧 ==")
    val at50 = idsAt(50)
    val at65 = idsAt(65)
    val at80 = idsAt(80)
    val at90 = idsAt(90)
    val at95 = idsAt(95)
    println("    50%=$at50 65%=$at65 80%=$at80 90%=$at90 95%=$at95")
    check("F1 结果集随准确度单调递减（子集关系）",
        at65.all { it in at50 } && at80.all { it in at65 } &&
                at90.all { it in at80 } && at95.all { it in at90 })
    check("F2 结果数不增且确实变少",
        at50.size >= at65.size && at65.size >= at80.size && at50.size > at80.size,
        "50%=${at50.size} 80%=${at80.size}")
    check("F3 强证据照片（VLM 关键词 1.0）在所有档位保留",
        listOf(at50, at65, at80, at90, at95).all { 1 in it })
    check("F4 弱证据照片在 50% 保留、在 80% 被剔除",
        3 in at50 && 3 !in at80)
    check("F5 完全无关的照片在任何档位都不出现",
        listOf(at50, at65, at80, at90, at95).all { 4 !in it })
    check("F6 中等证据照片在 50% 保留、在 90% 被剔除",
        2 in at50 && 2 !in at90)
    check("F7 每个档位都能正常返回（不会出现异常或全空）",
        listOf(at50, at65, at80, at90, at95).all { it.isNotEmpty() })

    println()
    println("== G. 豁免：结构化筛选 / 排除条件不受准确度影响 ==")
    val (photosG, metasG) = corpus()
    val range = TimeRange(1_600_000_000_000L, 1_800_000_000_000L, "2020~2027")
    val filterIntent = SearchIntent("按时间", emptyList(), timeRange = range)
    val f50 = SearchEngine.search(filterIntent, photosG, metasG, accuracyPercent = 50)
    val f95 = SearchEngine.search(filterIntent, photosG, metasG, accuracyPercent = 95)
    check("G1 纯时间筛选在高准确度下仍返回全部通过过滤的照片",
        f50.map { it.photo.id }.toSet() == f95.map { it.photo.id }.toSet() &&
                f95.size == photosG.size, "size=${f95.size}")
    check("G2 纯筛选结果按 100% 匹配度展示", f95.all { it.matchPercent == 100 })
    check("G3 带时间筛选 + 内容词时，高准确度仍能收紧内容结果",
        SearchEngine.search(
            IntentParser.parse("汽车").copy(timeRange = range), photosG, metasG,
            accuracyPercent = 95
        ).size <= SearchEngine.search(
            IntentParser.parse("汽车").copy(timeRange = range), photosG, metasG,
            accuracyPercent = 50
        ).size)
    val neg50 = SearchEngine.search(IntentParser.parse("没有汽车"), photosG, metasG, accuracyPercent = 50)
    val neg95 = SearchEngine.search(IntentParser.parse("没有汽车"), photosG, metasG, accuracyPercent = 95)
    check("G4 排除条件只做否决，不受准确度影响",
        neg50.map { it.photo.id }.toSet() == neg95.map { it.photo.id }.toSet() && neg95.isNotEmpty(),
        "neg95=${neg95.map { it.photo.id }}")
    check("G5 排除条件确实把含汽车的强证据照片挡掉",
        neg95.none { it.photo.id == 1L })

    println()
    println("== H. SearchResult.matchPercent 展示 ==")
    val resultsH = SearchEngine.search(
        IntentParser.parse("汽车"), photosG, metasG, accuracyPercent = 80
    )
    check("H1 每个结果的匹配度都在 0..100",
        resultsH.all { it.matchPercent in 0..100 })
    // v3.11.5 口径升级说明：v3.11.4 的 matchPercent = percentOf(avgScore)（内部证据分口径），
    // 与用户设定的准确度不在同一刻度，导致「照片标着 90%、严格 80% 却搜不到」。
    // v3.11.5 起 matchPercent 改为 50~100 的综合匹配度，且判定与展示同源，
    // 因此本断言改为「结果匹配度必然 >= 门槛」，等价契约由 V316Eval 完整覆盖。
    check("H2 v3.11.5：结果匹配度必然 >= 当前准确度门槛（展示与判定同源）",
        resultsH.all { it.matchPercent >= 80 && it.matchPercent in 50..100 })
    check("H3 结果按 score 降序排列",
        resultsH.map { it.score } == resultsH.map { it.score }.sortedDescending())
    check("H4 默认档下弱证据也带匹配度（列表视图可展示）",
        SearchEngine.search(IntentParser.parse("汽车"), photosG, metasG)
            .all { it.matchPercent in 0..100 })

    println()
    println("== I. 与 localEnhance / 三种逻辑组合不回归 ==")
    val iEnhanceOff = SearchEngine.search(
        IntentParser.parse("汽车"), photosG, metasG,
        localEnhance = false, accuracyPercent = 50
    ).map { it.photo.id.toInt() }.toSet()
    val iEnhanceOffStrict = SearchEngine.search(
        IntentParser.parse("汽车"), photosG, metasG,
        localEnhance = false, accuracyPercent = 90
    ).map { it.photo.id.toInt() }.toSet()
    check("I1 关闭本地增强时准确度仍生效",
        iEnhanceOffStrict.all { it in iEnhanceOff } && iEnhanceOffStrict.size <= iEnhanceOff.size)
    val orIntent = SearchIntent(
        "汽车或者猫",
        listOf(
            SearchCondition(ConditionType.OBJECT, listOf("汽车"), concept = Concept.CAR),
            SearchCondition(ConditionType.OBJECT, listOf("猫"), concept = Concept.CAT)
        ),
        logicOperator = LogicOperator.OR
    )
    val or50 = SearchEngine.search(orIntent, photosG, metasG, accuracyPercent = 50)
    val or90 = SearchEngine.search(orIntent, photosG, metasG, accuracyPercent = 90)
    check("I2 OR 模式下调高准确度不会增加结果",
        or90.size <= or50.size && or90.all { r -> or50.any { it.photo.id == r.photo.id } })
    val rankIntent = SearchIntent(
        "咖啡", listOf(SearchCondition(ConditionType.COMPOSITE, listOf("咖啡"))),
        logicOperator = LogicOperator.RANK
    )
    val rank50 = SearchEngine.search(rankIntent, photosG, metasG, accuracyPercent = 50)
    val rank95 = SearchEngine.search(rankIntent, photosG, metasG, accuracyPercent = 95)
    check("I3 RANK 模式下调高准确度不会抛异常、不会增加结果",
        rank95.size <= rank50.size)
    check("I4 空意图（无条件无筛选）在任何准确度下都返回空",
        SearchEngine.search(SearchIntent("", emptyList()), photosG, metasG, accuracyPercent = 95).isEmpty())

    println()
    println("---- V315Eval: $passed / $total passed ----")
    if (passed != total) throw AssertionError("V315Eval 有失败项: ${total - passed}")
}
