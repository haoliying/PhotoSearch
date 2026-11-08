package com.example.photosearch.search

/**
 * 查询解析器。
 *
 * v3.5.2：中文切词、每个条件带查询词元、词表外词不丢弃。
 * v3.6.0：先从查询里剥离时间 / 地点短语，生成结构化 TimeRange / LocationQuery；
 *         剩余文本再做内容检索。三者按 AND 联动：
 *         「2023年在北京拍的汽车」= 2023 年 AND 北京 AND 含汽车。
 *         只有时间/地点、没有内容词时，所有通过硬过滤的照片都返回。
 */
object IntentParser {

    private val OR_SPLIT = Regex("""\s*(?:或者|或|\bor\b|\|)\s*""")
    private val AND_SPLIT = Regex("""\s*(?:并且|和|与|\band\b|&|，|,)\s*""")

    private val NEG_PATTERNS = listOf(
        Regex("""(?:没有|不含|不要|排除)\s*(.+?)(?=和|与|或者|或|,|，|$)"""),
        Regex("""(?:without|excluding|no)\s+(.+?)(?=\band\b|,|$)""", RegexOption.IGNORE_CASE)
    )

    /**
     * @param knownPlaces 当前时间区间内实际存在的地点标签，用于识别非内置城市
     *                    （Geocoder 结果、用户手动标注）。
     */
    fun parse(
        query: String,
        knownPlaces: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): SearchIntent {
        val raw = query.trim()
        if (raw.isBlank()) return SearchIntent(query, emptyList())

        // 1) 时间短语
        val (timeRange, afterTime) = TimeQueryParser.parse(raw, nowMillis)
        // 2) 地点短语
        val (locationQuery, afterLocation) = LocationMatcher.extract(afterTime, knownPlaces)
        // 3) 剩余文本做内容检索
        val content = LocationMatcher.cleanup(afterLocation)

        val (positive, negations) = extractNegations(content)

        val orParts = positive.split(OR_SPLIT).map { it.trim() }.filter { it.isNotBlank() }
        val isOr = orParts.size > 1
        val parts = if (isOr) orParts else positive.split(AND_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

        val conditions = mutableListOf<SearchCondition>()
        for (part in parts) {
            val terms = ConceptRegistry.tokenizeQuery(part)
            val concepts = ConceptRegistry.normalize(part)
            if (concepts.isNotEmpty()) {
                concepts.forEach { c ->
                    conditions += SearchCondition(type = typeFor(c), concept = c, keywords = terms)
                }
            } else if (terms.isNotEmpty()) {
                conditions += SearchCondition(type = ConditionType.COMPOSITE, keywords = terms)
            }
        }

        negations.forEach { neg ->
            val concepts = ConceptRegistry.normalize(neg)
            if (concepts.isEmpty()) {
                conditions += SearchCondition(
                    type = ConditionType.COMPOSITE, keywords = listOf(neg), negated = true
                )
            } else {
                concepts.forEach { c ->
                    conditions += SearchCondition(type = typeFor(c), concept = c, negated = true)
                }
            }
        }

        // 只有筛选器、没有内容条件：交给 SearchEngine 的 filtersOnly 分支
        if (conditions.isEmpty()) {
            return SearchIntent(
                rawQuery = query,
                conditions = emptyList(),
                timeRange = timeRange,
                locationQuery = locationQuery
            )
        }

        val hasFallback = conditions.any { it.concept == null && !it.negated }
        val logic = when {
            isOr -> LogicOperator.OR
            hasFallback -> LogicOperator.RANK
            else -> LogicOperator.AND
        }
        val confidence = if (hasFallback) 0.5f else 0.9f

        return SearchIntent(query, conditions, logic, confidence, timeRange, locationQuery)
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
        val (time, afterTime) = TimeQueryParser.parse(query.trim())
        val (loc, afterLoc) = LocationMatcher.extract(afterTime)
        val pieces = mutableListOf<String>()
        if (time != null) pieces += "时间 ${time.label.ifBlank { TimeQueryParser.format(time) }}"
        if (loc != null) pieces += "地点 ${loc.raw}"

        val positive = extractNegations(LocationMatcher.cleanup(afterLoc)).first
        val concepts = ConceptRegistry.normalize(positive)
        if (concepts.isNotEmpty()) {
            pieces += ConceptRegistry.descriptionOf(concepts)
        } else {
            val terms = ConceptRegistry.tokenizeQuery(positive)
                .filter { it.length >= 2 }
                .sortedByDescending { it.length }
                .take(3)
            if (terms.isNotEmpty()) pieces += "关键词 ${terms.joinToString("、")}"
        }
        return if (pieces.isEmpty()) null else pieces.joinToString(" + ")
    }

    fun getSuggestions(): List<String> = listOf(
        "有车", "人物", "光膀子", "泰山", "海滩", "宠物", "美食", "建筑",
        "自行车", "咖啡", "笔记本电脑", "书", "夜景", "运动", "花卉",
        "自拍", "合影", "日落", "雪景", "婚礼", "宝宝", "室内", "气球",
        "2023年", "去年", "最近三个月", "北京", "上海", "2023年在北京拍的汽车"
    )
}
