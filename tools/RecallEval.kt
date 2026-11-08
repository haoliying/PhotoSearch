import com.example.photosearch.data.*
import com.example.photosearch.search.*
import android.net.Uri

private fun M(
    desc: String = "",
    kw: List<String> = emptyList(),
    labels: List<PhotoLabel> = emptyList(),
    objects: List<DetectedObject> = emptyList(),
    faces: List<FaceInfo> = emptyList(),
    scene: SceneType = SceneType.UNKNOWN,
    hasText: Boolean = false,
    outdoor: Boolean? = null
) = PhotoMetadata(
    photoId = 0, labels = labels, detectedObjects = objects, faces = faces,
    sceneType = scene, isOutdoor = outdoor, hasText = hasText,
    isAnalyzed = true, description = desc, vlmKeywords = kw
)

private fun L(vararg p: Pair<String, Float>) = p.map { PhotoLabel(it.first, it.second) }
private fun O(vararg p: Pair<String, Float>) = p.map { DetectedObject(it.first, it.second) }

data class Q(val query: String, val relevant: Set<Int>)

fun main() {
    val metas: List<Pair<Int, PhotoMetadata>> = listOf(
        1 to M("一辆白色轿车停在路边", listOf("轿车", "汽车", "道路"),
            L("Car" to .92f, "Vehicle" to .85f), O("Car" to .9f), scene = SceneType.VEHICLE_SCENE),
        2 to M("蓝蓝的天空飘着白云，没有汽车", listOf("天空", "白云", "户外"),
            L("Sky" to .95f, "Cloud" to .82f)),
        3 to M("一个人骑着自行车穿过公园", listOf("自行车", "骑行", "公园", "人"),
            L("Bicycle" to .88f)),
        4 to M("桌上放着一杯热饮和一台打开的电脑，旁边还有一本书", listOf("拿铁", "咖啡", "笔记本", "木桌", "书本"),
            L("Drink" to .62f, "Table" to .70f)),
        5 to M("远处连绵的山峰和云海", listOf("山", "山峰", "云海", "风景"),
            L("Mountain" to .90f), scene = SceneType.MOUNTAIN_HILL),
        6 to M("海滩上一个光着上身的男人", listOf("海滩", "光膀子", "男人", "沙滩"),
            L("Beach" to .90f), scene = SceneType.BEACH_SEA),
        7 to M("一只橘猫趴在沙发上", listOf("猫", "猫咪", "沙发"), L("Cat" to .93f)),
        8 to M("桌上摆着一个汽车模型玩具", listOf("汽车模型", "玩具", "模型")),
        9 to M("一辆公交车停在站台边", listOf("公交车", "巴士", "站台"), L("Bus" to .90f)),
        // 10 / 13 模拟 VLM 失败：描述与关键词全空，只有本地标签
        10 to M(labels = L("Tree" to .85f, "Forest" to .80f), scene = SceneType.OUTDOOR_NATURE),
        11 to M("一盘色香味俱全的红烧肉", listOf("美食", "红烧肉", "饭菜"), L("Food" to .88f)),
        12 to M("手机屏幕上的一段文字截图", listOf("截图", "文字", "手机"), hasText = true),
        13 to M(labels = L("Car" to .75f), objects = O("car" to .80f), scene = SceneType.VEHICLE_SCENE),
        14 to M("空荡荡的车库", listOf("车库", "室内")),
        // 17 模拟 VLM 失败 + 本地文字检测信号
        17 to M(labels = L("Book" to .80f), hasText = true, scene = SceneType.INDOOR_HOME)
    )

    val photos = metas.map { (id, m) ->
        PhotoItem(id.toLong(), Uri("p$id"), 0L, "p$id.jpg", labels = m.labels, metadata = m, isAnalyzed = true)
    } + PhotoItem(15L, Uri("p15"), 0L, "p15.jpg", labels = L("Car" to .80f), isAnalyzed = false)
    val metaMap: Map<Long, PhotoMetadata> = metas.associate { (id, m) -> id.toLong() to m }

    val carSet = setOf(1, 9, 13, 15)
    val queries = listOf(
        Q("汽车", carSet),
        Q("有汽车的照片", carSet),
        Q("car", carSet),
        Q("自行车", setOf(3)),
        Q("咖啡", setOf(4)),
        Q("一杯咖啡", setOf(4)),
        Q("笔记本电脑", setOf(4)),
        Q("书本", setOf(4, 17)),
        Q("山", setOf(5)),
        Q("光膀子", setOf(6)),
        Q("猫", setOf(7)),
        Q("树林", setOf(10)),
        Q("红烧肉", setOf(11)),
        Q("截图", setOf(12)),
        Q("landscape", emptySet())
    )

    var sumP = 0.0
    var sumR = 0.0
    var sumF = 0.0
    for (q in queries) {
        val got: Set<Int> = SearchEngine.quickSearch(q.query, photos, metaMap)
            .map { it.photo.id.toInt() }.toSet()
        val tp = (got intersect q.relevant).size
        val p = if (got.isEmpty()) 0.0 else tp.toDouble() / got.size
        val r = if (q.relevant.isEmpty()) 0.0 else tp.toDouble() / q.relevant.size
        val f = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        sumP += p; sumR += r; sumF += f
        println("  %-10s P=%.2f R=%.2f  got=%-22s want=%s".format(
            q.query, p, r, got.sorted().toString(), q.relevant.sorted().toString()))
    }
    val n = queries.size
    println("MACRO_P=%.3f MACRO_R=%.3f MACRO_F1=%.3f".format(sumP / n, sumR / n, sumF / n))
}
