package com.example.photosearch.data

import android.net.Uri

data class PhotoLabel(
    val text: String,
    val confidence: Float
)

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val displayName: String,
    val labels: List<PhotoLabel> = emptyList(),
    val metadata: PhotoMetadata? = null,
    val isAnalyzed: Boolean = false,
    // v3.6.0：MediaStore 层的经纬度兜底（Android 9 及以下有效；Q+ 由 EXIF 原始流读取）
    val latitude: Double? = null,
    val longitude: Double? = null,
    // v3.7.0：MediaStore 直接提供的文件信息，列表视图与 EXIF 标签使用
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val mimeType: String = ""
)
