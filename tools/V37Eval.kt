import com.example.photosearch.data.LabelTranslator
import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoLabel
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.chineseDisplayTags
import com.example.photosearch.data.toSearchableTags
import com.example.photosearch.search.ConceptRegistry
import com.example.photosearch.search.IntentParser
import com.example.photosearch.search.SearchEngine
import com.example.photosearch.search.TimeQueryParser
import java.util.Calendar
import java.util.TimeZone

/**
 * v3.7.0 离线评测：中文标签 / EXIF 扩展标签 / 时间条件格式 / 旧版回归。
 *
 * 编译（Kotlin 1.9.22 + AndroidStubs.kt）：
 *   kotlinc -d out Stub_*.kt PhotoItem.kt PhotoMetadata.kt PhotoExif.kt ExifReader.kt \
 *     PhotoExifStore.kt PhotoRepository.kt PlaceCatalog.kt TimeQueryParser.kt \
 *     LocationMatcher.kt SearchIntent.kt IntentParser.kt SearchEngine.kt \
 *     ConceptRegistry.kt V37Eval.kt
 *   java -cp out:kotlin-stdlib.jar V37EvalKt
 */
private val ZONE: TimeZone = TimeZone.getDefault()

private fun ts(y: Int, m: Int, d: Int, h: Int = 12): Long {
    val c = Calendar.getInstance(ZONE)
    c.clear()
    c.set(y, m - 1, d, h, 0, 0)
    return c.timeInMillis
}

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

fun main() {
    // ------------------------------------------------------------------
    // 1) 标签中文化
    // ------------------------------------------------------------------
    check("英文标签 car → 中文", LabelTranslator.toChinese("Car") == "车")
    check("英文标签 Laptop → 中文", LabelTranslator.toChinese("Laptop") == "笔记本电脑")
    check("已是中文原样返回", LabelTranslator.toChinese("汽车") == "汽车")
    check("词表外英文不冒充中文", LabelTranslator.toChinese("zzunknown") == null)
    check("复合词 swimming pool → 中文", LabelTranslator.toChinese("Swimming Pool") == "游泳池")

    val meta = PhotoMetadata(
        photoId = 1L,
        labels = listOf(
            PhotoLabel("Car", 0.92f),
            PhotoLabel("Laptop", 0.85f),
            PhotoLabel("zzunknown", 0.70f)
        ),
        detectedObjects = emptyList(),
        vlmKeywords = listOf("轿车", "街景"),
        description = "一辆车停在路边",
        isAnalyzed = true
    )
    val display = meta.chineseDisplayTags()
    val noLatin = display.none { t -> t.any { it in 'A'..'Z' || it in 'a'..'z' } }
    check("展示标签不含英文", noLatin, "实际=$display")
    check("展示标签含 VLM 中文词与翻译", display.contains("轿车") && display.contains("车") &&
            display.contains("笔记本电脑"), "实际=$display")
    check("toSearchableTags 仍保留英文原词（英文查询可用）",
        meta.toSearchableTags().contains("car"))

    // ------------------------------------------------------------------
    // 2) EXIF 扩展字段与中文标签
    // ------------------------------------------------------------------
    val ex = PhotoExif(
        photoId = 1L,
        dateTaken = ts(2023, 4, 15, 10),
        dateSource = "exif",
        cameraMake = "Canon",
        cameraModel = "EOS R6",
        lensModel = "RF50mm F1.2 L USM",
        software = "Lightroom",
        fNumber = 1.8,
        exposureTime = 1.0 / 125.0,
        iso = 100,
        focalLength = 50.0,
        focalLength35 = 50,
        flash = 0x01,
        whiteBalance = 0,
        orientation = 6,
        imageWidth = 4032,
        imageHeight = 3024,
        altitude = 32.0,
        mimeType = "image/jpeg",
        fileSize = 3_200_000L,
        fileExtension = "JPG"
    )
    check("相机品牌归一中文", ex.cameraLabel().contains("佳能"), ex.cameraLabel())
    check("镜头信息", ex.lensLabel().contains("RF50mm"))
    check("格式 JPEG", ex.formatLabel() == "JPEG")
    check("旋转后尺寸", ex.dimensionLabel() == "3024 × 4032", ex.dimensionLabel())
    check("画幅判定", ex.orientationLabel() == "竖幅")
    check("文件大小", ex.fileSizeLabel().contains("MB"), ex.fileSizeLabel())
    check("曝光参数行", ex.exposureLabel().contains("f/1.8") &&
            ex.exposureLabel().contains("ISO 100"), ex.exposureLabel())
    check("闪光灯 / 白平衡", ex.flashLabel() == "已闪光" && ex.whiteBalanceLabel() == "自动白平衡")
    check("详情页信息行完整", ex.exifInfoRows().size >= 8, "行数=${ex.exifInfoRows().size}")

    val et = ex.searchableExifTags()
    val expectedTags = listOf("佳能", "竖幅", "大光圈", "已闪光", "春季", "JPEG", "高像素", "Lightroom")
    check("EXIF 中文标签齐全", expectedTags.all { et.contains(it) },
        "缺失=${expectedTags.filterNot { et.contains(it) }} / 实际=$et")
    check("EXIF 展示标签含相机与画幅", ex.displayTags().contains("佳能") &&
            ex.displayTags().contains("竖幅"), ex.displayTags().toString())

    // 广角 / 长焦 / 长曝光 / 高感光也应生成标签
    val exWide = ex.copy(focalLength35 = 24, fNumber = 1.4, exposureTime = 2.0, iso = 3200, flash = 0)
    val etw = exWide.searchableExifTags()
    check("广角/长曝光/高感光度标签",
        listOf("广角", "长曝光", "高感光度").all { it in etw }, etw.toString())

    // ------------------------------------------------------------------
    // 3) EXIF 标签进入全文索引并可检索
    // ------------------------------------------------------------------
    val photo = PhotoItem(1L, android.net.Uri("p1"), 0L, "IMG_20230415.jpg")
    val meta1 = PhotoMetadata(photoId = 1L, description = "城市街景", isAnalyzed = true)
    val exifMap = mapOf(1L to ex)
    val metaMap = mapOf(1L to meta1)

    fun searchIds(q: String, photos: List<PhotoItem>, metas: Map<Long, PhotoMetadata>,
                  exifs: Map<Long, PhotoExif>): List<Long> =
        SearchEngine.search(IntentParser.parse(q), photos, metas, exifs).map { it.photo.id }

    for (q in listOf("佳能", "竖幅", "JPEG", "大光圈", "自动白平衡")) {
        val ids = searchIds(q, listOf(photo), metaMap, exifMap)
        check("按 EXIF 标签检索「$q」", ids == listOf(1L), "实际=$ids")
    }
    // 「春季」在自然语言里会被解析成季节时间条件（今年春季），因此用索引直接验证标签存在
    val springIdx = ConceptRegistry.buildSearchIndex(meta1, ex)
    check("季节标签「春季」已编入索引", springIdx.score("春季") >= 0.55f,
        "score=${springIdx.score("春季")}")

    // 索引权重足够（>= TEXT_MIN_EVIDENCE），且不带相机信息时不误命中
    val idx = ConceptRegistry.buildSearchIndex(meta1, ex)
    check("索引中「佳能」证据足够", idx.score("佳能") >= 0.55f, "score=${idx.score("佳能")}")
    val ex2 = PhotoExif(photoId = 2L, dateTaken = ts(2022, 1, 1), dateSource = "mediastore")
    val photo2 = PhotoItem(2L, android.net.Uri("p2"), 0L, "p2.jpg")
    val meta2 = PhotoMetadata(photoId = 2L, description = "另一张街景", isAnalyzed = true)
    check("「佳能」不误命中无相机信息照片",
        searchIds("佳能", listOf(photo, photo2), mapOf(1L to meta1, 2L to meta2),
            mapOf(1L to ex, 2L to ex2)) == listOf(1L))

    // 时间×EXIF 标签联动
    val range = TimeQueryParser.fromDateStrings("2023-01-01", "2023-12-31")!!
    val intent = IntentParser.parse("佳能", nowMillis = ts(2026, 1, 1))
        .copy(timeRange = range)
    val combo = SearchEngine.search(intent, listOf(photo, photo2),
        mapOf(1L to meta1, 2L to meta2), mapOf(1L to ex, 2L to ex2))
    check("时间 AND EXIF 标签联动", combo.map { it.photo.id } == listOf(1L),
        "实际=${combo.map { it.photo.id }}")

    // ------------------------------------------------------------------
    // 4) 时间条件格式
    // ------------------------------------------------------------------
    val r1 = TimeQueryParser.fromDateStrings("2023-06-01", "2023-12-31")
    val r2 = TimeQueryParser.fromDateStrings("2023年6月1日", "2023年12月31日")
    val r3 = TimeQueryParser.fromDateStrings("2023/6/1", "2023/12/31")
    val r4 = TimeQueryParser.fromDateStrings("2023.6.1", "2023.12.31")
    val r5 = TimeQueryParser.fromDateStrings("20230601", "20231231")
    check("多种日期格式均可解析", listOf(r1, r2, r3, r4, r5).all { it != null },
        "r1=$r1 r2=$r2 r3=$r3 r4=$r4 r5=$r5")
    check("各格式结果一致", r1 != null && r2 != null && r3 != null && r4 != null && r5 != null &&
            listOf(r2, r3, r4, r5).all { it!!.startMillis == r1!!.startMillis && it.endExclusive == r1.endExclusive })

    val ym = TimeQueryParser.fromDateStrings("2023-06", "2023-12")
    check("支持只写年月（整月）", ym != null && ym!!.label.contains("至"), "label=${ym?.label}")

    val cn = r1!!.let { TimeQueryParser.formatChinese(it) }
    check("中文区间格式", cn.contains("2023年6月1日") && cn.contains("至") &&
            cn.contains("2023年12月31日"), cn)
    check("自定义区间 label 也是中文格式", r1.label.contains("至"), r1.label)
    check("快捷标签展示优先", TimeQueryParser.displayLabel(
        TimeQueryParser.thisYear(ts(2026, 1, 1))) == "今年")
    check("无 label 时回退中文日期", TimeQueryParser.displayLabel(
        r1.copy(label = "")).contains("至"))
    check("非法日期被拒绝", TimeQueryParser.fromDateStrings("abc", "2023-12-31") == null)
    check("起止颠倒被拒绝", TimeQueryParser.fromDateStrings("2023-12-31", "2023-01-01") == null)
    check("日期提示写明 yyyy-MM-dd", TimeQueryParser.DATE_FORMAT_HINT.contains("yyyy-MM-dd"))
    check("时间写法提示完整", TimeQueryParser.TIME_QUERY_HINT.contains("2023-06-01 到 2023-12-31"))

    // 自然语言时间解析不回退
    val natural = TimeQueryParser.parse("2023年在北京拍的汽车", ts(2026, 1, 1))
    check("自然语言年份仍可解析", natural.range != null && natural.remainder.contains("北京"),
        "remainder=${natural.remainder}")

    // ------------------------------------------------------------------
    println()
    println("通过: $passed/$total")
    if (passed != total) {
        println("SOME ASSERTIONS FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ALL ASSERTIONS PASSED")
}
