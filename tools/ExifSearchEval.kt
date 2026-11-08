import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.search.IntentParser
import com.example.photosearch.search.LocationQuery
import com.example.photosearch.search.PlaceCatalog
import com.example.photosearch.search.SearchEngine
import com.example.photosearch.search.TimeQueryParser
import com.example.photosearch.search.TimeRange
import java.util.Calendar
import java.util.TimeZone

/**
 * v3.6.0 时空联动检索离线评测。
 *
 * 用仓库真实数据类（PhotoItem / PhotoMetadata / PhotoExif）+ 真实检索层编译运行，
 * 覆盖：时间区间、地点精确/别名/模糊、时间×地点×内容联动、纯筛选、
 * 以及 v3.5.2 精确率/召回率修复不回退。
 *
 * 编译（Kotlin 1.9.22，配合 AndroidStubs.kt 提供 android.net.Uri 桩）：
 *   kotlinc -d out AndroidStubs.kt PhotoItem.kt PhotoMetadata.kt PhotoExif.kt \
 *     PlaceCatalog.kt TimeQueryParser.kt LocationMatcher.kt SearchIntent.kt \
 *     IntentParser.kt SearchEngine.kt ConceptRegistry.kt ExifSearchEval.kt
 *   java -cp out:kotlin-stdlib.jar ExifSearchEvalKt
 */
private val ZONE = TimeZone.getDefault()

private fun ts(y: Int, m: Int, d: Int): Long {
    val c = Calendar.getInstance(ZONE)
    c.clear()
    c.set(y, m - 1, d, 12, 0, 0)
    return c.timeInMillis
}

private val NOW = ts(2023, 8, 1)

private data class Row(
    val id: Long,
    val desc: String,
    val kws: List<String>,
    val time: Long,
    val place: String
)

private val ROWS = listOf(
    Row(1, "一辆红色汽车停在路边", listOf("汽车", "轿车"), ts(2023, 6, 15), "北京"),
    Row(2, "停车场里的一辆汽车", listOf("汽车"), ts(2023, 7, 20), "北京"),
    Row(3, "木桌上一杯咖啡", listOf("咖啡", "笔记本电脑"), ts(2024, 1, 10), "北京"),
    Row(4, "上海街头的汽车", listOf("汽车", "街道"), ts(2023, 6, 18), "上海"),
    Row(5, "一只小狗在草地上", listOf("狗", "草地"), ts(2023, 8, 5), "深圳"),
    Row(6, "南京路上的汽车", listOf("汽车", "马路"), ts(2023, 6, 22), "南京"),
    Row(7, "一只猫趴在窗台", listOf("猫"), ts(2023, 6, 11), "西湖"),   // 手动标注、无 GPS
    Row(8, "一束鲜花", listOf("花"), 0L, ""),                            // 无时间、无地点
    Row(9, "冬天的雪景", listOf("雪", "雪景"), ts(2022, 12, 25), "北京"),
    Row(10, "一辆自行车", listOf("自行车", "单车"), ts(2023, 6, 30), "杭州"),
    Row(11, "", emptyList(), ts(2023, 6, 5), "上海"),                    // 无分析结果
    Row(12, "桌上一本书", listOf("书", "书本"), ts(2023, 6, 12), "北京")
)

private fun makeExif(r: Row): PhotoExif {
    if (r.place.isBlank()) {
        return PhotoExif(photoId = r.id, dateTaken = r.time, dateSource = "exif")
    }
    val p = PlaceCatalog.findByQuery(r.place)
    return if (p != null) {
        PhotoExif(
            photoId = r.id, dateTaken = r.time, dateSource = "exif",
            country = p.country, adminArea = p.province, locality = p.fullName,
            latitude = p.lat, longitude = p.lon, placeSource = "gps-city"
        )
    } else {
        PhotoExif(
            photoId = r.id, dateTaken = r.time, dateSource = "exif",
            manualLabel = r.place, placeSource = "manual"
        )
    }
}

private val EXIF: Map<Long, PhotoExif> = ROWS.associate { it.id to makeExif(it) }
private val META: Map<Long, PhotoMetadata> = ROWS.associate {
    it.id to PhotoMetadata(photoId = it.id, description = it.desc, vlmKeywords = it.kws, isAnalyzed = true)
}
private val PHOTOS: List<PhotoItem> = ROWS.map {
    PhotoItem(
        id = it.id,
        uri = android.net.Uri("content://media/${it.id}"),
        dateTaken = it.time,
        displayName = "IMG_${it.id}.jpg"
    )
}
private val KNOWN: Set<String> = EXIF.values
    .map { it.shortPlaceLabel() }
    .filter { it.isNotBlank() }
    .toSet()

private data class Q(
    val text: String,
    val expect: Set<Long>,
    val note: String,
    val uiTime: TimeRange? = null,
    val uiLoc: LocationQuery? = null
)

private fun range(a: String, b: String): TimeRange = TimeQueryParser.fromDateStrings(a, b)!!

private val QUERIES = listOf(
    // ---- 内容检索基线（确认 v3.5.2 的精确率/召回率没有回退） ----
    Q("汽车", setOf(1, 2, 4, 6), "内容基线：只返回含汽车的照片"),
    Q("一杯咖啡", setOf(3), "中文切词不回退"),
    Q("自行车", setOf(10), "自行车概念不回退"),
    Q("landscape", emptySet(), "无相关照片时不能全库返回"),

    // ---- 时间区间 ----
    Q("2023年", setOf(1, 2, 4, 5, 6, 7, 10, 11, 12), "绝对年份硬过滤"),
    Q("2023年6月", setOf(1, 4, 6, 7, 10, 11, 12), "年月过滤"),
    Q("去年", setOf(9), "相对年份（now=2023-08-01）"),
    Q("2023-01-01 到 2023-06-30", setOf(1, 4, 6, 7, 10, 11, 12), "显式日期区间"),

    // ---- 地点（精确 / 别名 / 模糊 / 不串味） ----
    Q("北京", setOf(1, 2, 3, 9, 12), "地点精确 + 半径匹配"),
    Q("北京市", setOf(1, 2, 3, 9, 12), "地名后缀归一"),
    Q("bj", setOf(1, 2, 3, 9, 12), "拼音/英文别名归一"),
    Q("南京", setOf(6), "北京 不得误命中 南京"),
    Q("深圳", setOf(5), "单城市地点"),
    Q("西湖", setOf(7), "手动标注地点（无 GPS）"),
    Q("深圳市南山区", setOf(5), "模糊：长地名包含城市名"),

    // ---- 联动 ----
    Q("2023年 北京", setOf(1, 2, 12), "查询内 时间 AND 地点"),
    Q("2023年在北京拍的汽车", setOf(1, 2), "查询内 时间 AND 地点 AND 内容"),
    Q("北京 汽车", setOf(1, 2), "空格分隔 地点 AND 内容"),
    Q("2023年 北京 汽车", setOf(1, 2), "三段联动"),
    Q("汽车", setOf(1, 4, 6), "UI 时间区间 AND 内容", uiTime = range("2023-06-01", "2023-06-30")),
    Q("", setOf(1, 2, 4, 5, 6, 7, 10, 11, 12), "只有 UI 筛选时也返回结果", uiTime = range("2023-01-01", "2023-12-31")),
    Q("", setOf(1, 2, 3, 9, 12), "只有 UI 地点筛选", uiLoc = PlaceCatalog.queryFor(PlaceCatalog.findExact("北京")!!)),
    Q("", setOf(1, 2, 12), "UI 时间 AND UI 地点 联动",
        uiTime = range("2023-01-01", "2023-12-31"),
        uiLoc = PlaceCatalog.queryFor(PlaceCatalog.findExact("北京")!!)),
    Q("汽车", emptySet(), "联动过严时返回 0 条（2022 + 汽车）",
        uiTime = range("2022-01-01", "2022-12-31")),
    Q("汽车", emptySet(), "地点与内容冲突时返回 0 条",
        uiLoc = PlaceCatalog.queryFor(PlaceCatalog.findExact("深圳")!!))
)

private fun run(q: Q): Set<Long> {
    val parsed = IntentParser.parse(q.text, KNOWN, NOW)
    val intent = parsed.copy(
        timeRange = q.uiTime ?: parsed.timeRange,
        locationQuery = q.uiLoc ?: parsed.locationQuery
    )
    return SearchEngine.search(intent, PHOTOS, META, EXIF)
        .map { it.photo.id }
        .toSet()
}

private fun check(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun main() {
    // 解析层断言：确认自然语言里的时空短语被正确剥离，且不会误吃内容词
    val p1 = IntentParser.parse("北京烤鸭", KNOWN, NOW)
    check(p1.locationQuery == null, "「北京烤鸭」不应被判定为地点条件")
    check(p1.conditions.isNotEmpty(), "「北京烤鸭」应保留为内容条件")

    val p2 = IntentParser.parse("2023年在北京拍的汽车", KNOWN, NOW)
    check(p2.timeRange != null, "未解析出时间区间")
    check(p2.locationQuery != null, "未解析出地点")
    check(p2.conditions.isNotEmpty(), "未保留内容条件")
    check(p2.filtersOnly.not(), "三段查询不应退化成纯筛选")

    val p3 = IntentParser.parse("北京 汽车", KNOWN, NOW)
    check(p3.locationQuery != null && p3.conditions.isNotEmpty(), "空格分隔的联动解析失败")

    val p4 = IntentParser.parse("2023年", KNOWN, NOW)
    check(p4.filtersOnly, "纯时间查询应是 filtersOnly")

    // 半径匹配与经纬度校验
    val bj = PlaceCatalog.findExact("北京")!!
    val nj = PlaceCatalog.findExact("南京")!!
    check(PlaceCatalog.distanceKm(bj.lat, bj.lon, nj.lat, nj.lon) > 500.0, "城市距离计算异常")

    println("==== v3.6.0 时空联动检索评测（%d 张照片 / %d 条查询） ====".format(ROWS.size, QUERIES.size))
    println("已知地点候选: %s".format(KNOWN.sorted().joinToString("、")))
    println()
    var pass = 0
    var sumP = 0.0
    var sumR = 0.0
    var sumF = 0.0
    for (q in QUERIES) {
        val got = run(q)
        val tp = (got intersect q.expect).size
        val p = if (got.isEmpty()) 1.0 else tp.toDouble() / got.size
        val r = if (q.expect.isEmpty()) 1.0 else tp.toDouble() / q.expect.size
        val f = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        sumP += p; sumR += r; sumF += f
        val ok = got == q.expect
        if (ok) pass++
        println(
            "%-4s %-30s 期望=%-28s 实际=%-28s P=%.2f R=%.2f".format(
                if (ok) "PASS" else "FAIL",
                q.note.take(28),
                q.expect.sorted().toString().take(28),
                got.sorted().toString().take(28),
                p, r
            )
        )
        if (!ok) {
            println("       查询=%s  多余=%s  漏掉=%s".format(
                q.text.ifBlank { "(仅筛选面板)" },
                (got - q.expect).sorted(),
                (q.expect - got).sorted()
            ))
        }
    }
    val n = QUERIES.size
    println()
    println("通过: %d/%d".format(pass, n))
    println("宏平均 P=%.3f  R=%.3f  F1=%.3f".format(sumP / n, sumR / n, sumF / n))
    if (pass != n) throw AssertionError("%d 条用例未通过".format(n - pass))
    println("ALL ASSERTIONS PASSED")
}
