package com.example.photosearch.data

/**
 * 一张照片的 EXIF 时空元数据（v3.6.0 新增）。
 *
 * 设计要点：独立于 AI 分析结果存储。拍摄时间 / GPS 是确定性数据，不需要
 * VLM 或 ML Kit，因此装上新版即可检索；也不会破坏已有的 analysis_results.json
 * 缓存（用户无需重新分析照片）。
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
    val placeSource: String = ""
) {
    val hasTime: Boolean get() = dateTaken > 0L
    val hasGps: Boolean get() = latitude != null && longitude != null
    val hasPlace: Boolean get() = searchableLocation().isNotBlank()

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
}
