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
    val isAnalyzed: Boolean = false
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
        "palm tree" to listOf("棕榈树")
    )

    fun toChineseTags(englishLabel: String): List<String> {
        return translations[englishLabel.lowercase()] ?: emptyList()
    }
}
