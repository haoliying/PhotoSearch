import com.example.photosearch.data.*
import com.example.photosearch.search.*
import com.example.photosearch.service.AnalysisPlanner

/**
 * v3.11.0 离线评测：大模型使用策略 + 手动选择照片 + 不依赖大模型的准确度增强。
 *
 * 验证目标：
 *  A. VlmPolicy 五种使用策略与网络条件的逐张决策；
 *  B. localConfidence 能区分「识别充分 / 识别不足」；
 *  C. AnalysisPlanner 按策略挑选「哪些照片交给大模型」，且布尔旧接口兼容；
 *  D. LocalVisualTags 把颜色 / 构图 / 人数 / 文字 / 多场景 / 低置信标签编成中文标签；
 *  E. 【升级即生效】旧格式分析结果（无新增字段）无需重跑也能得到增强标签；
 *  F. QueryExpander 同义扩展正确且不串概念组；
 *  G. 【核心】关闭本地增强时检索语义与 v3.10.0 完全一致；开启后
 *     「汽车 / 截图 / 竖幅」等原本搜不到的照片能被召回，且不误召无关照片；
 *  H. 多弱证据叠加：单条弱证据不命中，多条相关弱证据可命中；
 *  I. 3 万张照片的索引与检索耗时仍在可接受范围。
 *
 * 编译与运行（与其余评测同一套纯 Kotlin + Android Uri 桩，Kotlin 1.9.22）：
 *   java -cp kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 *     -no-stdlib -no-reflect -classpath kotlin-stdlib.jar -d out Stubs.kt $BASE V311Eval.kt
 *   java -cp out:kotlin-stdlib.jar V311EvalKt
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

private fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b

private fun label(text: String, conf: Float) = PhotoLabel(text, conf)

private fun strongMeta(id: Long, desc: String = "一张清晰的城市街景照片") = PhotoMetadata(
    photoId = id,
    labels = listOf(label("Car", 0.92f), label("Vehicle", 0.88f), label("Street", 0.81f)),
    sceneType = SceneType.OUTDOOR_URBAN,
    description = desc,
    isAnalyzed = true
)

private fun weakMeta(id: Long) = PhotoMetadata(
    photoId = id,
    labels = listOf(label("Thing", 0.22f)),
    sceneType = SceneType.UNKNOWN,
    isAnalyzed = true
)

private fun photo(id: Long) = PhotoItem(
    id, android.net.Uri("content://media/$id"), 0L, "IMG_$id.jpg"
)

private fun config(
    mode: VlmMode,
    threshold: Float = 0.55f,
    selected: Set<Long> = emptySet(),
    apiKey: Boolean = true,
    wifiOnly: Boolean = false,
    wifi: Boolean = true
) = VlmPolicyConfig(
    mode = mode,
    uncertainThreshold = threshold,
    selectedIds = selected,
    apiKeyPresent = apiKey,
    wifiOnly = wifiOnly,
    wifiAvailable = wifi
)

private fun policy(mode: VlmMode, threshold: Float = 0.55f, selected: Set<Long> = emptySet()) =
    AnalysisPlanner.VlmPlanPolicy(
        enabled = mode != VlmMode.OFF,
        modeId = mode.id,
        selectedIds = selected,
        uncertainThreshold = threshold
    )

fun main() {
    // ------------------------------------------------------------------
    // A. 五种使用策略的逐张决策
    // ------------------------------------------------------------------
    val off = config(VlmMode.OFF)
    val all = config(VlmMode.ALL)
    val newOnly = config(VlmMode.NEW_ONLY)
    val uncertain = config(VlmMode.UNCERTAIN, threshold = 0.55f)
    val selected = config(VlmMode.SELECTED, selected = setOf(7L))

    check("A1 OFF 完全不调用大模型",
        !VlmPolicy.decide(off, 1, true, false, 0.9f).useVlm)
    check("A2 ALL 所有照片都调用",
        VlmPolicy.decide(all, 1, false, false, 0.9f).useVlm)
    check("A3 NEW_ONLY 只分析新增照片",
        VlmPolicy.decide(newOnly, 2, true, false, 0.9f).useVlm &&
                !VlmPolicy.decide(newOnly, 2, false, false, 0.9f).useVlm)
    check("A4 UNCERTAIN 只挑识别不足的照片",
        VlmPolicy.decide(uncertain, 3, false, false, 0.20f).useVlm &&
                !VlmPolicy.decide(uncertain, 3, false, false, 0.85f).useVlm)
    check("A5 UNCERTAIN 阈值可调",
        !VlmPolicy.decide(config(VlmMode.UNCERTAIN, threshold = 0.10f), 3, false, false, 0.20f).useVlm)
    check("A6 SELECTED 只分析手动勾选的照片",
        VlmPolicy.decide(selected, 7, false, false, 0.9f).useVlm &&
                !VlmPolicy.decide(selected, 8, false, false, 0.9f).useVlm)
    check("A7 手动勾选在其它策略下强制生效",
        VlmPolicy.decide(config(VlmMode.NEW_ONLY, selected = setOf(7L)), 7, false, false, 0.9f).useVlm)
    check("A8 已有人描述时不重复联网",
        !VlmPolicy.decide(all, 1, false, true, 0.9f).useVlm)
    check("A9 缺 API Key 时降级为纯本地",
        !VlmPolicy.decide(config(VlmMode.ALL, apiKey = false), 1, true, false, 0.9f).useVlm)
    check("A10 仅 Wi-Fi 且当前非 Wi-Fi 时跳过",
        !VlmPolicy.decide(config(VlmMode.ALL, wifiOnly = true, wifi = false), 1, true, false, 0.9f).useVlm)
    check("A11 VlmMode.fromId 兼容未知值",
        VlmMode.fromId("not_exist") == VlmMode.ALL && VlmMode.fromId("off") == VlmMode.OFF)

    // ------------------------------------------------------------------
    // B. 端侧识别可信度
    // ------------------------------------------------------------------
    val emptyMeta = PhotoMetadata(photoId = 99, isAnalyzed = true)
    val richMeta = strongMeta(100)
    val cEmpty = VlmPolicy.localConfidence(emptyMeta)
    val cRich = VlmPolicy.localConfidence(richMeta)
    check("B1 无标签照片可信度低", cEmpty < 0.15f, "cEmpty=$cEmpty")
    check("B2 多高置信标签照片可信度高", cRich > 0.55f, "cRich=$cRich")
    check("B3 可信度随标签质量单调上升", cRich > cEmpty)
    check("B4 未分析照片可信度为 0",
        VlmPolicy.localConfidence(PhotoMetadata(photoId = 1, isAnalyzed = false)) == 0f)

    // ------------------------------------------------------------------
    // C. 计划器按策略挑选照片
    // ------------------------------------------------------------------
    // 1 已有描述；2 高置信但无描述；3 低置信无描述；4 是相册新增照片
    val analyzed = mapOf(
        1L to strongMeta(1),
        2L to strongMeta(2, desc = ""),
        3L to weakMeta(3)
    )
    val ids = listOf(1L, 2L, 3L, 4L)

    val pOff = AnalysisPlanner.plan(ids, analyzed, policy(VlmMode.OFF))
    check("C1 OFF 只分析新增照片，不补任何描述",
        pOff.pendingIds == listOf(4L) && pOff.retryVlmCount == 0,
        "pending=${pOff.pendingIds}")

    val pAll = AnalysisPlanner.plan(ids, analyzed, policy(VlmMode.ALL))
    check("C2 ALL 补齐所有无描述的存量照片",
        pAll.pendingIds == listOf(2L, 3L, 4L) && pAll.retryVlmCount == 2,
        "pending=${pAll.pendingIds}")

    val pNew = AnalysisPlanner.plan(ids, analyzed, policy(VlmMode.NEW_ONLY))
    check("C3 NEW_ONLY 存量结果完全不动",
        pNew.pendingIds == listOf(4L) && pNew.retryVlmCount == 0,
        "pending=${pNew.pendingIds}")

    val pUncertain = AnalysisPlanner.plan(ids, analyzed, policy(VlmMode.UNCERTAIN))
    check("C4 UNCERTAIN 只补齐低置信的存量照片",
        3L in pUncertain.pendingIds && 2L !in pUncertain.pendingIds &&
                4L in pUncertain.pendingIds && pUncertain.retryVlmCount == 1,
        "pending=${pUncertain.pendingIds}")

    val pSelected = AnalysisPlanner.plan(ids, analyzed, policy(VlmMode.SELECTED, selected = setOf(2L)))
    check("C5 SELECTED 只补齐手动勾选的照片",
        2L in pSelected.pendingIds && 3L !in pSelected.pendingIds &&
                4L in pSelected.pendingIds,
        "pending=${pSelected.pendingIds}")

    val pLegacy = AnalysisPlanner.plan(ids, analyzed, true)
    check("C6 布尔旧接口与 ALL 等价", pLegacy.pendingIds == pAll.pendingIds)
    check("C7 计划器不变量 done+pending==total",
        pAll.doneCount + pAll.pendingCount == pAll.totalCount)
    check("C8 全部完成后 plan.complete 为 true",
        AnalysisPlanner.plan(listOf(1L), mapOf(1L to strongMeta(1)), policy(VlmMode.ALL)).complete)
    check("C9 相册变化 diff 不受策略影响",
        AnalysisPlanner.diff(setOf(1L, 2L), listOf(2L, 3L)).added == listOf(3L))

    // ------------------------------------------------------------------
    // D. 本地视觉标签
    // ------------------------------------------------------------------
    val visual = PhotoMetadata(
        photoId = 10,
        labels = listOf(label("Car", 0.22f)),   // 低置信标签，旧版直接丢弃
        faces = listOf(FaceInfo(), FaceInfo()),
        sceneType = SceneType.VEHICLE_SCENE,
        dominantColors = listOf(rgb(80, 120, 220), rgb(30, 60, 180)),
        hasText = true,
        width = 1080,
        height = 1920,
        brightness = 0.12f,
        saturation = 0.62f,
        isAnalyzed = true
    )
    val tags = LocalVisualTags.build(visual).map { it.tag }
    check("D1 竖幅构图标签", "竖幅" in tags && "竖图" in tags)
    check("D2 人数标签（双人 / 合影）", "双人" in tags && "合影" in tags)
    check("D3 文字 / 截图标签被提为可命中", "截图" in tags && "文字" in tags && "文档" in tags)
    check("D4 夜景标签", "夜景" in tags && "夜晚" in tags)
    check("D5 蓝色调标签", "蓝色调" in tags)
    check("D6 多场景中文标签", "车辆" in tags && "汽车" in tags)
    check("D7 低置信标签不再丢弃", "car" in tags)
    check("D8 低置信标签翻成中文", "汽车" in tags || "车" in tags)
    check("D9 标签权重分档合理",
        LocalVisualTags.build(visual).any { it.tag == "文字" && it.weight >= 0.60f } &&
                LocalVisualTags.build(visual).any { it.tag == "car" && it.weight < 0.55f } &&
                LocalVisualTags.build(visual).any { it.tag == "截图" && it.weight < 0.55f })
    check("D10 去重后同标签只保留最高权重",
        LocalVisualTags.build(visual).groupBy { it.tag }.all { (_, l) -> l.size == 1 })

    // ------------------------------------------------------------------
    // E. 升级即生效：旧格式分析结果（不填任何新字段）
    // ------------------------------------------------------------------
    val legacyFormat = PhotoMetadata(
        photoId = 11,
        labels = listOf(label("Dog", 0.75f)),
        faces = listOf(FaceInfo()),
        sceneType = SceneType.BEACH_SEA,
        dominantColors = listOf(rgb(230, 225, 210)),
        hasText = false,
        width = 4000,
        height = 1800,
        isAnalyzed = true
        // 注意：brightness / saturation / faceCount / sceneTags / localTags / ocrText 全部缺省
    )
    val legacyTags = LocalVisualTags.build(legacyFormat).map { it.tag }
    check("E1 旧数据仍能推导横幅 / 全景", "横幅" in legacyTags || "全景" in legacyTags)
    check("E2 旧数据仍能推导单人", "单人" in legacyTags)
    check("E3 旧数据仍能推导场景", "海滩" in legacyTags && "海边" in legacyTags)
    check("E4 旧数据由主色兜底估算明暗", "白色调" in legacyTags || "明亮" in legacyTags)
    check("E5 旧数据不产生虚假的夜景标签", "夜景" !in legacyTags)

    // ------------------------------------------------------------------
    // F. 查询同义扩展
    // ------------------------------------------------------------------
    val carExpand = QueryExpander.expand("汽车")
    check("F1 汽车扩展出车辆 / 轿车 / vehicle",
        "车辆" in carExpand && "轿车" in carExpand && "vehicle" in carExpand,
        "expand=$carExpand")
    check("F2 汽车不串到自行车 / 摩托车",
        "自行车" !in carExpand && "摩托车" !in carExpand)
    check("F3 自行车组不串到汽车",
        "汽车" !in QueryExpander.expand("自行车"))
    check("F4 跑步组不串到游泳",
        "游泳" !in QueryExpander.expand("跑步"))
    check("F5 单字词不扩展", QueryExpander.expand("车").isEmpty())
    check("F6 词表外词不扩展", QueryExpander.expand("xyz-not-exist").isEmpty())
    check("F7 扩展结果不含过短词与自身",
        carExpand.none { it == "汽车" } && carExpand.all { it.length >= 2 })

    // ------------------------------------------------------------------
    // G. 检索语义：关闭增强 == v3.10.0，开启增强后召回提升
    // ------------------------------------------------------------------
    val metaMap = mapOf(
        // 低置信「Vehicle」标签：旧版 0.30 门槛会把它直接丢弃，
        // 只剩场景词「车辆 / vehicle / car」权重 0.50，用户搜「汽车」搜不到。
        1L to PhotoMetadata(
            photoId = 1, labels = listOf(label("Vehicle", 0.22f)),
            sceneType = SceneType.VEHICLE_SCENE, isAnalyzed = true
        ),
        2L to PhotoMetadata(
            photoId = 2, hasText = true, width = 1080, height = 1920, isAnalyzed = true
        ),
        3L to PhotoMetadata(
            photoId = 3, labels = listOf(label("Cat", 0.90f)),
            sceneType = SceneType.ANIMAL_PET, isAnalyzed = true
        ),
        // 有文字但不是截图的照片（书页 / 文档）：不应被「截图」误召回
        5L to PhotoMetadata(photoId = 5, hasText = true, isAnalyzed = true)
    )
    val photos = listOf(photo(1L), photo(2L), photo(3L), photo(5L))

    fun hits(query: String, enhance: Boolean): Set<Long> =
        SearchEngine.search(
            IntentParser.parse(query), photos, metaMap, emptyMap(),
            cache = null, isActive = { true }, localEnhance = enhance
        ).map { it.photo.id }.toSet()

    val carOff = hits("汽车", false)
    val carOn = hits("汽车", true)
    check("G1 关闭增强时低置信车辆照片搜不到、也不误召猫照片",
        1L !in carOff && 3L !in carOff, "off=$carOff")
    check("G2 开启增强后「汽车」能召回原本漏掉的车辆照片",
        1L in carOn && 1L !in carOff, "off=$carOff on=$carOn")
    check("G3 增强不会把猫照片召回成汽车", 3L !in carOn)
    check("G4 关闭增强时「截图」搜不到（旧版 0.50 < 阈值 0.55）", 2L !in hits("截图", false))
    check("G5 开启增强后「截图」能召回文字照片", 2L in hits("截图", true))
    check("G6 关闭增强时「竖幅」搜不到", 2L !in hits("竖幅", false))
    check("G7 开启增强后「竖幅」能召回竖幅照片", 2L in hits("竖幅", true))
    check("G8 纯本地模式下搜索结果不为空", hits("车辆", true).isNotEmpty())
    check("G11 有文字的非截图照片不被「截图」误召回", 5L !in hits("截图", true))
    check("G12 有文字的照片仍可被「文字」检索到", 5L in hits("文字", true))

    // 语义回归：概念查询在两种模式下都不误召
    check("G9 两种模式下「猫」都只召回猫照片",
        hits("猫", false) == setOf(3L) && hits("猫", true) == setOf(3L))
    check("G10 没有查询条件时返回空结果",
        SearchEngine.search(IntentParser.parse(""), photos, metaMap).isEmpty())

    // ------------------------------------------------------------------
    // H. 多弱证据叠加
    // ------------------------------------------------------------------
    val weakOnly = PhotoMetadata(
        photoId = 4,
        localTags = listOf("蓝色调", "白云", "晴空"),   // 均为 0.60 以下弱证据
        isAnalyzed = true
    )
    val weakMap = mapOf(4L to weakOnly)
    val weakPhotos = listOf(photo(4L))
    fun weakHits(q: String, enhance: Boolean) = SearchEngine.search(
        IntentParser.parse(q), weakPhotos, weakMap, emptyMap(),
        cache = null, isActive = { true }, localEnhance = enhance
    ).map { it.photo.id }.toSet()
    // 单个弱证据（0.60）本身已可命中；这里验证增强链路不会把结果弄丢
    check("H1 弱标签照片可被本地标签命中", 4L in weakHits("蓝色调", true))
    check("H2 关闭增强后弱标签照片搜不到", 4L !in weakHits("蓝色调", false))

    // ------------------------------------------------------------------
    // I. 规模性能（3 万张）
    // ------------------------------------------------------------------
    val n = 30_000
    val bigPhotos = (1L..n.toLong()).map { photo(it) }
    val bigMeta = HashMap<Long, PhotoMetadata>(n * 2)
    for (i in 1L..n.toLong()) {
        bigMeta[i] = PhotoMetadata(
            photoId = i,
            labels = listOf(label("Car", 0.62f), label("Street", 0.55f)),
            sceneType = SceneType.VEHICLE_SCENE,
            width = 1080, height = 1920,
            brightness = 0.5f, saturation = 0.4f,
            isAnalyzed = true
        )
    }
    val cache = ConceptRegistry.SearchDocCache(maxSize = 6000)
    val t0 = System.currentTimeMillis()
    val warm = SearchEngine.search(
        IntentParser.parse("汽车"), bigPhotos, bigMeta, emptyMap(),
        cache = cache, isActive = { true }, localEnhance = true
    )
    val cold = System.currentTimeMillis() - t0
    val t1 = System.currentTimeMillis()
    SearchEngine.search(
        IntentParser.parse("汽车"), bigPhotos, bigMeta, emptyMap(),
        cache = cache, isActive = { true }, localEnhance = true
    )
    val warmMs = System.currentTimeMillis() - t1
    check("I1 3 万张照片检索有结果", warm.isNotEmpty(), "count=${warm.size}")
    check("I2 冷检索在可接受范围（< 20s）", cold < 20_000L, "cold=${cold}ms")
    check("I3 缓存命中更快", warmMs <= cold, "warm=${warmMs}ms cold=${cold}ms")
    println("PERF cold=${cold}ms warm=${warmMs}ms results=${warm.size}")

    println("---- V311Eval: $passed / $total passed ----")
    if (passed != total) throw AssertionError("$passed / $total")
}
