package com.example.photosearch.search

import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata

/**
 * 检索引擎。
 *
 * v3.5.0 修复精确率：删除 fuzzyMatch / 2 字子串 / 双向 contains / 排除条件反向加分。
 * v3.5.2 修复召回率：概念通道 + 全文通道双路检索。
 * v3.6.0 新增时空联动：时间区间、地点（模糊 / 半径）作为硬过滤条件，
 *         与内容条件按 AND 组合；只有筛选器时返回全部通过过滤的照片。
 * v3.11.4 新增「搜索准确度」：用户可设定匹配度门槛。
 * v3.11.5 修复「严格档搜不到、但照片标着 90%」：
 *         - 通道召回恢复固定质量基线（默认档与 v3.11.3 逐位一致）；
 *         - 命中证据经 MatchAccuracy.toPercent 拉伸成 50~100 的「综合匹配度」；
 *         - 综合匹配度既是列表展示值、也是与准确度比较的唯一判定值；
 *         - 详见 [MatchAccuracy]。
 *
 * 时间来源优先级：EXIF DateTimeOriginal > 文件名时间戳 > MediaStore DATE_TAKEN。
 * 无时间或时间落在区间外的照片不参与时间筛选；无地点信息的照片不参与地点筛选。
 */
object SearchEngine {

    /** 后台检索被 newer 查询取消时抛出，ViewModel 捕获后丢弃本次结果。 */
    class SearchCancelledException : RuntimeException()

    private val STRICT_ASCII = Regex("^[a-z0-9 -]+$")
    private const val MIN_EVIDENCE = 0.5f       // 概念证据强度下限
    private const val TEXT_MIN_EVIDENCE = 0.55f // 全文通道证据强度下限
    /** v3.11.0：查询扩展词要求更强的证据，避免同义词兜底引入误召。 */
    private const val EXPAND_MIN_EVIDENCE = 0.62f
    /** 扩展命中分折扣：排序低于原词直接命中。 */
    private const val EXPAND_SCORE_FACTOR = 0.85f
    private const val RANK_THRESHOLD = 0.45f    // RANK 模式平均分下限
    private const val RANK_MIN_CONDITION = 0.2f
    private const val FILTER_ONLY_SCORE = 0.6f  // 纯筛选结果的固定分

    data class SearchResult(
        val photo: PhotoItem,
        val score: Float,
        val matchedConditions: List<String>,
        val matchSource: String = "",
        /**
         * 综合匹配度 0..100。v3.11.5 起它与准确度门槛比较用的是同一个数，
         * 因此结果列表里显示多少，就意味着它至少通过了多少的门槛。
         */
        val matchPercent: Int = 0
    )

    private data class ConditionEval(
        val condition: SearchCondition,
        val hit: Boolean,        // 原始命中（未考虑 negated）
        val effective: Boolean,  // 考虑 negated 后是否通过
        val score: Float,
        val source: String,
        /** v3.11.5：该条件命中证据换算出的综合匹配度（0 表示未命中）。 */
        val percent: Int
    )

    /** v3.11.5：一次查询内复用。通道基线固定，不随用户准确度抬高。 */
    private data class MatchThresholds(
        val concept: Float,
        val text: Float,
        val expand: Float
    )

    /** v3.11.5：单条件命中结果。percent 用与展示/终判同源的 50~100 刻度。 */
    private data class Hit(
        val matched: Boolean,
        val score: Float,
        val source: String,
        val percent: Int
    )

    private fun miss(): Hit = Hit(false, 0f, "", 0)

    /**
     * v3.8.0：
     * - cache：跨查询复用每张照片的预处理结果（概念证据 + 全文索引），不再每次重建；
     * - isActive：每 256 张检查一次，输入变化时可立刻取消旧查询，避免任务堆积。
     * 两个参数都有默认值，离线评测脚本无需改动。
     */
    fun search(
        intent: SearchIntent,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>,
        exifMap: Map<Long, PhotoExif> = emptyMap(),
        cache: ConceptRegistry.SearchDocCache? = null,
        isActive: () -> Boolean = { true },
        /** v3.11.0：是否启用不依赖大模型的本地准确度增强。 */
        localEnhance: Boolean = true,
        /** v3.11.5：综合匹配度门槛（%）。默认 50 = 与旧版完全一致。 */
        accuracyPercent: Int = MatchAccuracy.DEFAULT_PERCENT
    ): List<SearchResult> {
        if (intent.conditions.isEmpty() && !intent.hasFilters) return emptyList()

        val accuracy = MatchAccuracy.coerce(accuracyPercent)
        // v3.11.5：召回门槛固定为引擎质量基线。准确度只作用于最终综合匹配度，
        // 不再抬高高通道阈值 —— 否则端侧证据上限 0.80 会让「严格 80%」全库 0 结果。
        val thresholds = MatchThresholds(MIN_EVIDENCE, TEXT_MIN_EVIDENCE, EXPAND_MIN_EVIDENCE)

        return photos.mapIndexedNotNull { index, photo ->
            if (index % 256 == 0 && !isActive()) throw SearchCancelledException()
            val exif = exifMap[photo.id]

            // ---- 硬过滤 1：时间区间 ----
            val time = intent.timeRange
            if (time != null) {
                val t = effectiveTime(photo, exif)
                if (t <= 0L || !time.contains(t)) return@mapIndexedNotNull null
            }

            // ---- 硬过滤 2：地点（模糊 / 半径） ----
            val loc = intent.locationQuery
            if (loc != null) {
                if (exif == null || !LocationMatcher.matches(exif, loc)) return@mapIndexedNotNull null
            }

            // ---- 只有时间/地点筛选，没有内容词 ----
            // 结构化筛选是精确匹配，不属于「标签与关键字的匹配度」，按 100% 计。
            if (intent.filtersOnly) {
                return@mapIndexedNotNull SearchResult(
                    photo = photo,
                    score = FILTER_ONLY_SCORE,
                    matchedConditions = filterLabels(intent),
                    matchSource = "filters",
                    matchPercent = 100
                )
            }

            // 修复：分析失败/尚未分析的照片不再直接丢弃，至少用 PhotoItem 的标签参与检索
            val metadata = metadataMap[photo.id] ?: PhotoMetadata(
                photoId = photo.id,
                labels = photo.labels,
                isAnalyzed = false
            )
            // v3.8.0：优先复用缓存，未命中才现算；缓存未启用时行为与旧版一致
            val doc = cache?.get(photo.id, metadata, exif, localEnhance)
                ?: ConceptRegistry.prepare(metadata, exif, localEnhance)
            val evidence = doc.evidence
            val docIndex = doc.index

            val evals = intent.conditions.map { cond ->
                val h = evaluateCondition(cond, metadata, evidence, docIndex, localEnhance, thresholds)
                ConditionEval(
                    condition = cond,
                    hit = h.matched,
                    effective = if (cond.negated) !h.matched else h.matched,
                    score = if (cond.negated) 0f else h.score,
                    source = h.source,
                    percent = if (cond.negated) 0 else h.percent
                )
            }

            val negativeOk = evals.none { it.condition.negated && it.hit }
            val positive = evals.filter { !it.condition.negated }

            var total = 0f
            var weight = 0f
            positive.forEach {
                total += it.score * it.condition.weight
                weight += it.condition.weight
            }
            val avgScore = if (weight > 0f) total / weight else if (negativeOk) 0.5f else 0f

            // v3.11.5：综合匹配度 = 命中条件的匹配度按权重加权平均（未命中条件不计入分母，
            // 避免 OR 模式下被 0 分拉低）。纯排除条件查询没有正向条件，按 100% 计并豁免门槛。
            var pTotal = 0f
            var pWeight = 0f
            positive.forEach {
                if (it.hit && it.percent > 0) {
                    pTotal += it.percent * it.condition.weight
                    pWeight += it.condition.weight
                }
            }
            val noPositive = positive.isEmpty()
            val matchPercent = when {
                pWeight > 0f -> (pTotal / pWeight + 0.5f).toInt().coerceIn(0, 100)
                noPositive -> 100
                else -> 0
            }

            val logicOk = when (intent.logicOperator) {
                LogicOperator.AND -> evals.all { it.effective }
                // 排除条件只做否决，不参与 OR 命中判定（否则恒为真、全库返回）
                LogicOperator.OR -> positive.any { it.effective } || positive.isEmpty()
                LogicOperator.RANK -> {
                    val minScore = positive.minOfOrNull { it.score } ?: 0f
                    minScore >= RANK_MIN_CONDITION && avgScore >= RANK_THRESHOLD
                }
            }
            // v3.11.5 唯一终判：展示值与门槛同源，>= 才算匹配。
            // 纯排除条件查询（无正向条件）豁免，否则「没有汽车」在任何档位都会 0 结果。
            val accuracyOk = noPositive || MatchAccuracy.meetsPercent(matchPercent, accuracy)
            val pass = negativeOk && logicOk && accuracyOk

            if (!pass || avgScore <= 0f) return@mapIndexedNotNull null

            SearchResult(
                photo = photo,
                score = avgScore,
                matchedConditions = intent.conditions.map { conditionLabel(it) } + filterLabels(intent),
                matchSource = evals.maxByOrNull { it.score }?.source ?: "",
                matchPercent = matchPercent
            )
        }.sortedByDescending { it.score }
    }

    /** 时间单位归一：部分厂商把 DATE_TAKEN 写成秒。 */
    private fun effectiveTime(photo: PhotoItem, exif: PhotoExif?): Long {
        if (exif != null && exif.hasTime) return exif.dateTaken
        val t = photo.dateTaken
        return when {
            t <= 0L -> 0L
            t < 100_000_000_000L -> t * 1000L
            else -> t
        }
    }

    private fun filterLabels(intent: SearchIntent): List<String> {
        val out = mutableListOf<String>()
        intent.timeRange?.let { out += "时间:${TimeQueryParser.displayLabel(it)}" }
        intent.locationQuery?.let { out += "地点:${it.raw}" }
        return out
    }

    private fun evaluateCondition(
        condition: SearchCondition,
        metadata: PhotoMetadata,
        evidence: Map<Concept, Float>,
        index: ConceptRegistry.SearchIndex,
        localEnhance: Boolean,
        thresholds: MatchThresholds
    ): Hit {
        // 通道 1：规范概念（精确率最高）
        val concept = condition.concept
        if (concept != null) {
            val w = evidence[concept] ?: 0f
            // 排除条件只做否决、不产生结果，固定用引擎基线判定。
            val gate = if (condition.negated) MIN_EVIDENCE else thresholds.concept
            if (w >= gate) {
                return Hit(true, w, "concept:${concept.name}", MatchAccuracy.toPercent(w, MIN_EVIDENCE))
            }
        }

        // 排除条件：只用严格关键词匹配做否决，不启用全文通道
        if (condition.negated) {
            for (keyword in condition.keywords) {
                if (strictMatch(keyword, metadata)) return Hit(true, 0.6f, "keyword", 0)
            }
            return miss()
        }

        // 通道 2：全文检索（召回词表外的词，含 EXIF 地名/年份）。
        // 原词路径与 v3.10.0 完全一致：按长度降序，第一个达到阈值的即返回。
        val terms = condition.keywords.distinct().sortedByDescending { it.length }
        val weakHits = ArrayList<Float>()
        for (term in terms) {
            val s = index.score(term)
            if (s > 0f) weakHits += s
            if (s >= thresholds.text) {
                return Hit(true, s, "text:$term", MatchAccuracy.toPercent(s, TEXT_MIN_EVIDENCE))
            }
        }

        // ---- v3.11.0 新增通路（只在不启用增强时跳过，旧行为完全保留）----
        if (localEnhance) {
            // 2a. 多弱证据叠加：远景小车常只留下几条 0.44~0.50 的弱标签，
            //     单条不够阈值，但多条相关证据合起来足以确信。
            //     次证据按递减系数小权重累加，且要求至少两条，避免单条弱证据误召。
            if (weakHits.size >= 2) {
                val sorted = weakHits.sortedDescending()
                var agg = sorted[0]
                for (i in 1 until minOf(sorted.size, 5)) {
                    agg += sorted[i] * (0.30f - (i - 1) * 0.06f)
                }
                if (agg >= thresholds.text) {
                    return Hit(
                        true, agg.coerceAtMost(1f), "text:multi(${weakHits.size})",
                        MatchAccuracy.toPercent(agg, TEXT_MIN_EVIDENCE)
                    )
                }
            }

            // 2b. 同义词 / 上下位词扩展兜底：照片标签是「车辆」，用户搜「汽车」。
            //     只用于已归一到规范概念的条件，要求更强证据，命中分打折。
            for (term in condition.expandedKeywords) {
                val s = index.score(term)
                if (s >= thresholds.expand) {
                    return Hit(
                        true, s * EXPAND_SCORE_FACTOR, "expand:$term",
                        MatchAccuracy.toPercent(s, EXPAND_MIN_EVIDENCE)
                    )
                }
            }
        }
        return miss()
    }

    /**
     * 严格文本匹配：
     * - 英文用词边界，中文要求整词包含且长度 >= 2
     * - 不做双向 contains、不做字符集合重叠、不做 2 字子串模糊
     */
    private fun strictMatch(keyword: String, metadata: PhotoMetadata): Boolean {
        val kw = keyword.lowercase().trim()
        if (kw.isEmpty()) return false

        val isAscii = STRICT_ASCII.matches(kw)

        fun hit(text: String): Boolean {
            val t = text.lowercase()
            return if (isAscii) {
                // v3.8.0：无正则词边界，等价于旧的 (?<![a-z])kw(?![a-z])
                ConceptRegistry.containsAsciiWord(t, kw)
            } else {
                t == kw || (kw.length >= 2 && t.contains(kw))
            }
        }

        if (metadata.vlmKeywords.any { hit(it) }) return true
        if (kw.length >= 2 && metadata.description.isNotBlank() && hit(metadata.description)) return true
        return metadata.labels.any { it.confidence >= 0.5f && hit(it.text) }
    }

    private fun conditionLabel(condition: SearchCondition): String {
        val label = condition.concept?.name ?: condition.keywords.firstOrNull() ?: ""
        return if (condition.negated) "非$label" else label
    }

    fun quickSearch(
        query: String,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> = search(IntentParser.parse(query), photos, metadataMap, emptyMap())

    fun quickSearch(
        query: String,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>,
        exifMap: Map<Long, PhotoExif>
    ): List<SearchResult> = search(IntentParser.parse(query), photos, metadataMap, exifMap)
}
