package com.example.photosearch.search

import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata

/**
 * 检索引擎。
 *
 * v3.5.0 修复精确率：删除 fuzzyMatch / 2 字子串 / 双向 contains / 排除条件反向加分。
 * v3.5.2 修复召回率：在概念通道之外增加「全文通道」，把 VLM 描述/关键词、
 * ML Kit 标签（含中文翻译）、对象检测、场景、人脸等信号编入索引，用完整词
 * 匹配覆盖词表外的任意查询词；同时不再丢弃没有分析结果的照片。
 *
 * 全文通道仍然严格：不做子串模糊、不做双向 contains、中文词元 >= 2 字、
 * 英文词元 >= 3 字母且词边界、长词优先命中即停止。
 */
object SearchEngine {

    private const val MIN_EVIDENCE = 0.5f       // 概念证据强度下限
    private const val TEXT_MIN_EVIDENCE = 0.55f // 全文通道证据强度下限
    private const val RANK_THRESHOLD = 0.45f    // RANK 模式平均分下限
    private const val RANK_MIN_CONDITION = 0.2f

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

    fun search(
        intent: SearchIntent,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> {
        if (intent.conditions.isEmpty()) return emptyList()

        return photos.mapNotNull { photo ->
            // 修复：分析失败/尚未分析的照片不再直接丢弃，至少用 PhotoItem 的标签参与检索
            val metadata = metadataMap[photo.id] ?: PhotoMetadata(
                photoId = photo.id,
                labels = photo.labels,
                isAnalyzed = false
            )
            val evidence = ConceptRegistry.fromMetadata(metadata)
            val index = ConceptRegistry.buildSearchIndex(metadata)

            val evals = intent.conditions.map { cond ->
                val (hit, score, source) = evaluateCondition(cond, metadata, evidence, index)
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

            if (!pass || avgScore <= 0f) return@mapNotNull null

            SearchResult(
                photo = photo,
                score = avgScore,
                matchedConditions = intent.conditions.map { conditionLabel(it) },
                matchSource = evals.maxByOrNull { it.score }?.source ?: ""
            )
        }.sortedByDescending { it.score }
    }

    private fun evaluateCondition(
        condition: SearchCondition,
        metadata: PhotoMetadata,
        evidence: Map<Concept, Float>,
        index: ConceptRegistry.SearchIndex
    ): Triple<Boolean, Float, String> {
        // 通道 1：规范概念（精确率最高）
        val concept = condition.concept
        if (concept != null) {
            val w = evidence[concept] ?: 0f
            if (w >= MIN_EVIDENCE) return Triple(true, w, "concept:${concept.name}")
        }

        // 排除条件：只用严格关键词匹配做否决，不启用全文通道
        // （索引已 scrub 掉否定短语，避免「画面中没有汽车」被误判为含汽车）
        if (condition.negated) {
            for (keyword in condition.keywords) {
                if (strictMatch(keyword, metadata)) return Triple(true, 0.6f, "keyword")
            }
            return Triple(false, 0f, "")
        }

        // 通道 2：全文检索（召回词表外的词）。
        // 只用本条件自己的词元，避免多条件查询时串味；长词优先、命中即停。
        val terms = condition.keywords.distinct().sortedByDescending { it.length }
        for (term in terms) {
            val s = index.score(term)
            if (s >= TEXT_MIN_EVIDENCE) return Triple(true, s, "text:$term")
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

        val isAscii = Regex("^[a-z0-9 -]+$").matches(kw)
        val wordPattern = if (isAscii) Regex("(?<![a-z])${Regex.escape(kw)}(?![a-z])") else null

        fun hit(text: String): Boolean {
            val t = text.lowercase()
            return if (wordPattern != null) {
                wordPattern.containsMatchIn(t)
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
    ): List<SearchResult> = search(IntentParser.parse(query), photos, metadataMap)
}
