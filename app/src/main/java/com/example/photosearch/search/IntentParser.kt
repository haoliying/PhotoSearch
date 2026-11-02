package com.example.photosearch.search

/**
 * Parses natural language search queries into structured SearchIntent objects.
 * Supports Chinese and English input with compound conditions.
 */
object IntentParser {

    // Predefined intent patterns: maps keyword patterns to conditions
    private val intentPatterns = listOf(
        // === Vehicle patterns ===
        IntentPattern(
            triggers = listOf("车", "汽车", "轿车", "卡车", "车辆", "car", "vehicle", "truck", "auto"),
            conditions = listOf(
                SearchCondition(ConditionType.OBJECT, listOf("car", "vehicle", "truck", "automobile", "van", "bus", "wheel", "tire", "motorcycle", "bicycle", "车", "汽车", "车辆", "卡车", "轮胎")),
            ),
            description = "包含车辆的照片"
        ),

        // === Person patterns ===
        IntentPattern(
            triggers = listOf("人", "人物", "有人", "person", "people", "human", "人像", "合影"),
            conditions = listOf(
                SearchCondition(ConditionType.OBJECT, listOf("person", "people", "human", "man", "woman", "child", "portrait", "face", "人", "人物", "人脸", "人像")),
            ),
            description = "包含人物的照片"
        ),

        // === Shirtless patterns ===
        IntentPattern(
            triggers = listOf("光膀子", "赤裸上身", "没穿上衣", "光着上身", "光背", "shirtless", "bare chest", "no shirt", "半裸"),
            conditions = listOf(
                SearchCondition(ConditionType.ATTRIBUTE, listOf("person", "people", "man", "human", "body", "人", "人物", "男人"), weight = 1.0f),
                SearchCondition(ConditionType.ATTRIBUTE, listOf("光膀子", "赤裸上身", "shirtless", "bare chest", "body", "skin", "chest", "torso", "muscle", "身体", "皮肤", "胸", "肌肉"), weight = 0.8f),
                SearchCondition(ConditionType.ATTRIBUTE, listOf("beach", "sea", "swimming", "sport", "exercise", "海滩", "游泳", "运动"), weight = 0.5f),
                SearchCondition(ConditionType.ATTRIBUTE, listOf("shirt", "jacket", "coat", "sweater", "formal", "suit", "long sleeve", "衬衫", "夹克", "外套", "毛衣", "正装", "西装"), negated = true, weight = 1.5f),
            ),
            logicOperator = LogicOperator.RANK,
            description = "光膀子/赤裸上身的人"
        ),

        // === Mountain patterns ===
        IntentPattern(
            triggers = listOf("山", "泰山", "登山", "爬山", "mountain", "hill", "peak", "hiking", "climbing", "山峰", "山顶"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("mountain", "hill", "peak", "ridge", "cliff", "rock", "valley", "landscape", "hiking", "山", "泰山", "山峰", "登山", "爬山", "山谷", "岩石", "风景")),
            ),
            description = "山景/登山/泰山照片"
        ),

        // === Beach patterns ===
        IntentPattern(
            triggers = listOf("海滩", "海边", "沙滩", "大海", "beach", "sea", "ocean", "coast", "shore", "sand", "wave"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("beach", "sand", "sea", "ocean", "water", "coast", "shore", "wave", "palm", "swimming", "海滩", "沙滩", "大海", "海边", "海浪", "游泳", "棕榈")),
            ),
            description = "海滩/海边照片"
        ),

        // === Pet/Animal patterns ===
        IntentPattern(
            triggers = listOf("宠物", "猫", "狗", "动物", "pet", "cat", "dog", "animal", "kitten", "puppy", "bird"),
            conditions = listOf(
                SearchCondition(ConditionType.OBJECT, listOf("cat", "dog", "pet", "animal", "kitten", "puppy", "bird", "fish", "猫", "狗", "宠物", "动物", "小猫", "小狗", "鸟")),
            ),
            description = "宠物/动物照片"
        ),

        // === Food patterns ===
        IntentPattern(
            triggers = listOf("美食", "食物", "吃的", "饭菜", "food", "meal", "dish", "cuisine", "cooking", "restaurant", "fruit", "dessert"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("food", "meal", "dish", "cuisine", "restaurant", "fruit", "vegetable", "dessert", "drink", "cooking", "美食", "食物", "菜", "水果", "甜点", "饮料")),
            ),
            description = "美食/食物照片"
        ),

        // === Building/Architecture patterns ===
        IntentPattern(
            triggers = listOf("建筑", "房子", "大楼", "building", "house", "architecture", "tower", "bridge"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("building", "house", "architecture", "structure", "tower", "bridge", "church", "temple", "建筑", "房子", "大楼", "塔", "桥")),
            ),
            description = "建筑照片"
        ),

        // === Nature/Landscape patterns ===
        IntentPattern(
            triggers = listOf("风景", "自然", "景色", "landscape", "nature", "scenery", "view", "森林", "花园"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("landscape", "nature", "scenery", "forest", "tree", "flower", "garden", "park", "sunset", "sky", "grass", "风景", "自然", "景色", "森林", "树", "花", "花园", "公园", "日落", "天空", "草地")),
            ),
            description = "自然风景照片"
        ),

        // === Night patterns ===
        IntentPattern(
            triggers = listOf("夜景", "夜晚", "晚上", "night", "dark", "evening", "霓虹"),
            conditions = listOf(
                SearchCondition(ConditionType.TIME, listOf("night", "dark", "evening", "light", "neon", "夜景", "夜晚", "晚上", "黑暗", "霓虹灯", "灯光")),
            ),
            description = "夜景照片"
        ),

        // === Sport patterns ===
        IntentPattern(
            triggers = listOf("运动", "跑步", "健身", "sport", "exercise", "running", "fitness", "gym", "篮球", "足球"),
            conditions = listOf(
                SearchCondition(ConditionType.ACTION, listOf("sport", "exercise", "running", "fitness", "gym", "football", "basketball", "swimming", "yoga", "运动", "跑步", "健身", "足球", "篮球", "游泳", "瑜伽")),
            ),
            description = "运动照片"
        ),

        // === Flower/Plant patterns ===
        IntentPattern(
            triggers = listOf("花", "植物", "树", "flower", "plant", "tree", "garden", "leaf", "rose"),
            conditions = listOf(
                SearchCondition(ConditionType.OBJECT, listOf("flower", "plant", "tree", "garden", "leaf", "rose", "grass", "花", "植物", "树", "花园", "叶子", "玫瑰", "草")),
            ),
            description = "花卉植物照片"
        ),

        // === Smile/Happy patterns ===
        IntentPattern(
            triggers = listOf("笑", "微笑", "开心", "smile", "happy", "laugh", "高兴"),
            conditions = listOf(
                SearchCondition(ConditionType.ATTRIBUTE, listOf("smile", "happy", "laugh", "笑", "微笑", "开心", "高兴")),
                SearchCondition(ConditionType.OBJECT, listOf("person", "people", "face", "人", "人物", "脸")),
            ),
            description = "微笑/开心的照片"
        ),

        // === Group/Many people patterns ===
        IntentPattern(
            triggers = listOf("合影", "合照", "一群人", "多人", "group", "crowd", "many people", "集体"),
            conditions = listOf(
                SearchCondition(ConditionType.COUNT, listOf("group", "crowd", "many", "multiple", "people", "合影", "合照", "一群", "多人", "集体")),
                SearchCondition(ConditionType.OBJECT, listOf("person", "people", "人", "人物")),
            ),
            description = "多人合影照片"
        ),

        // === Selfie patterns ===
        IntentPattern(
            triggers = listOf("自拍", "selfie", "自拍照"),
            conditions = listOf(
                SearchCondition(ConditionType.ATTRIBUTE, listOf("selfie", "自拍", "front camera")),
                SearchCondition(ConditionType.OBJECT, listOf("person", "face", "人", "脸")),
            ),
            description = "自拍照片"
        ),

        // === Travel patterns ===
        IntentPattern(
            triggers = listOf("旅游", "旅行", "travel", "trip", "vacation", "度假", "出游"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("travel", "trip", "vacation", "tourism", "旅游", "旅行", "度假", "出游")),
                SearchCondition(ConditionType.SCENE, listOf("landscape", "scenery", "beach", "mountain", "building", "风景", "海滩", "山", "建筑"), weight = 0.6f),
            ),
            description = "旅游照片"
        ),

        // === Baby/Child patterns ===
        IntentPattern(
            triggers = listOf("宝宝", "婴儿", "小孩", "孩子", "baby", "infant", "child", "kid", "toddler"),
            conditions = listOf(
                SearchCondition(ConditionType.OBJECT, listOf("baby", "infant", "child", "kid", "toddler", "宝宝", "婴儿", "小孩", "孩子")),
            ),
            description = "宝宝/小孩照片"
        ),

        // === Sunset/Sunrise patterns ===
        IntentPattern(
            triggers = listOf("日落", "夕阳", "日出", "朝阳", "sunset", "sunrise", "dawn", "dusk"),
            conditions = listOf(
                SearchCondition(ConditionType.TIME, listOf("sunset", "sunrise", "dawn", "dusk", "日落", "夕阳", "日出", "朝阳")),
                SearchCondition(ConditionType.SCENE, listOf("sky", "cloud", "天空", "云"), weight = 0.5f),
            ),
            description = "日出/日落照片"
        ),

        // === Snow patterns ===
        IntentPattern(
            triggers = listOf("雪", "雪景", "下雪", "snow", "snowy", "winter"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("snow", "snowy", "winter", "ice", "雪", "雪景", "下雪", "冰", "冬天")),
            ),
            description = "雪景照片"
        ),

        // === Wedding patterns ===
        IntentPattern(
            triggers = listOf("婚礼", "结婚", "wedding", "bride", "groom", "婚纱"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("wedding", "bride", "groom", "marriage", "婚礼", "结婚", "婚纱", "新娘", "新郎")),
            ),
            description = "婚礼照片"
        ),

        // === Indoor patterns ===
        IntentPattern(
            triggers = listOf("室内", "家里", "房间", "indoor", "inside", "room", "home"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("indoor", "room", "home", "house", "furniture", "室内", "家里", "房间", "家具")),
            ),
            description = "室内照片"
        ),

        // === Outdoor patterns ===
        IntentPattern(
            triggers = listOf("户外", "室外", "outdoor", "outside"),
            conditions = listOf(
                SearchCondition(ConditionType.SCENE, listOf("outdoor", "outside", "nature", "sky", "户外", "室外", "自然", "天空")),
            ),
            description = "户外照片"
        )
    )

    data class IntentPattern(
        val triggers: List<String>,
        val conditions: List<SearchCondition>,
        val logicOperator: LogicOperator = LogicOperator.AND,
        val description: String = ""
    )

    /**
     * Parse a natural language query into a SearchIntent.
     */
    fun parse(query: String): SearchIntent {
        val normalizedQuery = query.trim().lowercase()

        if (normalizedQuery.isBlank()) {
            return SearchIntent(query, emptyList())
        }

        // Check for compound queries with AND/OR
        val andParts = splitByOperators(normalizedQuery, listOf("和", "与", "and", "&", "，", ","))
        val orParts = splitByOperators(normalizedQuery, listOf("或", "or", "|"))

        // If we have OR operators, split by OR and parse each part
        if (orParts.size > 1) {
            val subIntents = orParts.map { parse(it.trim()) }
            val allConditions = subIntents.flatMap { it.conditions }
            return SearchIntent(
                rawQuery = query,
                conditions = allConditions,
                logicOperator = LogicOperator.OR
            )
        }

        // If we have AND operators, split and parse each part
        if (andParts.size > 1) {
            val subIntents = andParts.map { parse(it.trim()) }
            val allConditions = subIntents.flatMap { it.conditions }
            return SearchIntent(
                rawQuery = query,
                conditions = allConditions,
                logicOperator = LogicOperator.AND
            )
        }

        // Check for NOT patterns
        val (positiveQuery, negations) = extractNegations(normalizedQuery)

        // Try to match against predefined patterns
        val matchedPatterns = intentPatterns.filter { pattern ->
            pattern.triggers.any { trigger -> positiveQuery.contains(trigger) }
        }

        if (matchedPatterns.isNotEmpty()) {
            // Use the best matching pattern (most specific = most trigger matches)
            val bestPattern = matchedPatterns.maxByOrNull { pattern ->
                pattern.triggers.count { trigger -> positiveQuery.contains(trigger) }
            }!!

            val conditions = bestPattern.conditions.toMutableList()

            // Add negation conditions
            negations.forEach { neg ->
                val negPattern = intentPatterns.find { p ->
                    p.triggers.any { t -> neg.contains(t) }
                }
                if (negPattern != null) {
                    negPattern.conditions.forEach { cond ->
                        conditions.add(cond.copy(negated = true))
                    }
                } else {
                    conditions.add(SearchCondition(
                        type = ConditionType.ATTRIBUTE,
                        keywords = listOf(neg),
                        negated = true
                    ))
               }
            }

            return SearchIntent(
                rawQuery = query,
                conditions = conditions,
                logicOperator = bestPattern.logicOperator,
                confidence = 0.9f
            )
        }

        // Fallback: flexible keyword matching
        val keywords = tokenize(normalizedQuery)
        if (keywords.isNotEmpty()) {
            return SearchIntent(
                rawQuery = query,
                conditions = listOf(
                    SearchCondition(
                        type = ConditionType.COMPOSITE,
                        keywords = keywords,
                        minConfidence = 0.2f,
                        weight = 1.0f
                    )
                ),
                logicOperator = LogicOperator.RANK,
                confidence = 0.5f
            )
        }

        return SearchIntent(query, emptyList())
    }

    private fun splitByOperators(query: String, operators: List<String>): List<String> {
        var result = listOf(query)
        for (op in operators) {
            result = result.flatMap { part ->
                part.split(Regex("\\s*${Regex.escape(op)}\\s*"))
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun extractNegations(query: String): Pair<String, List<String>> {
        val negations = mutableListOf<String>()
        var positiveQuery = query

        // Chinese negation patterns
        val negPatterns = listOf(
            Regex("没有(.+?)(?=和|与|,|，|$)"),
            Regex("不要(.+?)(?=和|与|,|，|$)"),
            Regex("不含(.+?)(?=和|与|,|，|$)"),
            Regex("excluding\\s+(.+?)(?=and|,|$)"),
            Regex("no\\s+(.+?)(?=and|,|$)"),
            Regex("without\\s+(.+?)(?=and|,|$)")
        )

        for (pattern in negPatterns) {
            val matches = pattern.findAll(positiveQuery)
            matches.forEach { match ->
                negations.add(match.groupValues[1].trim())
            }
            positiveQuery = pattern.replace(positiveQuery, "").trim()
        }

        return positiveQuery to negations
    }

    private fun tokenize(query: String): List<String> {
        // Split by spaces, commas, etc. and filter short tokens
        return query.split(Regex("[\\s,，、;；!！?？]+"))
            .map { it.trim() }
            .filter { it.length >= 1 }
    }

    /**
     * Get description for a matched pattern.
     */
    fun getDescription(query: String): String? {
        val normalizedQuery = query.trim().lowercase()
        val matched = intentPatterns.filter { pattern ->
            pattern.triggers.any { trigger -> normalizedQuery.contains(trigger) }
        }
        return matched.firstOrNull()?.description
    }

    /**
     * Get search suggestions.
     */
    fun getSuggestions(): List<String> {
        return listOf(
            "有车", "人物", "光膀子", "泰山", "海滩",
            "宠物", "美食", "建筑", "风景", "夜景",
            "运动", "花卉", "自拍", "合影", "旅游",
            "日落", "雪景", "婚礼", "宝宝", "室内"
        )
    }
}
