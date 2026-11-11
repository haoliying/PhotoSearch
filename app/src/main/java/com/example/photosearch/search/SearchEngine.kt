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
        val matchSource: String = ""
    )

    private data class ConditionEval(
        val condition: SearchCondition,
        val hit: Boolean,        // 原始命中（未考虑 negated）
        val effective: Boolean,  // 考虑 negated 后是否通过
        val score: Float,
        val source: String
    )

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
        localEnhance: Boolean = true
    ): List<SearchResult> {
        if (intent.conditions.isEmpty() && !intent.hasFilters) return emptyList()

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
            if (intent.filtersOnly) {
                return@mapIndexedNotNull SearchResult(
                    photo = photo,
                    score = FILTER_ONLY_SCORE,
                    matchedConditions = filterLabels(intent),
                    matchSource = "filters"
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
                val (hit, score, source) = evaluateCondition(cond, metadata, evidence, docIndex, localEnhance)
                ConditionEval(
                    condition = cond,
                    hit = hit,
                    effective = if (cond.negated) !hit else hit,
                    score = if (cond.negated) 0f else score,
                    source = source
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

            val pass = negativeOk && when (intent.logicOperator) {
                LogicOperator.AND -> evals.all { it.effective }
                // 排除条件只做否决，不参与 OR 命中判定（否则恒为真、全库返回）
                LogicOperator.OR -> positive.any { it.effective } || positive.isEmpty()
                LogicOperator.RANK -> {
                    val minScore = positive.minOfOrNull { it.score } ?: 0f
                    minScore >= RANK_MIN_CONDITION && avgScore >= RANK_THRESHOLD
                }
            }

            if (!pass || avgScore <= 0f) return@mapIndexedNotNull null

            SearchResult(
                photo = photo,
                score = avgScore,
                matchedConditions = intent.conditions.map { conditionLabel(it) } + filterLabels(intent),
                matchSource = evals.maxByOrNull { it.score }?.source ?: ""
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
        localEnhance: Boolean
    ): Triple<Boolean, Float, String> {
        // 通道 1：规范概念（精确率最高）
        val concept = condition.concept
        if (concept != null) {
            val w = evidence[concept] ?: 0f
            if (w >= MIN_EVIDENCE) return Triple(true, w, "concept:${concept.name}")
        }

        // 排除条件：只用严格关键词匹配做否决，不启用全文通道
        if (condition.negated) {
            for (keyword in condition.keywords) {
                if (strictMatch(keyword, metadata)) return Triple(true, 0.6f, "keyword")
            }
            return Triple(false, 0f, "")
        }

        // 通道 2：全文检索（召回词表外的词，含 EXIF 地名/年份）。
        // 原词路径与 v3.10.0 完全一致：按长度降序，第一个达到阈值的即返回。
        val terms = condition.keywords.distinct().sortedByDescending { it.length }
        val weakHits = ArrayList<Float>()
        for (term in terms) {
            val s = index.score(term)
            if (s > 0f) weakHits += s
            if (s >= TEXT_MIN_EVIDENCE) return Triple(true, s, "text:$term")
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
                if (agg >= TEXT_MIN_EVIDENCE) {
                    return Triple(true, agg.coerceAtMost(1f), "text:multi(${weakHits.size})")
                }
            }

            // 2b. 同义词 / 上下位词扩展兜底：照片标签是「车辆」，用户搜「汽车」。
            //     只用于已归一到规范概念的条件，要求更强证据，命中分打折。
            for (term in condition.expandedKeywords) {
                val s = index.score(term)
                if (s >= EXPAND_MIN_EVIDENCE) {
                    return Triple(true, s * EXPAND_SCORE_FACTOR, "expand:$term")
                }
            }
        }
        return Triple(false, 0f, "")
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
