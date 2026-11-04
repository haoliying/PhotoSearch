package com.example.photosearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists photo analysis results (PhotoMetadata) to local storage.
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
        private const val KEY_METADATA = "metadata"
        private const val CURRENT_VERSION = 2
    }

    data class StoredAnalysis(
        val totalPhotos: Int,
        val analyzedCount: Int,
        val isComplete: Boolean,
        val metadataMap: Map<Long, PhotoMetadata>
    )

    fun saveSegment(
        totalPhotos: Int,
        analyzedCount: Int,
        isComplete: Boolean,
        metadataMap: Map<Long, PhotoMetadata>
    ) {
        synchronized(lock) {
            try {
                val json = JSONObject().apply {
                    put(KEY_VERSION, CURRENT_VERSION)
                    put(KEY_TOTAL_PHOTOS, totalPhotos)
                    put(KEY_ANALYZED_COUNT, analyzedCount)
                    put(KEY_IS_COMPLETE, isComplete)

                    val resultsArray = JSONArray()
                    metadataMap.forEach { (photoId, metadata) ->
                        resultsArray.put(JSONObject().apply {
                            put(KEY_PHOTO_ID, photoId)
                            put(KEY_METADATA, serializeMetadata(metadata))
                        })
                    }
                    put(KEY_RESULTS, resultsArray)
                }

                // Write atomically: write to temp file then rename
                val tempFile = File(storeFile.parent, "${FILE_NAME}.tmp")
                tempFile.writeText(json.toString())
                tempFile.renameTo(storeFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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

                val metadataMap = mutableMapOf<Long, PhotoMetadata>()
                val resultsArray = json.getJSONArray(KEY_RESULTS)
                for (i in 0 until resultsArray.length()) {
                    val entry = resultsArray.getJSONObject(i)
                    val photoId = entry.getLong(KEY_PHOTO_ID)
                    val metadataJson = entry.getJSONObject(KEY_METADATA)
                    metadataMap[photoId] = deserializeMetadata(metadataJson)
                }

                StoredAnalysis(totalPhotos, analyzedCount, isComplete, metadataMap)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            if (storeFile.exists()) {
                storeFile.delete()
            }
        }
    }

    fun hasProgress(): Boolean {
        return storeFile.exists() && load()?.let { !it.isComplete && it.analyzedCount > 0 } == true
    }

    // --- Serialization ---

    private fun serializeMetadata(m: PhotoMetadata): JSONObject {
        return JSONObject().apply {
            put("photoId", m.photoId)
            put("sceneType", m.sceneType.name)
            put("isOutdoor", m.isOutdoor)
            put("hasText", m.hasText)
            put("width", m.width)
            put("height", m.height)
            put("isAnalyzed", m.isAnalyzed)
            put("description", m.description)

            // VLM keywords
            put("vlmKeywords", JSONArray().apply {
                m.vlmKeywords.forEach { put(it) }
            })

            // Labels
            put("labels", JSONArray().apply {
                m.labels.forEach { l ->
                    put(JSONObject().apply {
                        put("text", l.text)
                        put("confidence", l.confidence.toDouble())
                    })
                }
            })

            // Detected objects
            put("objects", JSONArray().apply {
                m.detectedObjects.forEach { o ->
                    put(JSONObject().apply {
                        put("label", o.label)
                        put("confidence", o.confidence.toDouble())
                        o.boundingBox?.let { bb ->
                            put("bbox", JSONObject().apply {
                                put("left", bb.left)
                                put("top", bb.top)
                                put("width", bb.width)
                                put("height", bb.height)
                            })
                        }
                    })
                }
            })

            // Faces
            put("faces", JSONArray().apply {
                m.faces.forEach { f ->
                    put(JSONObject().apply {
                        put("smile", f.smilingProbability.toDouble())
                        put("leftEye", f.leftEyeOpenProbability.toDouble())
                        put("rightEye", f.rightEyeOpenProbability.toDouble())
                        put("facing", f.facing.name)
                    })
                }
            })

            // Dominant colors
            put("colors", JSONArray().apply {
                m.dominantColors.forEach { put(it) }
            })
        }
    }

    private fun deserializeMetadata(json: JSONObject): PhotoMetadata {
        val labels = mutableListOf<PhotoLabel>()
        val labelsArray = json.optJSONArray("labels")
        if (labelsArray != null) {
            for (i in 0 until labelsArray.length()) {
                val l = labelsArray.getJSONObject(i)
                labels.add(PhotoLabel(l.getString("text"), l.getDouble("confidence").toFloat()))
            }
        }

        val objects = mutableListOf<DetectedObject>()
        val objectsArray = json.optJSONArray("objects")
        if (objectsArray != null) {
            for (i in 0 until objectsArray.length()) {
                val o = objectsArray.getJSONObject(i)
                val bbox = if (o.has("bbox")) {
                    val bb = o.getJSONObject("bbox")
                    BoundingBox(bb.getInt("left"), bb.getInt("top"), bb.getInt("width"), bb.getInt("height"))
                } else null
                objects.add(DetectedObject(o.getString("label"), o.getDouble("confidence").toFloat(), bbox))
            }
        }

        val faces = mutableListOf<FaceInfo>()
        val facesArray = json.optJSONArray("faces")
        if (facesArray != null) {
            for (i in 0 until facesArray.length()) {
                val f = facesArray.getJSONObject(i)
                faces.add(FaceInfo(
                    smilingProbability = f.getDouble("smile").toFloat(),
                    leftEyeOpenProbability = f.getDouble("leftEye").toFloat(),
                    rightEyeOpenProbability = f.getDouble("rightEye").toFloat(),
                    facing = try { FaceFacing.valueOf(f.getString("facing")) } catch (e: Exception) { FaceFacing.UNKNOWN }
                ))
            }
        }

        val colors = mutableListOf<Int>()
        val colorsArray = json.optJSONArray("colors")
        if (colorsArray != null) {
            for (i in 0 until colorsArray.length()) {
                colors.add(colorsArray.getInt(i))
            }
        }

        val vlmKeywords = mutableListOf<String>()
        val vlmKeywordsArray = json.optJSONArray("vlmKeywords")
        if (vlmKeywordsArray != null) {
            for (i in 0 until vlmKeywordsArray.length()) {
                vlmKeywords.add(vlmKeywordsArray.getString(i))
            }
        }

        return PhotoMetadata(
            photoId = json.optLong("photoId", 0),
            labels = labels,
            detectedObjects = objects,
            faces = faces,
            sceneType = try { SceneType.valueOf(json.getString("sceneType")) } catch (e: Exception) { SceneType.UNKNOWN },
            dominantColors = colors,
            isOutdoor = if (json.has("isOutdoor") && !json.isNull("isOutdoor")) json.getBoolean("isOutdoor") else null,
            hasText = json.optBoolean("hasText", false),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            isAnalyzed = json.optBoolean("isAnalyzed", true),
            description = json.optString("description", ""),
            vlmKeywords = vlmKeywords
        )
    }
}
