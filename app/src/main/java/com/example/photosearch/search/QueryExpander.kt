package com.example.photosearch.search

/**
 * v3.11.0：中文同义词 / 上下位词查询扩展（不依赖大模型）。
 *
 * 问题：端侧 ML Kit 给出的是英文标签，经 LabelTranslator 翻译成中文后往往与
 * 用户说法不一致。例如模型给 "Vehicle" → 中文是「车辆 / 交通工具」，但用户搜
 * 「有汽车的照片」；「汽车」这个词并不在照片的全文索引里，概念通道又受置信度
 * 门槛限制，于是明明有车的照片却搜不出来。
 *
 * 做法：查询时把用户词扩展成同一概念的一组常见说法，一起送入全文通道。
 * 这是**兜底扩召回**：SearchEngine 仍然先走概念通道、再走原词，只有两者都
 * 没命中时才尝试扩展词，且命中分打 0.85 折（排序低于直接命中），
 * 因此不会把「自行车」误当成「汽车」，也不会让结果排序失真。
 *
 * 词表按「概念组」隔离，不做链式扩散（汽车 → 车轮 → 火车 这种不会发生）。
 * 纯 Kotlin，可离线评测。
 */
object QueryExpander {

    /** 每个列表是一组等价 / 强相关说法；组与组之间互不扩散。 */
    private val GROUPS: List<List<String>> = listOf(
        // 汽车：刻意不含自行车 / 摩托车 / 单车 / 车轮 / 车站，避免误召
        listOf(
            "汽车", "轿车", "小汽车", "小轿车", "私家车", "机动车", "车辆", "车",
            "越野车", "跑车", "卡车", "货车", "面包车", "公交车", "大巴", "客车", "巴士",
            "出租车", "警车", "消防车", "救护车", "汽车照片", "车辆照片",
            "car", "vehicle", "sedan", "automobile", "truck", "van", "bus", "taxi", "minibus", "jeep"
        ),
        listOf("自行车", "单车", "脚踏车", "骑行", "骑车", "bicycle", "bike", "cycling"),
        listOf("摩托车", "电动车", "机车", "踏板车", "motorcycle", "scooter"),
        listOf("火车", "列车", "高铁", "地铁", "轨道交通", "train", "subway", "railway"),
        listOf("飞机", "客机", "航班", "机场", "airplane", "aircraft", "plane", "airport"),
        listOf("船", "轮船", "小船", "游船", "渡轮", "boat", "ship", "ferry"),
        // 人
        listOf(
            "人", "人物", "人像", "肖像", "男人", "女人", "行人", "人脸", "面孔",
            "person", "people", "man", "woman", "human", "face", "portrait", "child"
        ),
        listOf("自拍", "自拍照", "selfie", "front camera"),
        listOf("合影", "合照", "集体照", "一群人", "多人", "大合影", "人群", "group", "crowd"),
        // 动物
        listOf("猫", "猫咪", "小猫", "cat", "kitten"),
        listOf("狗", "小狗", "犬", "dog", "puppy"),
        listOf("宠物", "动物", "野生动物", "pet", "animal", "wildlife"),
        listOf("鸟", "小鸟", "飞鸟", "bird", "pigeon", "sparrow", "eagle"),
        // 自然 / 场景
        listOf(
            "海滩", "沙滩", "海边", "海岸", "海滨", "海", "大海", "海洋", "海水", "海浪",
            "海景", "beach", "seaside", "shore", "sand", "coast", "sea", "ocean", "wave"
        ),
        listOf(
            "山", "山峰", "山顶", "山丘", "山坡", "高山", "群山", "登山", "爬山", "山脉",
            "山景", "mountain", "hill", "peak", "hiking", "cliff", "ridge"
        ),
        listOf("花", "花朵", "鲜花", "樱花", "野花", "花束", "花卉", "flower", "blossom", "rose", "bloom"),
        listOf("树", "树木", "树林", "森林", "植物", "绿叶", "tree", "forest", "woods", "plant", "leaf"),
        listOf("草", "草地", "草坪", "草原", "绿地", "grass", "lawn", "meadow"),
        listOf("天空", "蓝天", "云", "云朵", "白云", "晴空", "sky", "cloud"),
        listOf("夜景", "夜晚", "晚上", "夜色", "夜间", "夜市", "霓虹灯", "黑夜", "night", "dark", "evening", "neon"),
        listOf("日落", "夕阳", "晚霞", "黄昏", "sunset", "dusk"),
        listOf("日出", "朝阳", "晨曦", "清晨", "sunrise", "dawn"),
        listOf("雪", "雪景", "下雪", "雪山", "雪花", "snow", "snowy"),
        listOf("雨", "下雨", "雨天", "rain", "rainy"),
        listOf(
            "建筑", "大楼", "房子", "桥", "塔", "高楼", "楼房", "房屋", "城市", "街道",
            "building", "house", "architecture", "tower", "bridge", "city", "street", "skyscraper"
        ),
        listOf("室内", "家里", "房间", "屋内", "室内照", "室内照片", "indoor", "room", "interior"),
        listOf("户外", "室外", "野外", "外景", "outdoor", "outside"),
        // 饮食
        listOf(
            "美食", "食物", "饭菜", "菜", "菜肴", "水果", "甜点", "蛋糕", "餐厅", "吃饭",
            "早餐", "午餐", "晚餐", "food", "meal", "dish", "cuisine", "fruit", "dessert", "cake", "restaurant"
        ),
        listOf("咖啡", "茶", "饮料", "饮品", "水杯", "杯子", "奶茶", "coffee", "tea", "drink", "cup", "juice"),
        listOf("酒", "红酒", "啤酒", "鸡尾酒", "wine", "beer", "cocktail"),
        // 文字类：刻意拆成「通称 / 截图 / 印刷品 / 书本」四组。
        // 若把「截图」与「文字 / 文档」放同一组，搜「截图」会扩展到「文字」，
        // 从而把任意含文字的书页 / 文档照片也召回，精确率明显下降。
        listOf("文字", "文档", "含文字", "图片文字", "有文字", "text", "document"),
        listOf("截图", "屏幕截图", "手机截图", "长截图", "截屏", "聊天记录", "screenshot"),
        listOf("招牌", "字幕", "票据", "菜单", "海报", "sign", "poster", "menu", "ticket", "billboard"),
        listOf("书", "书本", "书籍", "图书", "杂志", "报纸", "阅读", "book", "magazine", "newspaper"),
        // 电子设备
        listOf(
            "电脑", "笔记本电脑", "笔记本", "手机", "平板", "显示器", "屏幕", "相机",
            "laptop", "computer", "phone", "smartphone", "tablet", "camera", "screen", "monitor"
        ),
        // 活动 / 人物状态
        // 运动：拆成「上位词」与「具体项目」两组，避免搜「跑步」把游泳照片也召回
        listOf("运动", "体育锻炼", "sport", "exercise"),
        listOf("跑步", "奔跑", "马拉松", "慢跑", "running", "marathon"),
        listOf("健身", "健身房", "锻炼", "肌肉训练", "fitness", "gym"),
        listOf("游泳", "潜水", "swimming", "diving"),
        listOf("篮球", "basketball"),
        listOf("足球", "soccer", "football"),
        listOf("瑜伽", "yoga"),
        listOf("羽毛球", "网球", "乒乓球", "badminton", "tennis"),
        listOf("婚礼", "结婚", "新娘", "新郎", "婚纱", "wedding", "bride", "groom"),
        listOf("聚会", "派对", "生日", "庆典", "party", "birthday", "celebration"),
        listOf("宝宝", "婴儿", "小孩", "孩子", "儿童", "baby", "infant", "child", "kid"),
        listOf("微笑", "笑容", "笑脸", "开心", "大笑", "smile", "smiling", "happy", "laugh"),
        listOf("光膀子", "赤裸上身", "没穿上衣", "赤膊", "shirtless", "bare chest"),
        // 画质 / 构图（配合 LocalVisualTags 的本地视觉标签）
        listOf("竖幅", "竖图", "竖屏", "纵向", "portrait orientation"),
        listOf("横幅", "横图", "横屏", "横向", "landscape orientation"),
        listOf("全景", "宽幅", "长图", "panorama", "wide"),
        listOf("黑白", "黑白照", "灰调", "单色", "black and white", "monochrome"),
        listOf("单人", "一个人", "独照", "single person"),
        listOf("双人", "两个人", "two people")
    )

    /** 单元格：查询 term 太短时不扩展，避免单字「车」把整组拉进来。 */
    private const val MIN_CN_LEN = 2
    private const val MIN_EN_LEN = 3

    private val ASCII = Regex("^[a-z0-9 -]+$")

    /**
     * 返回与 term 同组、且可用于全文检索的其它说法。
     * 过滤掉过短词（全文通道本身也会忽略它们）与 term 自身。
     */
    fun expand(term: String, limit: Int = 14): List<String> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val isAscii = ASCII.matches(t)
        val minLen = if (isAscii) MIN_EN_LEN else MIN_CN_LEN
        if (t.length < minLen) return emptyList()

        val group = GROUPS.firstOrNull { g -> g.any { it == t } } ?: return emptyList()
        val cn = ArrayList<String>()
        val en = ArrayList<String>()
        group
            .map { it.trim().lowercase() }
            .filter { it != t && it.isNotBlank() }
            .filter { w ->
                val ascii = ASCII.matches(w)
                w.length >= if (ascii) MIN_EN_LEN else MIN_CN_LEN
            }
            .distinct()
            .forEach { w -> if (ASCII.matches(w)) en += w else cn += w }

        // 中英文交错，避免中文词把数量上限占满、而端侧标签原文（英文）挤不进来。
        // 「汽车」→「车辆 /轿车」是中文同义，→「vehicle / car」则直接命中 ML Kit 英文标签。
        val merged = ArrayList<String>(cn.size + en.size)
        val maxLen = maxOf(cn.size, en.size)
        for (i in 0 until maxLen) {
            if (i < cn.size) merged += cn[i]
            if (i < en.size) merged += en[i]
        }
        return merged.take(limit)
    }

    /** 对一组查询词元批量扩展，返回去重后的扩展词表。 */
    fun expandAll(terms: Iterable<String>, limitPerTerm: Int = 14, totalLimit: Int = 32): List<String> {
        val out = LinkedHashSet<String>()
        for (t in terms) {
            expand(t, limitPerTerm).forEach { out.add(it) }
            if (out.size >= totalLimit) break
        }
        return out.toList().take(totalLimit)
    }
}
