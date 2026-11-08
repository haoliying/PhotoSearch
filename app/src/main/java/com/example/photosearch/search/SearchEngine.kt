package com.example.photosearch.search

import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata

/**
 * 检索引擎（替换版）。
 * 替换路径：app/src/main/java/com/example/photosearch/search/SearchEngine.kt
 *
 * 相对原版的修复：
 * 1. 彻底删除 fuzzyMatch / matchAgainstText / 2 字子串 / 双向 contains。
 *    原版 fuzzyMatch 把 2 字符子串匹配用在英文上：'car' 的 'ca' 命中
 *    'landscape'、'vehicle' 的 'cl' 命中 'cloud'，导致搜「汽车」返回整个相册。
 * 2. 匹配单位由「自由文本」改为「规范概念 + 证据强度」。
 * 3. negation 不再用 (1 - score) 加分；命中排除项直接否决。
 * 4. RANK 阈值从 0.05 提到 0.45，并要求每个正向条件都拿到最低分。
 * 5. 不采信 toSearchableTags() 里的启发式推断标签
 *    （原版会把「海滩 + 人物」直接推断成「光膀子」）。
 */
object SearchEngine {

    private const val MIN_EVIDENCE = 0.5f     // 概念证据强度下限
    private const val RANK_THRESHOLD = 0.45f  // RANK 模式平均分下限
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

        // 一次搜索内缓存概念提取，避免每张照片重复 normalize
        val evidenceMap = HashMap<Long, Map<Concept, Float>>(metadataMap.size)
        metadataMap.forEach { (id, metadata) ->
            evidenceMap[id] = ConceptRegistry.fromMetadata(metadata)
        }

        return photos.mapNotNull { photo ->
            val metadata = metadataMap[photo.id] ?: return@mapNotNull null
            val evidence = evidenceMap[photo.id] ?: emptyMap()

            val evals = intent.conditions.map { cond ->
                val (hit, score, source) = evaluateCondition(cond, metadata, evidence)
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
        evidence: Map<Concept, Float>
    ): Triple<Boolean, Float, String> {
        val concept = condition.concept
        if (concept != null) {
            val w = evidence[concept] ?: 0f
            if (w >= MIN_EVIDENCE) return Triple(true, w, "concept:${concept.name}")
        }

        // 兜底：词表未收录的自定义关键词，使用严格匹配
        var best = 0f
        for (keyword in condition.keywords) {
            if (strictMatch(keyword, metadata)) {
                best = maxOf(best, 0.6f)
                break
            }
        }
        return if (best > 0f) Triple(true, best, "keyword") else Triple(false, 0f, "")
    }

    /**
     * 严格文本匹配：
     * - 英文用词边界，中文要求整词包含且长度 >= 2
     * - 不做双向 contains、不做字符集合重叠、不做 2 字子串模糊
     * - 单字词只匹配 VLM 关键词/标签的完全相等项
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
