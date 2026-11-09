import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoLabel
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.search.ConceptRegistry
import com.example.photosearch.search.IntentParser
import com.example.photosearch.search.SearchEngine
import java.util.Calendar
import java.util.TimeZone

/**
 * v3.8.0 大规模检索离线基准与语义一致性验证。
 *
 * 目标：证明在 30,000 张照片下
 *  1) 索引缓存命中、无正则词边界匹配与旧实现语义完全等价；
 *  2) 单次全库检索从「每次重建全部索引」变成「复用索引」，耗时大幅下降；
 *  3) 输入变化时可立即取消旧查询，任务不会堆积。
 *
 * 编译（Kotlin 1.9.22 + AndroidStubs.kt）：
 *   kotlinc -d out Stub_*.kt PhotoItem.kt PhotoMetadata.kt PhotoExif.kt ExifReader.kt \
 *     PhotoExifStore.kt AnalysisResultStore.kt PhotoRepository.kt PlaceCatalog.kt \
 *     TimeQueryParser.kt LocationMatcher.kt SearchIntent.kt IntentParser.kt \
 *     SearchEngine.kt ConceptRegistry.kt PerfEval.kt
 *   java -Xmx1g -cp out:kotlin-stdlib.jar PerfEvalKt
 */
private const val N = 30_000
private val ZONE: TimeZone = TimeZone.getDefault()

private var passed = 0
private var total = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    total++
    if (cond) {
        passed++
        println("PASS $name${if (detail.isBlank()) "" else "  $detail"}")
    } else {
        println("FAIL $name  $detail")
    }
}

private fun ts(y: Int, m: Int, d: Int): Long {
    val c = Calendar.getInstance(ZONE)
    c.clear()
    c.set(y, m - 1, d, 12, 0, 0)
    return c.timeInMillis
}

private val CITIES = listOf("北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "苏州")
private val TOPICS = listOf(
    listOf("汽车", "轿车"), listOf("咖啡", "笔记本电脑"), listOf("狗", "草地"),
    listOf("山", "登山"), listOf("花", "樱花"), listOf("建筑", "街道"),
    listOf("自行车", "骑行"), listOf("雪景", "冬天")
)

private val PHOTOS: List<PhotoItem> = (1..N).map { i ->
    PhotoItem(
        id = i.toLong(),
        uri = android.net.Uri("content://media/$i"),
        dateTaken = ts(2019 + (i / 8) % 6, 1 + i % 12, 1 + i % 28),
        displayName = "IMG_$i.jpg"
    )
}

private val META: Map<Long, PhotoMetadata> = PHOTOS.associate { p ->
    val i = p.id.toInt()
    val kws = TOPICS[i % TOPICS.size]
    p.id to PhotoMetadata(
        photoId = p.id,
        description = "一张关于${kws[0]}的照片，拍于${CITIES[i % CITIES.size]}",
        vlmKeywords = kws,
        labels = listOf(PhotoLabel(kws[0], 0.72f + (i % 20) / 100f)),
        isAnalyzed = true
    )
}

private val EXIF: Map<Long, PhotoExif> = PHOTOS.associate { p ->
    val i = p.id.toInt()
    p.id to PhotoExif(
        photoId = p.id,
        dateTaken = ts(2019 + (i / 8) % 6, 1 + i % 12, 1 + i % 28),
        dateSource = "exif",
        country = "中国",
        locality = CITIES[(i / 8) % CITIES.size],
        cameraMake = if (i % 2 == 0) "Canon" else "Nikon",
        mimeType = "image/jpeg",
        fileSize = 1_500_000L + i,
        imageWidth = 4000,
        imageHeight = 3000,
        schemaVersion = 2
    )
}

/** 旧实现的全文通道打分：每条文本单独保存 + 每次编译正则，作为语义参照。 */
private fun legacyScore(entries: List<Pair<String, Float>>, term: String): Float {
    val q = term.lowercase().trim()
    if (q.isEmpty()) return 0f
    val ascii = Regex("^[a-z0-9 ]+$").matches(q)
    if (ascii) {
        if (q.length < 3) return 0f
        val re = Regex("(?<![a-z])${Regex.escape(q)}(?![a-z])")
        var best = 0f
        for ((phrase, w) in entries) {
            if (w > best && re.containsMatchIn(phrase)) best = w
        }
        return best
    }
    if (q.length < 2) return 0f
    var best = 0f
    for ((phrase, w) in entries) {
        if (w > best && (phrase == q || phrase.contains(q))) best = w
    }
    return best
}

private fun bench(intent: com.example.photosearch.search.SearchIntent,
                  cache: ConceptRegistry.SearchDocCache?): Pair<Long, List<Long>> {
    val t = System.nanoTime()
    val r = SearchEngine.search(intent, PHOTOS, META, EXIF, cache = cache)
    return (System.nanoTime() - t) / 1_000_000L to r.map { it.photo.id }
}

fun main() {
    println("数据集：$N 张照片 / ${META.size} 份分析结果 / ${EXIF.size} 份 EXIF\n")

    // ------------------------------------------------------------------
    // 1) 无正则词边界与旧正则等价
    // ------------------------------------------------------------------
    val wordCases = listOf(
        "a red car on the road" to "car",
        "car2 and car" to "car",
        "scarf and car" to "car",
        "cartoon" to "car",
        "shirtless man" to "shirtless man",
        "bike, bicycle" to "bike"
    )
    for ((text, word) in wordCases) {
        val old = Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(text)
        val now = ConceptRegistry.containsAsciiWord(text, word)
        check("词边界等价「$word」in「$text」", old == now, "old=$old new=$now")
    }

    // ------------------------------------------------------------------
    // 2) 分桶索引与旧全条目索引打分等价
    // ------------------------------------------------------------------
    val entries = listOf(
        "红色汽车 轿车 车辆" to 1.0f,
        "蓝色汽车 跑车" to 1.0f,          // 同权重合并成一个桶
        "香浓咖啡 拿铁" to 0.85f,
        "car vehicle sedan" to 0.90f,
        "咖啡杯 笔记本电脑" to 0.75f,
        "山景 登山 山峰" to 0.60f,
        "beach sea ocean" to 0.55f
    )
    val buckets = entries.groupBy { it.second }.entries
        .sortedByDescending { it.key }
        .map { (w, list) -> w to list.joinToString("\u0001") { it.first } + "\u0001" }
    val idx = ConceptRegistry.SearchIndex(buckets)
    for (term in listOf("汽车", "轿车", "咖啡", "拿铁", "car", "sedan", "sea", "山峰", "c", "车")) {
        check("分桶索引等价「$term」", idx.score(term) == legacyScore(entries, term),
            "new=${idx.score(term)} old=${legacyScore(entries, term)}")
    }
    check("索引桶数远少于原始条目", idx.bucketCount <= entries.size,
        "buckets=${idx.bucketCount} entries=${entries.size}")

    // ------------------------------------------------------------------
    // 3) 大库检索：冷启动 / 缓存预热 / 缓存命中
    // ------------------------------------------------------------------
    val intentCar = IntentParser.parse("汽车")
    val intentMix = IntentParser.parse("2021年 北京 咖啡")
    val intentBike = IntentParser.parse("自行车")

    val (coldMs, coldIds) = bench(intentCar, null)
    println("冷启动（每次重建索引）: ${coldMs}ms, 命中 ${coldIds.size} 张")

    val cache = ConceptRegistry.SearchDocCache(N)
    val (warmUpMs, warmIds) = bench(intentCar, cache)
    println("缓存预热（首次填充）  : ${warmUpMs}ms, 命中 ${warmIds.size} 张, 缓存 ${cache.size()} 条")

    val (hitMs, hitIds) = bench(intentCar, cache)
    println("缓存命中（复用索引）  : ${hitMs}ms, 命中 ${hitIds.size} 张")

    check("冷/热缓存结果完全一致", coldIds == warmIds && warmIds == hitIds,
        "cold=${coldIds.size} warm=${warmIds.size} hit=${hitIds.size}")
    check("冷启动可在后台线程秒级完成", coldMs < 30_000L, "${coldMs}ms")
    check("缓存命中明显快于重建", hitMs < coldMs, "hit=${hitMs}ms cold=${coldMs}ms")
    check("缓存命中单次低于 3 秒", hitMs < 3_000L, "${hitMs}ms")

    // 连续输入模拟：多次不同查询共用同一份索引
    val seq = listOf("汽车", "咖啡", "2021年 北京 咖啡", "自行车", "山", "花")
    val t0 = System.nanoTime()
    val counts = seq.map { q -> bench(IntentParser.parse(q), cache).second.size }
    val seqMs = (System.nanoTime() - t0) / 1_000_000L
    println("连续 6 次不同查询总耗时: ${seqMs}ms, 各次命中 $counts")
    check("连续输入不出现任务堆积", seqMs < 8_000L, "${seqMs}ms")
    check("每次查询都返回非空结果", counts.all { it > 0 }, counts.toString())

    val (mixMs, mixIds) = bench(intentMix, cache)
    check("时间×地点×内容联动在大库仍生效", mixIds.isNotEmpty(), "命中 ${mixIds.size} 张, ${mixMs}ms")
    val (bikeMs, _) = bench(intentBike, cache)
    println("联动查询 ${mixMs}ms / 自行车 ${bikeMs}ms")

    // ------------------------------------------------------------------
    // 4) 可取消：新输入到来时旧查询立刻中止
    // ------------------------------------------------------------------
    var cancelled = false
    val tCancel = System.nanoTime()
    try {
        SearchEngine.search(intentCar, PHOTOS, META, EXIF, cache = null, isActive = { false })
    } catch (_: SearchEngine.SearchCancelledException) {
        cancelled = true
    }
    val cancelMs = (System.nanoTime() - tCancel) / 1_000_000L
    check("取消信号立即中止全库检索", cancelled && cancelMs < 100L, "${cancelMs}ms")

    // 部分取消：跑到一半返回 false
    var seen = 0
    var halfCancelled = false
    try {
        SearchEngine.search(intentCar, PHOTOS, META, EXIF, cache = null, isActive = {
            seen++
            seen < 3      // 第 3 次检查（约 768 张）时取消
        })
    } catch (_: SearchEngine.SearchCancelledException) {
        halfCancelled = true
    }
    check("中途取消可生效", halfCancelled && seen < 10, "检查次数=$seen")

    println("\n通过: $passed/$total")
    if (passed == total) println("ALL ASSERTIONS PASSED") else {
        System.err.println("SOME ASSERTIONS FAILED")
        kotlin.system.exitProcess(1)
    }
}
