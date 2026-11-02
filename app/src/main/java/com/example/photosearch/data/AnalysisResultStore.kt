package com.example.photosearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists photo analysis results to local storage.
 * Saves incrementally after each segment to prevent data loss on crash/exit.
 */
class AnalysisResultStore(context: Context) {

    private val storeFile = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    companion object {
        private const val FILE_NAME = "analysis_results.json"
        private const val KEY_VERSION = "version"
        private const val KEY_TOTAL_PHOTOS = "totalPhotos"
        private const val KEY_ANALYZED_COUNT = "analyzedCount"
        private const val KEY_IS_COMPLETE = "isComplete"
        private const val KEY_RESULTS = "results"
        private const val KEY_PHOTO_ID = "photoId"
        private const val KEY_LABELS = "labels"
        private const val KEY_LABEL_TEXT = "text"
        private const val KEY_LABEL_CONFIDENCE = "confidence"
        private const val CURRENT_VERSION = 1
    }

    data class StoredAnalysis(
        val totalPhotos: Int,
        val analyzedCount: Int,
        val isComplete: Boolean,
        val results: Map<Long, List<PhotoLabel>>
    )

    /**
     * Save analysis results incrementally.
     * Call this after each segment completes.
     */
    fun saveSegment(
        totalPhotos: Int,
        analyzedCount: Int,
        isComplete: Boolean,
        results: Map<Long, List<PhotoLabel>>
    ) {
        synchronized(lock) {
            try {
                val json = JSONObject().apply {
                    put(KEY_VERSION, CURRENT_VERSION)
                    put(KEY_TOTAL_PHOTOS, totalPhotos)
                    put(KEY_ANALYZED_COUNT, analyzedCount)
                    put(KEY_IS_COMPLETE, isComplete)

                    val resultsArray = JSONArray()
                    results.forEach { (photoId, labels) ->
                        val photoJson = JSONObject().apply {
                            put(KEY_PHOTO_ID, photoId)
                            put(KEY_LABELS, JSONArray().apply {
                                labels.forEach { label ->
                                    put(JSONObject().apply {
                                        put(KEY_LABEL_TEXT, label.text)
                                        put(KEY_LABEL_CONFIDENCE, label.confidence.toDouble())
                                    })
                                }
                            })
                        }
                        resultsArray.put(photoJson)
                    }
                    put(KEY_RESULTS, resultsArray)
                }

                storeFile.writeText(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Load previously saved analysis results.
     * Returns null if no saved data exists or data is corrupted.
     */
    fun load(): StoredAnalysis? {
        synchronized(lock) {
            return try {
                if (!storeFile.exists()) return null

                val json = JSONObject(storeFile.readText())
                val version = json.optInt(KEY_VERSION, 0)
                if (version != CURRENT_VERSION) return null

                val totalPhotos = json.getInt(KEY_TOTAL_PHOTOS)
                val analyzedCount = json.getInt(KEY_ANALYZED_COUNT)
                val isComplete = json.getBoolean(KEY_IS_COMPLETE)

                val results = mutableMapOf<Long, List<PhotoLabel>>()
                val resultsArray = json.getJSONArray(KEY_RESULTS)
                for (i in 0 until resultsArray.length()) {
                    val photoJson = resultsArray.getJSONObject(i)
                    val photoId = photoJson.getLong(KEY_PHOTO_ID)
                    val labelsArray = photoJson.getJSONArray(KEY_LABELS)
                    val labels = mutableListOf<PhotoLabel>()
                    for (j in 0 until labelsArray.length()) {
                        val labelJson = labelsArray.getJSONObject(j)
                        labels.add(
                            PhotoLabel(
                                text = labelJson.getString(KEY_LABEL_TEXT),
                                confidence = labelJson.getDouble(KEY_LABEL_CONFIDENCE).toFloat()
                            )
                        )
                    }
                    results[photoId] = labels
                }

                StoredAnalysis(totalPhotos, analyzedCount, isComplete, results)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Clear all saved results (e.g., when user wants to re-analyze).
     */
    fun clear() {
        synchronized(lock) {
            if (storeFile.exists()) {
                storeFile.delete()
            }
        }
    }

    /**
     * Check if there's saved progress that can be resumed.
     */
    fun hasProgress(): Boolean {
        return storeFile.exists() && load()?.let { !it.isComplete && it.analyzedCount > 0 } == true
    }
}
