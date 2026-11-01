package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.photosearch.data.PhotoLabel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ImageAnalyzer(context: Context) {

    private val labeler: ImageLabeler

    init {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.3f)
            .build()
        labeler = ImageLabeling.getClient(options)
    }

    suspend fun analyzePhoto(uri: Uri, context: Context): List<PhotoLabel> {
        return withContext(Dispatchers.Default) {
            try {
                val bitmap = loadBitmap(uri, context) ?: return@withContext emptyList()
                val labels = detectLabels(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()
                labels
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun loadBitmap(uri: Uri, context: Context): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(inputStream, 1024, 1024)
                }
                inputStream.close()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        inputStream: java.io.InputStream,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // We can't easily get dimensions without decoding, use a fixed sample size
        return 4 // Downsample by 4x for analysis
    }

    private suspend fun detectLabels(bitmap: Bitmap): List<PhotoLabel> {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val photoLabels = labels.map { label ->
                        PhotoLabel(
                            text = label.text,
                            confidence = label.confidence
                        )
                    }
                    if (continuation.isActive) {
                        continuation.resume(photoLabels)
                    }
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
        }
    }

    fun close() {
        labeler.close()
    }
}
