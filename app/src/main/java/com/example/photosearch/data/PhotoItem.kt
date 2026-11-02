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
    val isAnalyzed: Boolean = false
)
