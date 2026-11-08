package com.example.photosearch.search

/**
 * 查询解析器（替换版）。
 * 替换路径：app/src/main/java/com/example/photosearch/search/IntentParser.kt
 *
 * 相对原版的修复：
 * 1. 删除 24 个硬编码 IntentPattern（触发词 contains 单字「车」「人」会串味）。
 * 2. 英文 or / and 改为词边界 \bor\b / \band\b，
 *    修复「portrait」「forest」被拆成 "p"+"trait"、"f"+"est" 的致命 bug。
 * 3. 查询直接规范化为概念 ID，中英文同义词统一处理。
 * 4. 描述文案与实际使用的概念保持一致（原版 getDescription 取 first，
 *    parse 取 maxByOrNull，两者可能不一致）。
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
            val concepts = ConceptRegistry.normalize(part)
            if (concepts.isNotEmpty()) {
                concepts.forEach { c ->
                    conditions += SearchCondition(type = typeFor(c), concept = c)
                }
            } else {
                val words = tokenize(part)
                if (words.isNotEmpty()) {
                    conditions += SearchCondition(type = ConditionType.COMPOSITE, keywords = words)
                }
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

        val hasFallback = conditions.any { it.type == ConditionType.COMPOSITE && !it.negated }
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

    private fun tokenize(text: String): List<String> =
        text.split(Regex("""[\s,，、;；!！?？]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
        return if (concepts.isEmpty()) null else ConceptRegistry.descriptionOf(concepts)
    }

    fun getSuggestions(): List<String> = listOf(
        "有车", "人物", "光膀子", "泰山", "海滩", "宠物", "美食", "建筑",
        "风景", "夜景", "运动", "花卉", "自拍", "合影", "旅游", "日落",
        "雪景", "婚礼", "宝宝", "室内"
    )
}
