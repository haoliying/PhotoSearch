package com.example.photosearch.search

import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.SceneType

/**
 * 规范化概念词表。
 *
 * 设计目标：检索只比较「概念 ID」，不再对自由文本做子串 / 双向 contains /
 * 字符集合重叠这类模糊匹配。中英文同义词、易混淆组合词、否定句、不确定表述
 * 都在这里集中处理，避免散落在 IntentParser 和 SearchEngine 里。
 *
 * 新增文件，放置路径：app/src/main/java/com/example/photosearch/search/ConceptRegistry.kt
 */
enum class Concept {
    CAR, BICYCLE, BUS, BEACH, MOUNTAIN, MOUNT_TAI, PERSON, SHIRTLESS, SMILE,
    FLOWER, FOREST, FOOD, CAT, DOG, NIGHT, SUNSET, SUNRISE, BUILDING, INDOOR,
    WEDDING, SELFIE, GROUP, SNOW, SPORT, BABY, TEXT_CONTENT
}

object ConceptRegistry {

    /**
     * 易混淆组合词：必须先从文本中剔除，否则单字「车」会把自行车 / 车库 /
     * 火车站 / 停车场 / 汽车模型全部误判成汽车。按长度降序替换。
     */
    private val NEGATIVE_SURFACES: List<String> = listOf(
        "自行车", "摩托车", "电动车", "火车站", "停车场", "汽车模型", "玩具车",
        "车库", "车站", "车票", "车轮", "轮胎", "车模", "堵车", "车间",
        "修车", "车流", "站台", "bicycle", "bike", "garage", "parking",
        "train station", "toy car", "model car"
    ).sortedByDescending { it.length }

    private val NEGATION_PREFIXES = listOf("没有", "无", "不含", "看不到", "并非", "不是", "未出现")
    private val NEGATION_SUFFIXES = listOf("也没有", "都没有", "均没有", "并不存在")
    private val UNCERTAIN_PREFIXES = listOf(
        "看起来像", "疑似", "可能是", "似乎是", "仿佛", "有点像", "像是", "好像是"
    )

    /** 概念 -> 中英文表层词。英文用词边界匹配，中文用包含匹配（最长优先）。 */
    private val SURFACES: Map<Concept, List<String>> = mapOf(
        Concept.CAR to listOf(
            "汽车", "轿车", "小汽车", "私家车", "SUV", "suv", "跑车", "卡车", "货车",
            "公交车", "大巴", "机动车", "车辆", "车",
            "car", "sedan", "automobile", "vehicle", "truck", "van", "sports utility vehicle"
        ),
        Concept.BICYCLE to listOf("自行车", "单车", "脚踏车", "bicycle", "bike"),
        Concept.BUS to listOf("公交车", "大巴", "巴士", "bus"),
        Concept.BEACH to listOf("海滩", "沙滩", "海边", "海岸", "海滨", "beach", "seaside", "shore", "sand", "coast"),
        Concept.MOUNTAIN to listOf("山", "山峰", "山顶", "山丘", "山坡", "登山", "爬山", "mountain", "hill", "peak", "hiking"),
        Concept.MOUNT_TAI to listOf("泰山", "玉皇顶", "mount tai"),
        Concept.PERSON to listOf(
            "人", "人物", "男人", "女人", "行人", "合影", "合照", "人像", "肖像", "自拍",
            "person", "people", "man", "woman", "human", "portrait", "face", "selfie"
        ),
        Concept.SHIRTLESS to listOf(
            "光膀子", "赤裸上身", "没穿上衣", "光着上身", "赤膊", "裸露上身",
            "shirtless", "bare chest", "no shirt", "shirtless man"
        ),
        Concept.SMILE to listOf("微笑", "笑容", "笑脸", "开心", "高兴", "smile", "smiling", "happy", "laugh"),
        Concept.FLOWER to listOf("花", "花朵", "樱花", "野花", "flower", "blossom", "rose"),
        Concept.FOREST to listOf("森林", "树林", "forest", "woods"),
        Concept.FOOD to listOf("美食", "食物", "饭菜", "菜", "水果", "甜点", "food", "meal", "dish", "cuisine", "fruit", "dessert"),
        Concept.CAT to listOf("猫", "猫咪", "cat", "kitten"),
        Concept.DOG to listOf("狗", "小狗", "dog", "puppy"),
        Concept.NIGHT to listOf("夜景", "夜晚", "霓虹灯", "night", "neon", "dark"),
        Concept.SUNSET to listOf("日落", "夕阳", "sunset", "dusk"),
        Concept.SUNRISE to listOf("日出", "朝阳", "sunrise", "dawn"),
        Concept.BUILDING to listOf("建筑", "大楼", "房子", "桥", "塔", "building", "house", "architecture", "tower", "bridge"),
        Concept.INDOOR to listOf("室内", "家里", "房间", "indoor", "room"),
        Concept.WEDDING to listOf("婚礼", "结婚", "新娘", "新郎", "wedding", "bride", "groom"),
        Concept.SELFIE to listOf("自拍", "selfie", "front camera"),
        Concept.GROUP to listOf("合影", "合照", "一群人", "多人", "group", "crowd"),
        Concept.SNOW to listOf("雪", "雪景", "下雪", "snow", "snowy"),
        Concept.SPORT to listOf("运动", "跑步", "健身", "游泳", "篮球", "足球", "sport", "exercise", "running", "fitness", "swimming"),
        Concept.BABY to listOf("宝宝", "婴儿", "小孩", "孩子", "baby", "infant", "child", "kid"),
        Concept.TEXT_CONTENT to listOf("文字", "截图", "文档", "text", "document", "screenshot", "sign")
    )

    private val ASCII_WORD = Regex("^[a-z0-9 ]+$")

    /** 把一段自由文本映射为规范概念集合。 */
    fun normalize(text: String): Set<Concept> {
        if (text.isBlank()) return emptySet()

        var t = text.lowercase()
        for (w in NEGATIVE_SURFACES) {
            t = t.replace(w.lowercase(), " ")
        }

        val found = mutableSetOf<Concept>()
        for ((concept, surfaces) in SURFACES) {
            for (s in surfaces.sortedByDescending { it.length }) {
                val sl = s.lowercase()
                val hit = if (ASCII_WORD.matches(sl)) {
                    Regex("(?<![a-z])${Regex.escape(sl)}(?![a-z])").containsMatchIn(t)
                } else {
                    t.contains(sl)
                }
                if (hit) {
                    found.add(concept)
                    break
                }
            }
        }

        // 否定句 / 不确定表述抑制：解决「画面中没有汽车」「看起来像泰山」这类误判
        val original = text.lowercase()
        for ((concept, surfaces) in SURFACES) {
            for (s in surfaces) {
                val sl = s.lowercase()
                val negated = NEGATION_PREFIXES.any { original.contains(it + sl) || original.contains("$it $sl") } ||
                        NEGATION_SUFFIXES.any { original.contains(sl + it) }
                val uncertain = UNCERTAIN_PREFIXES.any { original.contains(it + sl) }
                if (negated || uncertain) found.remove(concept)
            }
        }
        return found
    }

    /**
     * 从照片元数据提取「概念 -> 证据强度」。
     * 证据强度分档：
     *   VLM 结构化关键词 1.00 > VLM 描述 0.90 > 人脸等结构化信号 0.80
     *   > ML Kit 标签 0.45~0.80 > sceneType 弱信号 0.55
     * sceneType 只作弱证据，绝不能单独决定命中（旧版 toSearchableTags 会
     * 直接把「海滩 + 人物」推断为「光膀子」，这里已彻底移除该推断）。
     */
    fun fromMetadata(m: PhotoMetadata): Map<Concept, Float> {
        val evidence = mutableMapOf<Concept, Float>()

        fun add(concept: Concept, weight: Float) {
            if (weight > (evidence[concept] ?: 0f)) evidence[concept] = weight
        }

        // 1. VLM 结构化关键词（最可靠）
        m.vlmKeywords.flatMap { normalize(it) }.forEach { add(it, 1.0f) }

        // 2. VLM 描述（已过滤否定与不确定表述）
        normalize(m.description).forEach { add(it, 0.9f) }

        // 3. ML Kit 标签：低于 0.45 的噪声标签直接丢弃
        m.labels.filter { it.confidence >= 0.45f }.forEach { label ->
            val w = 0.45f + 0.35f * label.confidence
            normalize(label.text).forEach { add(it, w) }
        }

        // 4. 结构化信号
        if (m.faces.any { it.smilingProbability > 0.7f }) add(Concept.SMILE, 0.8f)
        if (m.faces.size >= 3) add(Concept.GROUP, 0.8f)

        // 5. sceneType 仅作弱证据（0.40 < SearchEngine.MIN_EVIDENCE=0.5，
        //    因此它只能提升排序，不能单独决定命中）
        when (m.sceneType) {
            SceneType.VEHICLE_SCENE -> add(Concept.CAR, 0.40f)
            SceneType.BEACH_SEA -> add(Concept.BEACH, 0.40f)
            SceneType.MOUNTAIN_HILL -> add(Concept.MOUNTAIN, 0.40f)
            SceneType.FOOD_DRINK -> add(Concept.FOOD, 0.40f)
            SceneType.ANIMAL_PET -> { add(Concept.CAT, 0.35f); add(Concept.DOG, 0.35f) }
            SceneType.PEOPLE_PORTRAIT -> add(Concept.PERSON, 0.40f)
            SceneType.NIGHT_SCENE -> add(Concept.NIGHT, 0.40f)
            else -> Unit
        }
        return evidence
    }

    fun descriptionOf(concepts: Collection<Concept>): String = when {
        concepts.isEmpty() -> "自定义关键词"
        concepts.contains(Concept.CAR) -> "包含车辆的照片"
        concepts.contains(Concept.BEACH) -> "海滩/海边照片"
        concepts.contains(Concept.MOUNT_TAI) -> "泰山照片"
        concepts.contains(Concept.MOUNTAIN) -> "山景/登山照片"
        concepts.contains(Concept.SHIRTLESS) -> "光膀子/赤裸上身的人"
        concepts.contains(Concept.PERSON) -> "包含人物的照片"
        else -> concepts.joinToString("、") { it.name }
    }
}
