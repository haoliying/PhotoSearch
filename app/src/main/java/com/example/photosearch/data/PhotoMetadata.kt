package com.example.photosearch.data

/**
 * Rich metadata for a photo, collected from multiple ML Kit APIs.
 * This enables much more accurate semantic search than simple labels alone.
 */
data class PhotoMetadata(
    val photoId: Long,
    // ML Kit Image Labeling results
    val labels: List<PhotoLabel> = emptyList(),
    // Object detection results (what objects are in the photo)
    val detectedObjects: List<DetectedObject> = emptyList(),
    // Face detection results
    val faces: List<FaceInfo> = emptyList(),
    // Scene classification
    val sceneType: SceneType = SceneType.UNKNOWN,
    // Color analysis
    val dominantColors: List<Int> = emptyList(),
    // Whether the image appears to be indoors/outdoors
    val isOutdoor: Boolean? = null,
    // Whether the image contains text
    val hasText: Boolean = false,
    // Image properties
    val width: Int = 0,
    val height: Int = 0,
    // Whether analysis is complete
    val isAnalyzed: Boolean = false,
    // VLM-generated rich description (Chinese)
    val description: String = "",
    // VLM-generated structured keywords
    val vlmKeywords: List<String> = emptyList()
)

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: BoundingBox? = null
)

data class BoundingBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

data class FaceInfo(
    val smilingProbability: Float = 0f,
    val leftEyeOpenProbability: Float = 0f,
    val rightEyeOpenProbability: Float = 0f,
    val facing: FaceFacing = FaceFacing.UNKNOWN
)

enum class FaceFacing {
    LEFT, RIGHT, STRAIGHT, UNKNOWN
}

enum class SceneType {
    OUTDOOR_NATURE,     // Mountains, forests, beaches, parks
    OUTDOOR_URBAN,      // City streets, buildings, architecture
    INDOOR_HOME,        // Living room, bedroom, kitchen
    INDOOR_PUBLIC,      // Restaurant, mall, office
    INDOOR_EVENT,       // Party, wedding, concert
    BEACH_SEA,          // Beach, ocean, swimming
    MOUNTAIN_HILL,      // Mountain, hiking, climbing
    NIGHT_SCENE,        // Night view, dark, neon lights
    FOOD_DRINK,         // Food, restaurant, cooking
    SPORT_EXERCISE,     // Sports, gym, running
    VEHICLE_SCENE,      // Cars, trucks, transportation
    ANIMAL_PET,         // Pets, animals
    PEOPLE_PORTRAIT,    // People-focused photos
    LANDSCAPE,          // Wide landscape views
    UNKNOWN
}

/**
 * Convert metadata to a flat list of searchable keywords/tags.
 * This is used by the search engine for matching.
 */
fun PhotoMetadata.toSearchableTags(): Set<String> {
    val tags = mutableSetOf<String>()

    // VLM description is the most valuable search signal
    if (description.isNotBlank()) {
        tags.add(description.lowercase())
        // Also add individual phrases from description
        description.split(Regex("[，。、；！？,\\.\\s]+"))
            .filter { it.length >= 2 }
            .forEach { tags.add(it.lowercase()) }
    }

    // VLM structured keywords
    vlmKeywords.forEach { tags.add(it.lowercase()) }

    // Add all labels (both English and common Chinese translations)
    labels.forEach { label ->
        tags.add(label.text.lowercase())
        LabelTranslator.toChineseTags(label.text).forEach { tags.add(it) }
    }

    // Add detected objects
    detectedObjects.forEach { obj ->
        tags.add(obj.label.lowercase())
        LabelTranslator.toChineseTags(obj.label).forEach { tags.add(it) }
    }

    // Add scene type tags
    when (sceneType) {
        SceneType.OUTDOOR_NATURE -> { tags.addAll(listOf("outdoor", "nature", "户外", "自然")) }
        SceneType.OUTDOOR_URBAN -> { tags.addAll(listOf("urban", "city", "城市", "街道")) }
        SceneType.INDOOR_HOME -> { tags.addAll(listOf("indoor", "home", "室内", "家")) }
        SceneType.INDOOR_PUBLIC -> { tags.addAll(listOf("indoor", "public", "公共场所")) }
        SceneType.BEACH_SEA -> { tags.addAll(listOf("beach", "sea", "ocean", "海滩", "海边", "沙滩", "大海")) }
        SceneType.MOUNTAIN_HILL -> { tags.addAll(listOf("mountain", "hill", "hiking", "山", "泰山", "登山", "爬山")) }
        SceneType.NIGHT_SCENE -> { tags.addAll(listOf("night", "dark", "夜景", "夜晚", "晚上")) }
        SceneType.FOOD_DRINK -> { tags.addAll(listOf("food", "meal", "美食", "食物", "吃的")) }
        SceneType.SPORT_EXERCISE -> { tags.addAll(listOf("sport", "exercise", "运动", "健身")) }
        SceneType.VEHICLE_SCENE -> { tags.addAll(listOf("vehicle", "car", "车", "汽车", "车辆")) }
        SceneType.ANIMAL_PET -> { tags.addAll(listOf("animal", "pet", "动物", "宠物", "猫", "狗")) }
        SceneType.PEOPLE_PORTRAIT -> { tags.addAll(listOf("person", "people", "人", "人物", "人像")) }
        SceneType.LANDSCAPE -> { tags.addAll(listOf("landscape", "scenery", "风景", "景色")) }
        else -> {}
    }

    // Add face-related tags
    if (faces.isNotEmpty()) {
        tags.addAll(listOf("person", "people", "face", "人", "人物", "人脸"))
        if (faces.any { it.smilingProbability > 0.5f }) {
            tags.addAll(listOf("smile", "happy", "笑", "开心"))
        }
    }

    // Add outdoor/indoor tags
    if (isOutdoor == true) {
        tags.addAll(listOf("outdoor", "户外", "室外"))
    } else if (isOutdoor == false) {
        tags.addAll(listOf("indoor", "室内"))
    }

    // Add clothing-related inferences
    val hasShirtLabel = labels.any {
        it.text.lowercase() in listOf("shirt", "t-shirt", "tshirt", "clothing", "garment", "top", "jacket", "coat", "sweater")
    }
    val hasPersonLabel = labels.any {
        it.text.lowercase() in listOf("person", "people", "man", "woman", "human")
    }
    val hasBareSkinLabel = labels.any {
        it.text.lowercase() in listOf("body", "skin", "chest", "torso", "muscle")
    }

    if (hasPersonLabel && !hasShirtLabel && (hasBareSkinLabel || sceneType == SceneType.BEACH_SEA || sceneType == SceneType.SPORT_EXERCISE)) {
        tags.addAll(listOf("shirtless", "bare chest", "光膀子", "赤裸上身", "没穿上衣"))
    }
    if (hasPersonLabel && hasShirtLabel) {
        tags.addAll(listOf("wearing shirt", "穿着衣服", "有上衣"))
    }

    return tags
}

/**
 * v3.7.0：界面展示用的**中文**标签。
 * VLM 中文关键词优先，其次是 ML Kit 标签 / 对象检测结果的中文翻译；
 * 词表外的英文标签直接丢弃，保证界面上不再出现英文标签。
 */
fun PhotoMetadata.chineseDisplayTags(limit: Int = Int.MAX_VALUE): List<String> {
    val out = mutableListOf<String>()
    vlmKeywords.forEach { LabelTranslator.toChinese(it)?.let { t -> out += t } }
    labels.sortedByDescending { it.confidence }.forEach {
        LabelTranslator.toChinese(it.text)?.let { t -> out += t }
    }
    detectedObjects.sortedByDescending { it.confidence }.forEach {
        LabelTranslator.toChinese(it.label)?.let { t -> out += t }
    }
    if (out.isEmpty()) {
        when (sceneType) {
            SceneType.BEACH_SEA -> out += listOf("海滩", "海边")
            SceneType.MOUNTAIN_HILL -> out += listOf("山景", "登山")
            SceneType.NIGHT_SCENE -> out += listOf("夜景", "夜晚")
            SceneType.FOOD_DRINK -> out += listOf("美食", "食物")
            SceneType.VEHICLE_SCENE -> out += listOf("车辆", "汽车")
            SceneType.ANIMAL_PET -> out += listOf("动物", "宠物")
            SceneType.PEOPLE_PORTRAIT -> out += listOf("人物", "人像")
            SceneType.LANDSCAPE -> out += listOf("风景", "景色")
            SceneType.SPORT_EXERCISE -> out += listOf("运动", "健身")
            else -> Unit
        }
    }
    return out.distinct().take(limit)
}

/**
 * Translates ML Kit English labels to Chinese equivalents for better search.
 */
object LabelTranslator {
    private val translations = mapOf(
        "car" to listOf("车", "汽车", "轿车"),
        "vehicle" to listOf("车辆", "交通工具"),
        "truck" to listOf("卡车", "货车"),
        "bus" to listOf("公交车", "大巴"),
        "motorcycle" to listOf("摩托车"),
        "bicycle" to listOf("自行车"),
        "wheel" to listOf("轮子", "轮胎"),
        "tire" to listOf("轮胎"),
        "person" to listOf("人", "人物"),
        "people" to listOf("人们", "人物"),
        "man" to listOf("男人", "男性"),
        "woman" to listOf("女人", "女性"),
        "child" to listOf("孩子", "儿童", "小孩"),
        "baby" to listOf("婴儿", "宝宝"),
        "boy" to listOf("男孩"),
        "girl" to listOf("女孩"),
        "portrait" to listOf("人像", "肖像"),
        "face" to listOf("脸", "面部"),
        "mountain" to listOf("山", "山峰"),
        "hill" to listOf("山丘", "小山"),
        "peak" to listOf("山顶", "峰"),
        "cliff" to listOf("悬崖", "峭壁"),
        "rock" to listOf("岩石", "石头"),
        "valley" to listOf("山谷"),
        "ridge" to listOf("山脊"),
        "landscape" to listOf("风景", "景色", "景观"),
        "beach" to listOf("海滩", "沙滩"),
        "sea" to listOf("海", "大海"),
        "ocean" to listOf("海洋", "大海"),
        "sand" to listOf("沙子", "沙滩"),
        "wave" to listOf("海浪", "波浪"),
        "coast" to listOf("海岸", "海边"),
        "shore" to listOf("岸边", "海滨"),
        "water" to listOf("水"),
        "swimming" to listOf("游泳"),
        "dog" to listOf("狗", "犬"),
        "cat" to listOf("猫"),
        "pet" to listOf("宠物"),
        "animal" to listOf("动物"),
        "bird" to listOf("鸟"),
        "fish" to listOf("鱼"),
        "food" to listOf("食物", "美食"),
        "meal" to listOf("饭菜", "餐"),
        "dish" to listOf("菜", "菜肴"),
        "fruit" to listOf("水果"),
        "vegetable" to listOf("蔬菜"),
        "dessert" to listOf("甜点", "蛋糕"),
        "drink" to listOf("饮料", "饮品"),
        "restaurant" to listOf("餐厅", "饭店"),
        "cooking" to listOf("烹饪", "做饭"),
        "building" to listOf("建筑", "大楼"),
        "house" to listOf("房子", "房屋"),
        "architecture" to listOf("建筑"),
        "tower" to listOf("塔"),
        "bridge" to listOf("桥", "桥梁"),
        "church" to listOf("教堂"),
        "temple" to listOf("寺庙", "庙"),
        "structure" to listOf("建筑物"),
        "tree" to listOf("树", "树木"),
        "flower" to listOf("花", "花朵"),
        "plant" to listOf("植物"),
        "garden" to listOf("花园", "园林"),
        "forest" to listOf("森林", "树林"),
        "leaf" to listOf("叶子", "树叶"),
        "grass" to listOf("草", "草地"),
        "park" to listOf("公园"),
        "nature" to listOf("自然"),
        "sky" to listOf("天空"),
        "sunset" to listOf("日落", "夕阳"),
        "sunrise" to listOf("日出", "朝阳"),
        "cloud" to listOf("云", "云朵"),
        "night" to listOf("夜晚", "夜景"),
        "dark" to listOf("黑暗", "暗"),
        "evening" to listOf("傍晚", "晚上"),
        "light" to listOf("光", "灯光"),
        "neon" to listOf("霓虹灯"),
        "sport" to listOf("运动"),
        "exercise" to listOf("锻炼", "健身"),
        "running" to listOf("跑步", "奔跑"),
        "fitness" to listOf("健身"),
        "gym" to listOf("健身房"),
        "football" to listOf("足球"),
        "basketball" to listOf("篮球"),
        "yoga" to listOf("瑜伽"),
        "shirt" to listOf("衬衫", "上衣"),
        "t-shirt" to listOf("T恤"),
        "clothing" to listOf("衣服", "服装"),
        "jacket" to listOf("夹克", "外套"),
        "coat" to listOf("大衣", "外套"),
        "dress" to listOf("裙子", "连衣裙"),
        "pants" to listOf("裤子"),
        "shoe" to listOf("鞋", "鞋子"),
        "hat" to listOf("帽子"),
        "sunglasses" to listOf("太阳镜", "墨镜"),
        "body" to listOf("身体", "躯体"),
        "skin" to listOf("皮肤"),
        "chest" to listOf("胸部", "胸膛"),
        "torso" to listOf("躯干", "上身"),
        "muscle" to listOf("肌肉"),
        "smile" to listOf("微笑", "笑"),
        "happy" to listOf("开心", "高兴"),
        "hiking" to listOf("登山", "徒步", "爬山"),
        "travel" to listOf("旅游", "旅行"),
        "selfie" to listOf("自拍"),
        "group" to listOf("合影", "合照"),
        "family" to listOf("家庭", "家人"),
        "friend" to listOf("朋友"),
        "wedding" to listOf("婚礼", "结婚"),
        "party" to listOf("聚会", "派对"),
        "concert" to listOf("音乐会", "演唱会"),
        "festival" to listOf("节日", "节庆"),
        "city" to listOf("城市"),
        "street" to listOf("街道", "马路"),
        "road" to listOf("道路", "公路"),
        "traffic" to listOf("交通"),
        "indoor" to listOf("室内"),
        "outdoor" to listOf("户外", "室外"),
        "scenery" to listOf("风景", "景色"),
        "view" to listOf("景色", "视野"),
        "palm" to listOf("棕榈树"),
        "snow" to listOf("雪", "雪景"),
        "rain" to listOf("雨", "下雨"),
        "palm tree" to listOf("棕榈树"),
        // v3.5.2：补充高频物体中文映射，供全文通道检索词表外的中文词
        "book" to listOf("书", "书本", "书籍"),
        "laptop" to listOf("笔记本电脑", "电脑", "笔记本"),
        "coffee" to listOf("咖啡", "咖啡杯"),
        "cup" to listOf("杯子", "咖啡杯"),
        "balloon" to listOf("气球"),
        "umbrella" to listOf("雨伞"),
        "chair" to listOf("椅子"),
        "table" to listOf("桌子", "木桌"),
        "phone" to listOf("手机"),
        "camera" to listOf("相机"),
        "bottle" to listOf("瓶子", "水瓶"),
        "wine" to listOf("红酒", "酒"),
        "street" to listOf("街道", "马路"),
        "road" to listOf("道路", "公路"),
        "train" to listOf("火车"),
        "airplane" to listOf("飞机"),
        "boat" to listOf("船", "小船"),
        "river" to listOf("河", "河流"),
        "lake" to listOf("湖", "湖泊"),
        "grass" to listOf("草", "草地"),
        "sky" to listOf("天空"),
        "cloud" to listOf("云", "云朵")
    )

    /**
     * v3.7.0：补充 ML Kit Image Labeling / Object Detection 的高频英文类别，
     * 让「内容标签」「识别物体」在界面上全部以中文显示。
     */
    private val extraTranslations = mapOf(
        // 自然 / 天气
        "nature" to listOf("自然", "自然风光"), "plant" to listOf("植物"),
        "bouquet" to listOf("花束"), "vase" to listOf("花瓶"), "potted plant" to listOf("盆栽"),
        "cactus" to listOf("仙人掌"), "bamboo" to listOf("竹子"), "mushroom" to listOf("蘑菇"),
        "sun" to listOf("太阳"), "sunlight" to listOf("阳光"), "moon" to listOf("月亮"),
        "star" to listOf("星星"), "fog" to listOf("雾", "雾气"), "mist" to listOf("薄雾"),
        "storm" to listOf("暴风雨"), "lightning" to listOf("闪电"), "rainbow" to listOf("彩虹"),
        "shadow" to listOf("影子", "阴影"), "reflection" to listOf("倒影", "反光"),
        "silhouette" to listOf("剪影"), "waterfall" to listOf("瀑布"), "stream" to listOf("溪流"),
        "pond" to listOf("池塘"), "swimming pool" to listOf("游泳池"), "island" to listOf("岛屿"),
        "desert" to listOf("沙漠"), "jungle" to listOf("丛林"), "field" to listOf("田野", "田地"),
        "meadow" to listOf("草地", "草原"), "farm" to listOf("农场"), "village" to listOf("村庄"),
        "canyon" to listOf("峡谷"), "cave" to listOf("洞穴"), "volcano" to listOf("火山"),
        "glacier" to listOf("冰川"), "iceberg" to listOf("冰山"),
        // 动物
        "mammal" to listOf("哺乳动物"), "wildlife" to listOf("野生动物"), "insect" to listOf("昆虫"),
        "butterfly" to listOf("蝴蝶"), "horse" to listOf("马"), "cow" to listOf("牛"),
        "sheep" to listOf("羊"), "pig" to listOf("猪"), "chicken" to listOf("鸡"),
        "duck" to listOf("鸭子"), "rabbit" to listOf("兔子"), "bear" to listOf("熊"),
        "tiger" to listOf("老虎"), "lion" to listOf("狮子"), "elephant" to listOf("大象"),
        "monkey" to listOf("猴子"), "deer" to listOf("鹿"), "wolf" to listOf("狼"),
        "fox" to listOf("狐狸"), "snake" to listOf("蛇"), "lizard" to listOf("蜥蜴"),
        "frog" to listOf("青蛙"), "turtle" to listOf("乌龟", "海龟"), "whale" to listOf("鲸鱼"),
        "dolphin" to listOf("海豚"), "shark" to listOf("鲨鱼"), "penguin" to listOf("企鹅"),
        "eagle" to listOf("鹰"), "goose" to listOf("鹅"), "parrot" to listOf("鹦鹉"),
        "owl" to listOf("猫头鹰"), "pigeon" to listOf("鸽子"), "sparrow" to listOf("麻雀"),
        "swan" to listOf("天鹅"), "crab" to listOf("螃蟹"), "shrimp" to listOf("虾"),
        "shell" to listOf("贝壳"), "coral" to listOf("珊瑚"), "jellyfish" to listOf("水母"),
        // 家居 / 室内
        "furniture" to listOf("家具"), "sofa" to listOf("沙发"), "couch" to listOf("沙发"),
        "bed" to listOf("床"), "desk" to listOf("书桌", "桌子"), "shelf" to listOf("架子", "书架"),
        "cabinet" to listOf("柜子"), "lamp" to listOf("台灯", "灯"), "light bulb" to listOf("灯泡"),
        "mirror" to listOf("镜子"), "clock" to listOf("时钟"), "window" to listOf("窗户"),
        "door" to listOf("门"), "stairs" to listOf("楼梯"), "floor" to listOf("地板"),
        "wall" to listOf("墙", "墙壁"), "ceiling" to listOf("天花板"), "roof" to listOf("屋顶"),
        "balcony" to listOf("阳台"), "fence" to listOf("栅栏"), "gate" to listOf("大门"),
        "bench" to listOf("长椅"), "kitchen" to listOf("厨房"), "bedroom" to listOf("卧室"),
        "living room" to listOf("客厅"), "bathroom" to listOf("浴室"), "office" to listOf("办公室"),
        "classroom" to listOf("教室"), "cafe" to listOf("咖啡馆"), "bar" to listOf("酒吧"),
        "hotel" to listOf("酒店"), "gallery" to listOf("画廊"), "theater" to listOf("剧院"),
        "cinema" to listOf("电影院"), "shop" to listOf("商店"), "store" to listOf("商店"),
        "mall" to listOf("商场"), "supermarket" to listOf("超市"), "hospital" to listOf("医院"),
        "school" to listOf("学校"), "library" to listOf("图书馆"), "museum" to listOf("博物馆"),
        "airport" to listOf("机场"), "station" to listOf("车站"), "harbor" to listOf("港口"),
        "pier" to listOf("码头"), "lighthouse" to listOf("灯塔"), "square" to listOf("广场"),
        "market" to listOf("市场"), "playground" to listOf("游乐场"), "stadium" to listOf("体育场"),
        "fountain" to listOf("喷泉"), "monument" to listOf("纪念碑"), "castle" to listOf("城堡"),
        "palace" to listOf("宫殿"), "mosque" to listOf("清真寺"), "statue" to listOf("雕像"),
        // 电子 / 物品
        "electronics" to listOf("电子产品"), "computer" to listOf("电脑", "计算机"),
        "monitor" to listOf("显示器"), "keyboard" to listOf("键盘"), "mouse" to listOf("鼠标"),
        "screen" to listOf("屏幕"), "television" to listOf("电视"), "tv" to listOf("电视"),
        "speaker" to listOf("音箱"), "headphones" to listOf("耳机"), "charger" to listOf("充电器"),
        "battery" to listOf("电池"), "remote control" to listOf("遥控器"),
        "game controller" to listOf("游戏手柄"), "printer" to listOf("打印机"),
        "microphone" to listOf("麦克风"), "projector" to listOf("投影仪"),
        "smartphone" to listOf("智能手机"), "tablet" to listOf("平板电脑"),
        "smartwatch" to listOf("智能手表"), "lens" to listOf("镜头"), "tripod" to listOf("三脚架"),
        "book" to listOf("书", "书本"), "magazine" to listOf("杂志"), "newspaper" to listOf("报纸"),
        "paper" to listOf("纸张"), "document" to listOf("文档", "文件"), "text" to listOf("文字"),
        "screenshot" to listOf("截图"), "letter" to listOf("信件"), "poster" to listOf("海报"),
        "sign" to listOf("招牌", "标识牌"), "billboard" to listOf("广告牌"), "menu" to listOf("菜单"),
        "blackboard" to listOf("黑板"), "whiteboard" to listOf("白板"), "map" to listOf("地图"),
        "flag" to listOf("旗帜"), "banner" to listOf("横幅"), "drawing" to listOf("绘画"),
        "painting" to listOf("画作", "油画"), "art" to listOf("艺术品"),
        "sculpture" to listOf("雕塑"), "photo" to listOf("照片"), "picture" to listOf("图片"),
        "frame" to listOf("相框"), "postcard" to listOf("明信片"), "stamp" to listOf("邮票"),
        "ticket" to listOf("票据"), "card" to listOf("卡片"), "money" to listOf("钱", "钞票"),
        "coin" to listOf("硬币"), "toy" to listOf("玩具"), "doll" to listOf("玩偶"),
        "teddy bear" to listOf("泰迪熊"), "puzzle" to listOf("拼图"), "ball" to listOf("球"),
        "kite" to listOf("风筝"), "bubble" to listOf("泡泡"), "slide" to listOf("滑梯"),
        "swing" to listOf("秋千"), "carousel" to listOf("旋转木马"),
        "musical instrument" to listOf("乐器"), "guitar" to listOf("吉他"), "piano" to listOf("钢琴"),
        "violin" to listOf("小提琴"), "drum" to listOf("鼓"), "flute" to listOf("长笛"),
        "trumpet" to listOf("小号"),
        // 食物
        "bread" to listOf("面包"), "rice" to listOf("米饭"), "noodle" to listOf("面条"),
        "pizza" to listOf("披萨"), "hamburger" to listOf("汉堡"), "sandwich" to listOf("三明治"),
        "salad" to listOf("沙拉"), "soup" to listOf("汤"), "meat" to listOf("肉"),
        "steak" to listOf("牛排"), "egg" to listOf("鸡蛋"), "milk" to listOf("牛奶"),
        "cheese" to listOf("奶酪"), "chocolate" to listOf("巧克力"),
        "ice cream" to listOf("冰淇淋"), "tea" to listOf("茶"), "juice" to listOf("果汁"),
        "beer" to listOf("啤酒"), "cocktail" to listOf("鸡尾酒"), "cookie" to listOf("饼干"),
        "candy" to listOf("糖果"), "apple" to listOf("苹果"), "banana" to listOf("香蕉"),
        "orange" to listOf("橙子"), "grape" to listOf("葡萄"), "strawberry" to listOf("草莓"),
        "watermelon" to listOf("西瓜"), "tomato" to listOf("西红柿"), "potato" to listOf("土豆"),
        "carrot" to listOf("胡萝卜"),
        // 人物 / 服饰
        "hand" to listOf("手"), "foot" to listOf("脚"), "arm" to listOf("手臂"),
        "leg" to listOf("腿"), "eye" to listOf("眼睛"), "ear" to listOf("耳朵"),
        "nose" to listOf("鼻子"), "mouth" to listOf("嘴"), "hair" to listOf("头发"),
        "head" to listOf("头部"), "crowd" to listOf("人群"), "team" to listOf("团队"),
        "audience" to listOf("观众"), "shorts" to listOf("短裤"), "skirt" to listOf("裙子"),
        "sweater" to listOf("毛衣"), "hoodie" to listOf("连帽衫"), "uniform" to listOf("制服"),
        "suit" to listOf("西装"), "tie" to listOf("领带"), "scarf" to listOf("围巾"),
        "glove" to listOf("手套"), "sock" to listOf("袜子"), "boot" to listOf("靴子"),
        "sandal" to listOf("凉鞋"), "cap" to listOf("鸭舌帽"), "helmet" to listOf("头盔"),
        "bag" to listOf("包", "袋子"), "backpack" to listOf("背包"), "handbag" to listOf("手提包"),
        "wallet" to listOf("钱包"), "watch" to listOf("手表"), "glasses" to listOf("眼镜"),
        "jewelry" to listOf("首饰"), "necklace" to listOf("项链"), "ring" to listOf("戒指"),
        "earring" to listOf("耳环"),
        // 交通 / 场景 / 活动
        "ship" to listOf("轮船"), "scooter" to listOf("踏板车"), "subway" to listOf("地铁"),
        "helicopter" to listOf("直升机"), "rocket" to listOf("火箭"),
        "ambulance" to listOf("救护车"), "police car" to listOf("警车"),
        "fire truck" to listOf("消防车"), "van" to listOf("面包车"),
        "soccer" to listOf("足球"), "tennis" to listOf("网球"), "baseball" to listOf("棒球"),
        "volleyball" to listOf("排球"), "badminton" to listOf("羽毛球"),
        "table tennis" to listOf("乒乓球"), "golf" to listOf("高尔夫"),
        "skiing" to listOf("滑雪"), "snowboarding" to listOf("单板滑雪"),
        "surfing" to listOf("冲浪"), "diving" to listOf("潜水"), "climbing" to listOf("攀岩"),
        "cycling" to listOf("骑行"), "marathon" to listOf("马拉松"), "boxing" to listOf("拳击"),
        "dance" to listOf("舞蹈"), "fishing" to listOf("钓鱼"), "camping" to listOf("露营"),
        "picnic" to listOf("野餐"), "parade" to listOf("游行"), "ceremony" to listOf("典礼"),
        "graduation" to listOf("毕业典礼"), "birthday" to listOf("生日"),
        "christmas" to listOf("圣诞节"), "halloween" to listOf("万圣节"),
        "fireworks" to listOf("烟花"), "gift" to listOf("礼物"), "candle" to listOf("蜡烛"),
        "lantern" to listOf("灯笼"), "decoration" to listOf("装饰"),
        "vehicle interior" to listOf("车内"), "home" to listOf("家", "家居")
    )

    private val allTranslations: Map<String, List<String>> = translations + extraTranslations

    fun toChineseTags(englishLabel: String): List<String> =
        allTranslations[englishLabel.lowercase()] ?: emptyList()

    /**
     * 把单个标签转成中文；无法翻译的纯英文返回 null，由调用方决定丢弃或保留。
     * 支持复合短语（"red car" → "红色" / "swimming pool" → "游泳池"）。
     */
    fun toChinese(text: String): String? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val hasLatin = raw.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (!hasLatin) return raw      // 已经是中文（VLM 关键词、用户标注等）
        val lower = raw.lowercase()
        allTranslations[lower]?.firstOrNull()?.let { return it }

        val words = lower.split(Regex("""[\s_\-/]+""")).filter { it.length >= 2 }
        if (words.size > 1) {
            val cn = words.mapNotNull { allTranslations[it]?.firstOrNull() }.distinct()
            if (cn.isNotEmpty()) return cn.joinToString("")
        }
        // 长短语里包含已知词（如 "a red car"）
        for ((k, v) in allTranslations) {
            if (k.length >= 4 && (lower.contains(" $k ") || lower.startsWith("$k ") ||
                        lower.endsWith(" $k"))) {
                return v.firstOrNull()
            }
        }
        return null
    }

    /** 界面展示用：中文标签列表，去重并保持顺序。 */
    fun toChineseLabels(items: Iterable<String>): List<String> =
        items.mapNotNull { toChinese(it) }.distinct()
}
