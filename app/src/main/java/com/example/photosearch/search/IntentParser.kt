package com.example.photosearch.search

/**
 * 查询解析器。
 *
 * v3.5.2 修复召回率：
 * 1. 每个条件都带上该片段的查询词元（不再只给词表外的兜底条件填 keywords），
 *    这样概念条件在概念通道未命中时也能走全文通道兜底。
 * 2. 中文查询改用 ConceptRegistry.tokenizeQuery 切词（去停用词 + 2~4 gram），
 *    修复「一杯咖啡」「有汽车的照片」被当成一整个关键词导致 0 结果的问题。
 * 3. 词表外的词不再被静默丢弃。
 */
object IntentParser {

    private val OR_SPLIT = Regex("""\s*(?:或者|或|\bor\b|\|)\s*""")
    private val AND_SPLIT = Regex("""\s*(?:并且|和|与|\band\b|&|，|,)\s*""")

    private val NEG_PATTERNS = listOf(
        Regex("""(?:没有|不含|不要|排除)\s*(.+?)(?=和|与|或者|或|,|，|$)"""),
        Regex("""(?:without|excluding|no)\s+(.+?)(?=\band\b|,|$)""", RegexOption.IGNORE_CASE)
    )

    fun parse(query: String): SearchIntent {
        val raw = query.trim()
        if (raw.isBlank()) return SearchIntent(query, emptyList())

        val (positive, negations) = extractNegations(raw)

        val orParts = positive.split(OR_SPLIT).map { it.trim() }.filter { it.isNotBlank() }
        val isOr = orParts.size > 1
        val parts = if (isOr) {
            orParts
        } else {
            positive.split(AND_SPLIT).map { it.trim() }.filter { it.isNotBlank() }
        }

        val conditions = mutableListOf<SearchCondition>()
        for (part in parts) {
            val terms = ConceptRegistry.tokenizeQuery(part)
            val concepts = ConceptRegistry.normalize(part)
            if (concepts.isNotEmpty()) {
                concepts.forEach { c ->
                    conditions += SearchCondition(
                        type = typeFor(c),
                        concept = c,
                        keywords = terms
                    )
                }
            } else if (terms.isNotEmpty()) {
                conditions += SearchCondition(type = ConditionType.COMPOSITE, keywords = terms)
            }
        }

        negations.forEach { neg ->
            val concepts = ConceptRegistry.normalize(neg)
            if (concepts.isEmpty()) {
                conditions += SearchCondition(
                    type = ConditionType.COMPOSITE,
                    keywords = listOf(neg),
                    negated = true
                )
            } else {
                concepts.forEach { c ->
                    conditions += SearchCondition(type = typeFor(c), concept = c, negated = true)
                }
            }
        }

        if (conditions.isEmpty()) return SearchIntent(query, emptyList())

        val hasFallback = conditions.any { it.concept == null && !it.negated }
        val logic = when {
            isOr -> LogicOperator.OR
            hasFallback -> LogicOperator.RANK
            else -> LogicOperator.AND
        }
        val confidence = if (hasFallback) 0.5f else 0.9f

        return SearchIntent(query, conditions, logic, confidence)
    }

    private fun extractNegations(query: String): Pair<String, List<String>> {
        val negations = mutableListOf<String>()
        var positive = query
        for (pattern in NEG_PATTERNS) {
            pattern.findAll(positive).forEach { m ->
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty()) negations += v
            }
            positive = pattern.replace(positive, " ").trim()
        }
        return positive to negations
    }

    private fun typeFor(c: Concept): ConditionType = when (c) {
        Concept.CAR, Concept.BICYCLE, Concept.BUS, Concept.CAT, Concept.DOG,
        Concept.FLOWER, Concept.FOREST, Concept.FOOD, Concept.BUILDING,
        Concept.BABY, Concept.PERSON, Concept.TEXT_CONTENT -> ConditionType.OBJECT
        Concept.BEACH, Concept.MOUNTAIN, Concept.MOUNT_TAI, Concept.INDOOR,
        Concept.WEDDING -> ConditionType.SCENE
        Concept.SHIRTLESS, Concept.SMILE, Concept.SELFIE, Concept.GROUP -> ConditionType.ATTRIBUTE
        Concept.NIGHT, Concept.SUNSET, Concept.SUNRISE, Concept.SNOW -> ConditionType.TIME
        Concept.SPORT -> ConditionType.ACTION
    }

    fun getDescription(query: String): String? {
        val positive = extractNegations(query.trim()).first
        val concepts = ConceptRegistry.normalize(positive)
        if (concepts.isNotEmpty()) return ConceptRegistry.descriptionOf(concepts)
        val terms = ConceptRegistry.tokenizeQuery(positive)
            .filter { it.length >= 2 }
            .sortedByDescending { it.length }
            .take(3)
        return if (terms.isEmpty()) null else "关键词匹配: ${terms.joinToString("、")}"
    }

    fun getSuggestions(): List<String> = listOf(
        "有车", "人物", "光膀子", "泰山", "海滩", "宠物", "美食", "建筑",
        "自行车", "咖啡", "笔记本电脑", "书", "夜景", "运动", "花卉",
        "自拍", "合影", "日落", "雪景", "婚礼", "宝宝", "室内", "气球"
    )
}
