package com.example.photosearch.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 一张照片的 EXIF / 时空元数据。
 *
 * v3.6.0：拍摄时间 + GPS + 地名。
 * v3.7.0：补齐 ExifInterface 可读的其余字段（相机、镜头、曝光、闪光灯、
 *         白平衡、方向、分辨率、海拔、文件格式与大小），并统一生成中文可检索标签。
 *
 * 设计要点：独立于 AI 分析结果存储。这些都是确定性数据，不需要 VLM 或 ML Kit，
 * 装上新版即可检索；也不会破坏已有的 analysis_results.json 缓存。
 */
data class PhotoExif(
    val photoId: Long,
    /** 拍摄时间，毫秒；0 表示未知。优先 EXIF DateTimeOriginal，其次文件名，最后 MediaStore。 */
    val dateTaken: Long = 0L,
    /** exif | filename | mediastore | unknown */
    val dateSource: String = "unknown",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val country: String = "",
    val adminArea: String = "",      // 省 / 直辖市
    val locality: String = "",       // 市
    val subLocality: String = "",    // 区 / 县
    val thoroughfare: String = "",   // 街道
    val poi: String = "",            // 兴趣点 / 建筑名（Geocoder featureName）
    /** 用户手动标注的地点：无 GPS 的照片也能参与地点检索。 */
    val manualLabel: String = "",
    /** geocoder | gps-city | manual | "" */
    val placeSource: String = "",

    // ---------------- v3.7.0 扩展字段 ----------------
    /** 相机厂商 / 型号；手机照片一般写在 Make / Model 里。 */
    val cameraMake: String = "",
    val cameraModel: String = "",
    val lensMake: String = "",
    val lensModel: String = "",
    /** 后期软件（如 Photoshop、某手机影像引擎）。 */
    val software: String = "",
    val artist: String = "",
    val imageDescription: String = "",
    /** 光圈 F 值，0 表示未知。 */
    val fNumber: Double = 0.0,
    /** 曝光时间（秒），0 表示未知。 */
    val exposureTime: Double = 0.0,
    /** 感光度，0 表示未知。 */
    val iso: Int = 0,
    /** 实际焦距 mm / 等效 35mm 焦距，0 表示未知。 */
    val focalLength: Double = 0.0,
    val focalLength35: Int = 0,
    /** ExifInterface.TAG_FLASH 原值，-1 表示未知。 */
    val flash: Int = -1,
    /** ExifInterface.TAG_WHITE_BALANCE 原值（0 自动 / 1 手动），-1 表示未知。 */
    val whiteBalance: Int = -1,
    val exposureBias: Double? = null,
    val meteringMode: Int = -1,
    /** 1 正常 / 6 顺时针 90° / 3 180° / 8 逆时针 90°。 */
    val orientation: Int = 1,
    /** 像素宽高（未考虑旋转），0 表示未知。 */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** GPS 海拔（米）。 */
    val altitude: Double? = null,
    /** GPS 日期戳 / 原始时间的时区偏移。 */
    val gpsDateStamp: String = "",
    val offsetTimeOriginal: String = "",
    /** 文件信息（MediaStore 提供，EXIF 里通常没有）。 */
    val mimeType: String = "",
    val fileSize: Long = 0L,
    val fileExtension: String = "",
    /** 缓存结构版本：< 2 的旧缓存会在下次扫描时重新提取以补齐扩展字段。 */
    val schemaVersion: Int = 2
) {
    val hasTime: Boolean get() = dateTaken > 0L
    val hasGps: Boolean get() = latitude != null && longitude != null
    val hasPlace: Boolean get() = searchableLocation().isNotBlank()
    val hasExtendedInfo: Boolean get() = schemaVersion >= 2

    /** 人类可读地点：手动标注优先，否则按 国家-省-市-区-街道-POI 去重拼接。 */
    fun placeLabel(): String {
        if (manualLabel.isNotBlank()) return manualLabel.trim()
        return listOf(country, adminArea, locality, subLocality, thoroughfare, poi)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    /** 供模糊匹配的全部地名文本（大小写由匹配器统一处理）。 */
    fun searchableLocation(): String = listOf(
        country, adminArea, locality, subLocality, thoroughfare, poi, manualLabel
    ).map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")

    /** 用于地点建议列表的短标签：市 > 区 > 省。 */
    fun shortPlaceLabel(): String = when {
        manualLabel.isNotBlank() -> manualLabel.trim()
        locality.isNotBlank() -> locality.trim()
        subLocality.isNotBlank() -> subLocality.trim()
        adminArea.isNotBlank() -> adminArea.trim()
        poi.isNotBlank() -> poi.trim()
        else -> ""
    }

    // ------------------------------------------------------------------
    // v3.7.0：展示用格式化
    // ------------------------------------------------------------------

    fun dateTimeLabel(): String {
        if (!hasTime) return "拍摄时间未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(dateTaken)
    }

    /** 中文日期（用于时间条件提示，例如 2023年6月15日）。 */
    fun chineseDateLabel(): String {
        if (!hasTime) return "时间未知"
        val c = Calendar.getInstance().apply { timeInMillis = dateTaken }
        return "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 设备名称：把 Make/Model 合并去重，并归一常见品牌中文名。 */
    fun cameraLabel(): String {
        val make = cameraMake.trim()
        val model = cameraModel.trim()
        val brand = normalizeBrand(make)
        if (model.isBlank()) return brand
        if (brand.isBlank()) return model
        // Model 常已包含厂商名（如 "HUAWEI P60 Pro"），避免重复
        return if (model.contains(brand, ignoreCase = true) || model.contains(make, ignoreCase = true)) model
        else "$brand $model"
    }

    /** 镜头描述（没有则返回空串）。 */
    fun lensLabel(): String = listOf(lensMake.trim(), lensModel.trim())
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.US) }
        .joinToString(" ")

    /** 考虑 EXIF 旋转后的实际显示尺寸。 */
    fun dimensionLabel(): String {
        if (imageWidth <= 0 || imageHeight <= 0) return ""
        val rotated = orientation == 6 || orientation == 8
        val w = if (rotated) imageHeight else imageWidth
        val h = if (rotated) imageWidth else imageHeight
        return "$w × $h"
    }

    /** 横幅 / 竖幅 / 方形。 */
    fun orientationLabel(): String {
        if (imageWidth <= 0 || imageHeight <= 0) return ""
        val rotated = orientation == 6 || orientation == 8
        val w = if (rotated) imageHeight else imageWidth
        val h = if (rotated) imageWidth else imageHeight
        return when {
            w > h -> "横幅"
            h > w -> "竖幅"
            else -> "方形"
        }
    }

    /** 文件格式：优先扩展名，其次 MIME。 */
    fun formatLabel(): String {
        val ext = fileExtension.trim().trimStart('.').uppercase(Locale.US)
        if (ext.isNotBlank()) return when (ext) {
            "JPG", "JPEG" -> "JPEG"
            "HEIF" -> "HEIC"
            "DNG", "RAW", "ARW", "CR2", "NEF" -> "RAW"
            else -> ext
        }
        val mime = mimeType.lowercase(Locale.US)
        return when {
            mime.contains("jpeg") -> "JPEG"
            mime.contains("png") -> "PNG"
            mime.contains("heic") || mime.contains("heif") -> "HEIC"
            mime.contains("webp") -> "WEBP"
            mime.contains("gif") -> "GIF"
            mime.contains("bmp") -> "BMP"
            mime.contains("raw") || mime.contains("dng") -> "RAW"
            else -> ""
        }
    }

    fun fileSizeLabel(): String {
        if (fileSize <= 0L) return ""
        val kb = fileSize / 1024.0
        return when {
            kb < 1024 -> String.format(Locale.US, "%.0f KB", kb)
            kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024)
            else -> String.format(Locale.US, "%.2f GB", kb / 1024 / 1024)
        }
    }

    /** 曝光参数一行：f/1.8 · 1/120s · ISO 100 · 26mm。 */
    fun exposureLabel(): String {
        val parts = mutableListOf<String>()
        if (fNumber > 0) parts += "f/${trimNumber(fNumber)}"
        if (exposureTime > 0) parts += shutterLabel(exposureTime)
        if (iso > 0) parts += "ISO $iso"
        if (focalLength > 0) parts += "${trimNumber(focalLength)}mm"
        return parts.joinToString(" · ")
    }

    /** 详情页 / 列表页的「拍摄参数」键值行，只返回有值的行。 */
    fun exifInfoRows(): List<Pair<String, String>> = buildList {
        cameraLabel().takeIf { it.isNotBlank() }?.let { add("相机设备" to it) }
        lensLabel().takeIf { it.isNotBlank() }?.let { add("镜头" to it) }
        formatLabel().takeIf { it.isNotBlank() }?.let { add("文件格式" to it) }
        dimensionLabel().takeIf { it.isNotBlank() }?.let { add("分辨率" to it) }
        orientationLabel().takeIf { it.isNotBlank() }?.let { add("画幅" to it) }
        fileSizeLabel().takeIf { it.isNotBlank() }?.let { add("文件大小" to it) }
        exposureLabel().takeIf { it.isNotBlank() }?.let { add("曝光参数" to it) }
        if (focalLength35 > 0) add("等效焦距" to "${focalLength35}mm")
        flashLabel()?.let { add("闪光灯" to it) }
        whiteBalanceLabel()?.let { add("白平衡" to it) }
        exposureBias?.let {
            val sign = if (it > 0) "+" else ""
            add("曝光补偿" to "$sign${trimNumber(it)} EV")
        }
        altitude?.let { add("海拔" to "${trimNumber(it)} m") }
        software.trim().takeIf { it.isNotBlank() }?.let { add("处理软件" to it) }
        artist.trim().takeIf { it.isNotBlank() }?.let { add("作者" to it) }
        imageDescription.trim().takeIf { it.isNotBlank() }?.let { add("图像描述" to it) }
        offsetTimeOriginal.trim().takeIf { it.isNotBlank() }?.let { add("原始时区" to it) }
    }

    fun flashLabel(): String? = when {
        flash < 0 -> null
        flash and 0x01 == 0x01 -> "已闪光"
        else -> "未闪光"
    }

    fun whiteBalanceLabel(): String? = when (whiteBalance) {
        0 -> "自动白平衡"
        1 -> "手动白平衡"
        else -> null
    }

    // ------------------------------------------------------------------
    // v3.7.0：中文可检索标签
    // ------------------------------------------------------------------

    /**
     * 把 EXIF 信息转换为中文检索标签，交给全文索引。
     * 这样「佳能拍的」「竖幅」「广角」「大光圈」「JPEG」「春天」「清晨」
     * 都能作为查询条件命中照片，而不必依赖 AI 描述。
     */
    fun searchableExifTags(): Set<String> {
        val tags = mutableSetOf<String>()

        // 设备
        val brand = normalizeBrand(cameraMake)
        if (brand.isNotBlank()) tags += brand
        cameraModel.trim().takeIf { it.isNotBlank() }?.let { tags += it }
        cameraLabel().takeIf { it.isNotBlank() }?.let { tags += it }
        lensLabel().takeIf { it.isNotBlank() }?.let { tags += it }
        software.trim().takeIf { it.isNotBlank() }?.let { tags += it }

        // 格式 / 画幅 / 尺寸
        formatLabel().takeIf { it.isNotBlank() }?.let {
            tags += it
            tags += "${it}格式"
        }
        orientationLabel().takeIf { it.isNotBlank() }?.let { tags += it }
        if (orientationLabel() == "横幅") tags += "横图"
        if (orientationLabel() == "竖幅") tags += "竖图"
        val pixels = imageWidth.toLong() * imageHeight.toLong()
        if (pixels >= 12_000_000L) tags += "高像素"
        if (pixels in 1L..1_000_000L) tags += "低分辨率"

        // 镜头与曝光
        if (focalLength35 in 1..35) { tags += "广角"; tags += "广角镜头" }
        if (focalLength35 >= 70) { tags += "长焦"; tags += "长焦镜头" }
        if (focalLength35 in 36..69) tags += "标准镜头"
        if (fNumber in 0.1..2.8) { tags += "大光圈"; tags += "虚化" }
        if (exposureTime >= 1.0) { tags += "长曝光"; tags += "慢门" }
        if (exposureTime in 0.0..0.001) tags += "高速快门"
        if (iso >= 1600) { tags += "高感光度"; tags += "高ISO" }
        if (iso in 1..200) tags += "低感光度"
        flashLabel()?.let { tags += it }
        whiteBalanceLabel()?.let { tags += it }
        altitude?.let { if (it >= 1500) tags += "高海拔" }

        // 时间派生标签
        if (hasTime) {
            val c = Calendar.getInstance().apply { timeInMillis = dateTaken }
            val y = c.get(Calendar.YEAR)
            val mo = c.get(Calendar.MONTH) + 1
            tags += "${y}年"
            tags += "${mo}月"
            tags += "${y}年${mo}月"
            tags += seasonOf(mo)
            tags += timeOfDayOf(c.get(Calendar.HOUR_OF_DAY))
            val week = c.get(Calendar.DAY_OF_WEEK)
            if (week == Calendar.SATURDAY || week == Calendar.SUNDAY) tags += "周末"
        }
        return tags.filter { it.isNotBlank() }.toSet()
    }

    /** 供 UI 直接展示的中文标签（按稳定顺序，去掉过长字段）。 */
    fun displayTags(): List<String> = buildList {
        formatLabel().takeIf { it.isNotBlank() }?.let { add(it) }
        orientationLabel().takeIf { it.isNotBlank() }?.let { add(it) }
        if (focalLength35 in 1..35) add("广角")
        if (focalLength35 >= 70) add("长焦")
        if (fNumber in 0.1..2.8) add("大光圈")
        if (exposureTime >= 1.0) add("长曝光")
        if (iso >= 1600) add("高感光度")
        if (flash >= 0 && flash and 0x01 == 0x01) add("已闪光")
        if (hasTime) {
            val c = Calendar.getInstance().apply { timeInMillis = dateTaken }
            add(seasonOf(c.get(Calendar.MONTH) + 1))
        }
        normalizeBrand(cameraMake).takeIf { it.isNotBlank() }?.let { add(it) }
    }.distinct()

    companion object {
        private val BRANDS: List<Pair<String, String>> = listOf(
            "apple" to "苹果", "iphone" to "苹果", "huawei" to "华为", "honor" to "荣耀",
            "xiaomi" to "小米", "redmi" to "红米", "oppo" to "OPPO", "oneplus" to "一加",
            "vivo" to "vivo", "iqoo" to "iQOO", "samsung" to "三星", "google" to "谷歌",
            "pixel" to "谷歌", "meizu" to "魅族", "realme" to "真我", "motorola" to "摩托罗拉",
            "nokia" to "诺基亚", "canon" to "佳能", "nikon" to "尼康", "sony" to "索尼",
            "fujifilm" to "富士", "fuji" to "富士", "panasonic" to "松下", "leica" to "徕卡",
            "olympus" to "奥林巴斯", "pentax" to "宾得", "ricoh" to "理光", "hasselblad" to "哈苏",
            "dji" to "大疆", "gopro" to "GoPro", "casio" to "卡西欧", "kodak" to "柯达",
            "lenovo" to "联想", "asus" to "华硕", "htc" to "HTC", "lg" to "LG",
            "sharp" to "夏普", "zte" to "中兴", "coolpad" to "酷派", "hisense" to "海信",
            "blackberry" to "黑莓", "insta360" to "影石"
        ).sortedByDescending { it.first.length }

        /** 把英文厂商名归一为中文品牌标签；未收录时返回原文。 */
        fun normalizeBrand(make: String): String {
            val m = make.trim()
            if (m.isBlank()) return ""
            val lower = m.lowercase(Locale.US)
            for ((key, cn) in BRANDS) if (lower.contains(key)) return cn
            return m
        }

        fun seasonOf(month: Int): String = when (month) {
            3, 4, 5 -> "春季"
            6, 7, 8 -> "夏季"
            9, 10, 11 -> "秋季"
            else -> "冬季"
        }

        fun timeOfDayOf(hour: Int): String = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..20 -> "傍晚"
            else -> "夜间"
        }

        private fun trimNumber(v: Double): String {
            val r = (Math.round(v * 100.0) / 100.0).toString()
            return if (r.endsWith(".0")) r.dropLast(2) else r
        }

        private fun shutterLabel(seconds: Double): String = when {
            seconds >= 1.0 -> "${trimNumber(seconds)}s"
            else -> {
                val denom = Math.round(1.0 / seconds)
                "1/${denom}s"
            }
        }
    }
}
