package com.example.photosearch.search

import com.example.photosearch.data.LabelTranslator
import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.SceneType

/**
 * 规范化概念词表 + 全文检索索引。
 *
 * 两条检索通道并存：
 *  - 概念通道：中英文同义词归一、否定/不确定表述抑制、易混淆组合词处理，
 *    精确率高，用于核心概念（车、人、海滩、山、光膀子…）。
 *  - 全文通道：把 VLM 描述/关键词、ML Kit 标签（含中文翻译）、对象检测、
 *    场景、人脸等全部编入索引，用「完整词」匹配覆盖词表外的任意查询词
 *    （咖啡、笔记本电脑、气球…），提升召回率。
 *
 * 全文通道的防噪声约束（与旧版 fuzzyMatch 的本质区别）：
 *  - 不做 2 字符子串模糊、不做双向 contains、不做字符集合重叠
 *  - 中文词元长度 >= 2，英文词元长度 >= 3 且用词边界
 *  - 只做「查询词出现在照片文本」这一个方向
 *  - 查询侧先剔除停用词，索引侧剔除否定句、不确定表述与描述套话
 *  - 长词优先，命中即停止，避免短 n-gram 乱命中
 */
enum class Concept {
    CAR, BICYCLE, BUS, BEACH, MOUNTAIN, MOUNT_TAI, PERSON, SHIRTLESS, SMILE,
    FLOWER, FOREST, FOOD, CAT, DOG, NIGHT, SUNSET, SUNRISE, BUILDING, INDOOR,
    WEDDING, SELFIE, GROUP, SNOW, SPORT, BABY, TEXT_CONTENT
}

object ConceptRegistry {

    /**
     * 含「车」但并非汽车的词。只在判定 CAR 时屏蔽，避免单字「车」误判。
     * 重要：不能像 v3.5.0 那样全局剔除，否则「自行车」「摩托车」这些词
     * 本身也会被抹掉，导致 BICYCLE 永远无法命中、用户搜自行车 0 结果。
     */
    private val CAR_CONFUSABLES: List<String> = listOf(
        // v3.6.0：补「单车 / 脚踏车」——BICYCLE 的别名含单字「车」，
        // 不屏蔽会让「汽车」查询把自行车照片召回（CAR 词表里有单字「车」）。
        "自行车", "单车", "脚踏车", "骑行", "摩托车", "电动车", "火车站", "停车场", "汽车模型", "玩具车",
        "车库", "车站", "车票", "车轮", "轮胎", "车模", "堵车", "车间",
        "修车", "车流", "站台", "车棚", "车牌", "车窗", "车厢", "下车", "车上",
        "bicycle", "bike", "cycling", "garage", "parking", "train station", "toy car", "model car"
    ).sortedByDescending { it.length }

    /** v3.8.0：一次扫描替换全部易混淆词，替代 24 次 String.replace。 */
    private val CAR_CONFUSABLE_REGEX = Regex(
        CAR_CONFUSABLES.joinToString("|") { Regex.escape(it.lowercase()) }
    )

    private val NEGATION_PREFIXES = listOf("没有", "无", "不含", "看不到", "并非", "不是", "未出现")
    private val NEGATION_SUFFIXES = listOf("也没有", "都没有", "均没有", "并不存在")
    private val UNCERTAIN_PREFIXES = listOf(
        "看起来像", "疑似", "可能是", "似乎是", "仿佛", "有点像", "像是", "好像是"
    )

    /** 概念 -> 中英文表层词。英文用词边界匹配，中文用包含匹配（最长优先）。 */
    private val SURFACES: Map<Concept, List<String>> = mapOf(
        Concept.CAR to listOf(
            "汽车", "轿车", "小汽车", "小轿车", "私家车", "SUV", "suv", "越野车",
            "跑车", "卡车", "货车", "面包车", "公交车", "大巴", "机动车", "车辆", "车",
            "car", "sedan", "automobile", "vehicle", "truck", "van", "taxi", "cab",
            "sports utility vehicle", "minibus", "coach", "jeep"
        ),
        Concept.BICYCLE to listOf("自行车", "单车", "脚踏车", "骑行", "bicycle", "bike", "cycling"),
        Concept.BUS to listOf("公交车", "大巴", "巴士", "客车", "bus", "minibus", "coach"),
        Concept.BEACH to listOf(
            "海滩", "沙滩", "海边", "海岸", "海滨", "海", "大海", "海洋", "海水", "海浪",
            "beach", "seaside", "shore", "sand", "coast", "sea", "ocean", "wave"
        ),
        Concept.MOUNTAIN to listOf(
            "山", "山峰", "山顶", "山丘", "山坡", "高山", "群山", "登山", "爬山", "山脉",
            "mountain", "hill", "peak", "hiking", "cliff", "ridge", "valley"
        ),
        Concept.MOUNT_TAI to listOf("泰山", "玉皇顶", "mount tai"),
        Concept.PERSON to listOf(
            "人", "人物", "男人", "女人", "行人", "合影", "合照", "人像", "肖像", "自拍", "人脸",
            "person", "people", "man", "woman", "human", "portrait", "face", "selfie", "child"
        ),
        Concept.SHIRTLESS to listOf(
            "光膀子", "赤裸上身", "没穿上衣", "光着上身", "赤膊", "裸露上身", "光着膀子",
            "shirtless", "bare chest", "no shirt", "shirtless man"
        ),
        Concept.SMILE to listOf("微笑", "笑容", "笑脸", "开心", "高兴", "大笑", "smile", "smiling", "happy", "laugh"),
        Concept.FLOWER to listOf("花", "花朵", "樱花", "野花", "鲜花", "flower", "blossom", "rose", "bloom"),
        Concept.FOREST to listOf("森林", "树林", "树木", "forest", "woods", "tree", "grove"),
        Concept.FOOD to listOf(
            "美食", "食物", "饭菜", "菜", "水果", "甜点", "蛋糕", "餐厅", "吃饭",
            "food", "meal", "dish", "cuisine", "fruit", "dessert", "cake", "restaurant"
        ),
        Concept.CAT to listOf("猫", "猫咪", "cat", "kitten"),
        Concept.DOG to listOf("狗", "小狗", "dog", "puppy"),
        Concept.NIGHT to listOf("夜景", "夜晚", "霓虹灯", "夜市", "night", "neon", "dark", "evening"),
        Concept.SUNSET to listOf("日落", "夕阳", "晚霞", "sunset", "dusk"),
        Concept.SUNRISE to listOf("日出", "朝阳", "晨曦", "sunrise", "dawn"),
        Concept.BUILDING to listOf(
            "建筑", "大楼", "房子", "桥", "塔", "高楼", "楼房",
            "building", "house", "architecture", "tower", "bridge", "skyscraper"
        ),
        Concept.INDOOR to listOf("室内", "家里", "房间", "室内照", "indoor", "room", "interior"),
        Concept.WEDDING to listOf("婚礼", "结婚", "新娘", "新郎", "婚纱", "wedding", "bride", "groom"),
        Concept.SELFIE to listOf("自拍", "selfie", "front camera"),
        Concept.GROUP to listOf("合影", "合照", "一群人", "多人", "集体照", "group", "crowd"),
        Concept.SNOW to listOf("雪", "雪景", "下雪", "雪山", "snow", "snowy"),
        Concept.SPORT to listOf(
            "运动", "跑步", "健身", "游泳", "篮球", "足球", "瑜伽", "羽毛球",
            "sport", "exercise", "running", "fitness", "swimming", "yoga", "football", "basketball"
        ),
        Concept.BABY to listOf("宝宝", "婴儿", "小孩", "孩子", "儿童", "baby", "infant", "child", "kid"),
        // 「书 / 书本」不能并进 TEXT_CONTENT：否则搜「截图」会把有书的照片也召回。
        // 它们交给全文通道处理（见 LabelTranslator 的 book 映射）。
        Concept.TEXT_CONTENT to listOf(
            "文字", "截图", "文档", "招牌", "字幕",
            "text", "document", "screenshot", "sign", "poster"
        )
    )

    private val ASCII_WORD = Regex("^[a-z0-9 ]+$")

    /** 同权重文本拼接分隔符：控制字符不会出现在查询词里，避免跨条目误命中。 */
    private const val INDEX_SEP = "\u0001"

    /**
     * v3.8.0：等价于旧的 Regex("(?<![a-z])word(?![a-z])")，但不编译正则。
     * 旧实现每次匹配都 new 一个 Regex 对象，在几万张照片 × 几十个词的规模下
     * 会产生数百万次正则编译，是搜索假死的主要常数级瓶颈。
     * 注意保持与旧正则一致的边界定义：只把 [a-z] 视作单词字符。
     */
    fun containsAsciiWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val beforeOk = i == 0 || text[i - 1] !in 'a'..'z'
            val after = i + word.length
            val afterOk = after >= text.length || text[after] !in 'a'..'z'
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }

    /**
     * 需要从全文索引中剔除的「噪声组合词」。
     * 与 CAR_CONFUSABLES 的区别：这里不放「自行车 / 摩托车」——它们本身是用户
     * 会搜的真实概念，剔除了就又回到 v3.5.0 的老 bug。
     */
    private val INDEX_NOISE: List<String> = listOf(
        "汽车模型", "玩具车", "车模", "车库", "车站", "车票", "车轮", "轮胎",
        "停车场", "火车站", "堵车", "车间", "修车", "车流", "站台", "车牌",
        "车窗", "车厢", "车棚", "toy car", "model car", "train station", "garage", "parking"
    ).sortedByDescending { it.length }

    /** 描述套话：VLM 常写「这张照片…」，这些词不应成为可检索内容。 */
    private val BOILERPLATE: List<String> = listOf(
        "这张照片", "这张图片", "照片中", "图片中", "画面中", "图中", "照片里", "图片里",
        "这是一张", "可以看到", "整体画面", "整张照片", "描述了", "显示的是", "呈现的是",
        "画面里", "这张", "照片", "图片", "画面", "图像", "整体氛围", "主要内容"
    ).sortedByDescending { it.length }

    // 注意：不要放单字「无」——它会把「无锡」「芜湖」这类地名从索引里抹掉。
    // 明确的否定词（没有/不含/看不到…）足以覆盖 VLM 描述里的否定表述。
    private val NEGATION_REGEX = Regex(
        "(?:没有|并无|不含|看不到|并非|不是|未出现)\\s*([\\u4e00-\\u9fa5a-zA-Z]{1,12})"
    )
    private val UNCERTAIN_REGEX = Regex(
        "(?:看起来像|疑似|可能是|似乎是|仿佛|有点像|像是|好像是)\\s*([\\u4e00-\\u9fa5]{1,12})"
    )

    /** 查询侧停用词：剔除后剩下的才是真正要检索的词元。 */
    private val STOPWORDS: List<String> = listOf(
        "照片", "图片", "相片", "帮我", "找一下", "搜索", "搜一下", "看一下", "看看",
        "有没有", "所有", "全部", "包含", "含有", "给我", "请把", "关于", "拍摄",
        "一张", "这张", "那些", "这些", "一些", "里面", "里头", "相关", "类型",
        "内容", "场景", "请", "把", "上", "下", "的", "了", "有", "和", "与",
        "或", "或者", "并且", "是", "吗", "呢", "啊", "我", "想", "要", "找",
        "给", "张", "些", "个", "一下", "里", "中"
    ).sortedByDescending { it.length }

    /**
     * v3.8.0：normalize 是纯函数，且标签 / 关键词在几万张照片间高度重复，
     * 加一层有上限的 LRU 缓存，避免每次 fromMetadata 都重扫 30×10 的词表。
     */
    private val NORMALIZE_CACHE_MAX = 4096
    private val normalizeCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Set<Concept>>(512, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Set<Concept>>?
            ): Boolean = size > NORMALIZE_CACHE_MAX
        }
    )

    /** 把一段自由文本映射为规范概念集合。 */
    fun normalize(text: String): Set<Concept> {
        if (text.isBlank()) return emptySet()
        val key = text.lowercase()
        normalizeCache[key]?.let { return it }
        val result = normalizeUncached(key)
        normalizeCache[key] = result
        return result
    }

    private fun normalizeUncached(lower: String): Set<Concept> {
        // 仅 CAR 使用屏蔽后的文本，其余概念仍看原文（修复自行车/摩托车无法检索）
        val carText = CAR_CONFUSABLE_REGEX.replace(lower, " ")

        val found = mutableSetOf<Concept>()
        for ((concept, surfaces) in SURFACES) {
            val target = if (concept == Concept.CAR) carText else lower
            for (s in surfaces.sortedByDescending { it.length }) {
                val sl = s.lowercase()
                val hit = if (ASCII_WORD.matches(sl)) {
                    // v3.8.0：无正则的 ASCII 词边界匹配，语义与旧版完全一致
                    containsAsciiWord(target, sl)
                } else {
                    target.contains(sl)
                }
                if (hit) {
                    found.add(concept)
                    break
                }
            }
        }

        // 否定句 / 不确定表述抑制：解决「画面中没有汽车」「看起来像泰山」这类误判
        for ((concept, surfaces) in SURFACES) {
            for (s in surfaces) {
                val sl = s.lowercase()
                val negated = NEGATION_PREFIXES.any { lower.contains(it + sl) || lower.contains("$it $sl") } ||
                        NEGATION_SUFFIXES.any { lower.contains(sl + it) }
                val uncertain = UNCERTAIN_PREFIXES.any { lower.contains(it + sl) }
                if (negated || uncertain) found.remove(concept)
            }
        }
        return found
    }

    /**
     * 从照片元数据提取「概念 -> 证据强度」。
     * v3.5.2：新增 detectedObjects、LabelTranslator 中文翻译、faces / hasText 信号，
     * 并把标签置信度门槛从 0.45 降到 0.30（低置信度给低权重，不单独决定命中）。
     */
    fun fromMetadata(m: PhotoMetadata): Map<Concept, Float> {
        val evidence = mutableMapOf<Concept, Float>()

        fun add(concept: Concept, weight: Float) {
            if (weight > (evidence[concept] ?: 0f)) evidence[concept] = weight
        }

        fun addConceptsOf(text: String, weight: Float) {
            normalize(text).forEach { add(it, weight) }
        }

        // 1. VLM 结构化关键词（最可靠）
        m.vlmKeywords.forEach { addConceptsOf(it, 1.0f) }

        // 2. VLM 描述（已过滤否定与不确定表述）
        addConceptsOf(m.description, 0.9f)

        // 3. ML Kit 标签：英文原文 + 中文翻译都参与概念识别
        m.labels.filter { it.confidence >= 0.30f }.forEach { label ->
            val w = 0.45f + 0.35f * label.confidence
            addConceptsOf(label.text, w)
            LabelTranslator.toChineseTags(label.text).forEach { addConceptsOf(it, w) }
        }

        // 4. 对象检测结果（此前完全未使用）
        m.detectedObjects.filter { it.confidence >= 0.30f }.forEach { obj ->
            val w = 0.50f + 0.30f * obj.confidence
            addConceptsOf(obj.label, w)
            LabelTranslator.toChineseTags(obj.label).forEach { addConceptsOf(it, w) }
        }

        // 5. 结构化信号
        if (m.faces.any { it.smilingProbability > 0.7f }) add(Concept.SMILE, 0.8f)
        if (m.faces.size >= 3) add(Concept.GROUP, 0.8f)
        if (m.faces.isNotEmpty()) add(Concept.PERSON, 0.75f)
        // hasText 太宽泛，不进概念通道，只在全文索引里作弱信号

        // 6. sceneType 仅作弱证据（0.40 < MIN_EVIDENCE=0.5，只能提升排序）
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

    // ---------------------------------------------------------------------
    // 查询切词
    // ---------------------------------------------------------------------

    /**
     * 把自然语言查询切成检索词元。中文按标点切段 → 去停用词 → 保留 2~4 gram，
     * 解决「一杯咖啡」「有汽车的照片」这类带修饰词的中文查询。
     */
    private val SEGMENT_SPLIT = Regex("""[\s,，、;；!！?？:：.。()（）\[\]【】]+""")
    private val WHITESPACE = Regex("\\s+")
    private val DESC_SPLIT = Regex("[，。、；！？,.\\s:：;；\\n\"]+")

    fun tokenizeQuery(text: String): List<String> {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (seg in t.split(SEGMENT_SPLIT)) {
            if (ASCII_WORD.matches(seg)) {
                out += seg.split(WHITESPACE).filter { it.length >= 3 }
                continue
            }
            var s = seg
            for (w in STOPWORDS) s = s.replace(w, " ")
            for (piece in s.split(WHITESPACE)) {
                val p = piece.trim()
                if (p.length < 2) continue
                out += p
                // 长中文串额外生成 2~4 gram，让「一杯咖啡」能命中「咖啡」
                val maxN = minOf(4, p.length)
                for (n in 2..maxN) {
                    for (i in 0..p.length - n) out += p.substring(i, i + n)
                }
            }
        }
        return out.distinct()
    }

    // ---------------------------------------------------------------------
    // 全文检索索引
    // ---------------------------------------------------------------------

    /**
     * v3.8.0：把 46 次 String.replace 合成一次正则扫描。
     * 旧实现每张照片约 15 次 add × 46 次 replace ≈ 700 次字符串重建，
     * 3 万张照片就是 2000 万次，是首次检索耗时的主要来源。
     * 分支顺序按「否定/不确定句式 > 长词 > 短词」，与旧版降序逐个替换等价。
     */
    private val SCRUB_REGEX: Regex = run {
        val parts = mutableListOf<String>()
        parts += "(?:没有|并无|不含|看不到|并非|不是|未出现)\\s*[\\u4e00-\\u9fa5a-zA-Z]{1,12}"
        parts += "(?:看起来像|疑似|可能是|似乎是|仿佛|有点像|像是|好像是)\\s*[\\u4e00-\\u9fa5]{1,12}"
        (INDEX_NOISE + BOILERPLATE)
            .sortedByDescending { it.length }
            .forEach { parts += Regex.escape(it.lowercase()) }
        Regex(parts.joinToString("|"))
    }

    /** 剔除描述套话、否定句、不确定表述，避免它们变成可检索词或造成误排除。 */
    private fun scrub(text: String): String {
        val t = text.lowercase()
        if (!SCRUB_REGEX.containsMatchIn(t)) return t.trim()
        return SCRUB_REGEX.replace(t, " ").trim()
    }

    /**
     * v3.8.0 全文索引：按权重分桶的紧凑结构。
     *
     * 旧版为每张照片保存 50~100 条 (文本, 权重)，每次查询为每个词元编译一次
     * 正则并遍历全部条目；几万张照片时内存与时间都不可控。
     * 新版把「同权重的所有文本」用控制字符拼成一个字符串，桶按权重降序排列：
     *  - 语义等价：旧版返回所有命中条目中的最大权重，降序桶的第一个命中即该值；
     *  - 内存降到原来的 1/5 左右，查询不再编译正则，且命中即返回。
     */
    class SearchIndex(private val buckets: List<Pair<Float, String>>) {

        /** 返回查询词元在这张照片上的最高证据权重，0 表示未命中。 */
        fun score(term: String): Float {
            val q = term.lowercase().trim()
            if (q.isEmpty()) return 0f
            val ascii = ASCII_WORD.matches(q)
            if (ascii) {
                if (q.length < 3) return 0f
                for ((w, text) in buckets) {
                    if (containsAsciiWord(text, q)) return w
                }
                return 0f
            }
            if (q.length < 2) return 0f
            for ((w, text) in buckets) {
                if (text.contains(q)) return w
            }
            return 0f
        }

        /** 供离线评测与调试观察索引规模。 */
        val bucketCount: Int get() = buckets.size
    }

    /** 一张照片的检索预处理结果：与查询无关，可以跨多次搜索复用。 */
    data class PreparedDoc(
        val evidence: Map<Concept, Float>,
        val index: SearchIndex
    )

    fun prepare(m: PhotoMetadata, exif: PhotoExif? = null): PreparedDoc =
        PreparedDoc(fromMetadata(m), buildSearchIndex(m, exif))

    /**
     * v3.8.0 文档缓存：LRU，按 photoId 缓存 PreparedDoc。
     * 几万张照片时为避免内存膨胀，默认只保留最近 6000 张；未命中的照片现算，
     * 由于已去掉正则编译，单次全量检索在后台线程也是秒级，不会阻塞输入。
     * 元数据/EXIF 变化时由 ViewModel 调用 invalidate / clear。
     */
    class SearchDocCache(private val maxSize: Int = DEFAULT_MAX_DOCS) {
        private val map = object : LinkedHashMap<Long, PreparedDoc>(
            minOf(maxSize, 1024), 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, PreparedDoc>?
            ): Boolean = size > maxSize
        }

        @Synchronized
        fun get(photoId: Long, m: PhotoMetadata, exif: PhotoExif?): PreparedDoc =
            map[photoId] ?: prepare(m, exif).also { map[photoId] = it }

        @Synchronized
        fun invalidate(ids: Collection<Long>) {
            ids.forEach { map.remove(it) }
        }

        @Synchronized
        fun clear() = map.clear()

        @Synchronized
        fun size(): Int = map.size

        companion object {
            const val DEFAULT_MAX_DOCS = 6000
        }
    }

    /**
     * 把一张照片的所有可用信号编成全文索引（不需要重新分析即可生效）。
     * v3.6.0：可选传入 EXIF，把地点名称与拍摄年/月也纳入全文通道，
     * 这样即使自然语言里没被解析成结构化筛选，输入「北京」「2023」也能命中。
     */
    fun buildSearchIndex(m: PhotoMetadata, exif: PhotoExif? = null): SearchIndex {
        // v3.8.0：同权重文本合并，桶按权重降序，命中即返回最大权重
        val buckets = mutableMapOf<Float, StringBuilder>()
        fun add(text: String, weight: Float) {
            val s = scrub(text)
            if (s.length >= 2) {
                buckets.getOrPut(weight) { StringBuilder() }.append(s).append(INDEX_SEP)
            }
        }

        m.vlmKeywords.forEach { add(it, 1.0f) }
        add(m.description, 0.80f)
        m.description.split(DESC_SPLIT)
            .filter { it.length >= 2 }
            .forEach { add(it, 0.85f) }

        m.labels.filter { it.confidence >= 0.30f }.forEach { label ->
            val w = 0.55f + 0.25f * label.confidence
            add(label.text, w)
            LabelTranslator.toChineseTags(label.text).forEach { add(it, w) }
        }
        m.detectedObjects.filter { it.confidence >= 0.30f }.forEach { obj ->
            val w = 0.60f + 0.20f * obj.confidence
            add(obj.label, w)
            LabelTranslator.toChineseTags(obj.label).forEach { add(it, w) }
        }

        if (m.faces.isNotEmpty()) {
            listOf("人物", "人像", "人脸", "person", "people", "face", "portrait").forEach { add(it, 0.70f) }
        }
        // 弱信号权重 0.50 < TEXT_MIN_EVIDENCE(0.55)：只能提升排序，不能单独决定命中
        if (m.hasText) {
            listOf("文字", "截图", "文档", "text", "document", "screenshot").forEach { add(it, 0.50f) }
        }

        when (m.sceneType) {
            SceneType.BEACH_SEA -> listOf("海滩", "海边", "沙滩", "beach", "sea")
            SceneType.MOUNTAIN_HILL -> listOf("山景", "登山", "mountain", "hiking")
            SceneType.VEHICLE_SCENE -> listOf("车辆", "vehicle", "car")
            SceneType.FOOD_DRINK -> listOf("美食", "食物", "food", "meal")
            SceneType.ANIMAL_PET -> listOf("动物", "宠物", "animal", "pet")
            SceneType.NIGHT_SCENE -> listOf("夜景", "夜晚", "night")
            SceneType.PEOPLE_PORTRAIT -> listOf("人物", "人像", "portrait")
            SceneType.OUTDOOR_NATURE -> listOf("户外", "自然", "outdoor", "nature")
            SceneType.OUTDOOR_URBAN -> listOf("城市", "街道", "city", "street")
            SceneType.LANDSCAPE -> listOf("风景", "景色", "landscape")
            SceneType.SPORT_EXERCISE -> listOf("运动", "健身", "sport")
            SceneType.INDOOR_HOME, SceneType.INDOOR_PUBLIC, SceneType.INDOOR_EVENT -> listOf("室内", "indoor")
            else -> emptyList()
        }.forEach { add(it, 0.50f) }

        when (m.isOutdoor) {
            true -> listOf("户外", "室外", "outdoor").forEach { add(it, 0.50f) }
            false -> listOf("室内", "indoor", "room").forEach { add(it, 0.50f) }
            null -> Unit
        }

        // ---- v3.6.0：EXIF 时空信息进入全文索引 ----
        if (exif != null) {
            val loc = exif.searchableLocation()
            if (loc.isNotBlank()) {
                add(loc, 0.85f)
                loc.split(WHITESPACE).filter { it.length >= 2 }.forEach { add(it, 0.85f) }
            }
            if (exif.hasTime) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = exif.dateTaken }
                val y = cal.get(java.util.Calendar.YEAR)
                val mo = cal.get(java.util.Calendar.MONTH) + 1
                val mm = String.format(java.util.Locale.US, "%02d", mo)
                listOf("$y", "${y}年", "${y}年${mo}月", "$y-$mm").forEach { add(it, 0.70f) }
            }
            // v3.7.0：相机 / 镜头 / 曝光 / 画幅 / 格式 / 季节 / 时段等中文标签
            exif.searchableExifTags().forEach { add(it, 0.75f) }
        }
        val ordered = buckets.entries
            .sortedByDescending { it.key }
            .map { it.key to it.value.toString() }
        return SearchIndex(ordered)
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
