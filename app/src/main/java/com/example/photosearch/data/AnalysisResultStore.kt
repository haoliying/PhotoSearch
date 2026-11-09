package com.example.photosearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists photo analysis results (PhotoMetadata) to local storage.
 *
 * v3.8.0：改为**增量行式存储**。
 * 旧实现每完成一个 segment 就把全部已分析照片序列化成一个 JSON 重新落盘，
 * 3 万张照片时是 O(N²) 磁盘写入，分析到后半段会明显拖慢整机。
 * 新实现只把本段新增的 photoId 追加成一行（O(新增数)），
 * totalPhotos / analyzedCount / isComplete 单独写一个小 meta 文件。
 * 旧版单文件在首次写入时一次性迁移，读取时自动兼容。
 */
class AnalysisResultStore(context: Context) {

    private val filesDir = context.filesDir
    private val legacyFile = File(filesDir, LEGACY_FILE)
    private val lineFile = File(filesDir, LINE_FILE)
    private val metaFile = File(filesDir, META_FILE)
    private val lock = Any()
    /** 本实例已写入的 id，避免重复追加；跨实例重复行在 load 时后写覆盖即可。 */
    private val writtenIds = HashSet<Long>()

    companion object {
        private const val LEGACY_FILE = "analysis_results.json"
        private const val LINE_FILE = "analysis_results_v3.jsonl"
        private const val META_FILE = "analysis_meta_v3.json"
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
                ensureMigrated()
                val fresh = metadataMap.filterKeys { it !in writtenIds }
                if (fresh.isNotEmpty()) {
                    val sb = StringBuilder()
                    fresh.forEach { (photoId, metadata) ->
                        sb.append(JSONObject().apply {
                            put(KEY_PHOTO_ID, photoId)
                            put(KEY_METADATA, serializeMetadata(metadata))
                        }.toString()).append('\n')
                    }
                    lineFile.appendText(sb.toString())
                    writtenIds.addAll(fresh.keys)
                }
                writeMeta(totalPhotos, analyzedCount, isComplete)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun load(): StoredAnalysis? {
        synchronized(lock) {
            return try {
                if (metaFile.exists() && lineFile.exists()) {
                    val meta = JSONObject(metaFile.readText())
                    if (meta.optInt(KEY_VERSION, 0) != CURRENT_VERSION) return null
                    val map = mutableMapOf<Long, PhotoMetadata>()
                    lineFile.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        try {
                            val o = JSONObject(line)
                            val id = o.getLong(KEY_PHOTO_ID)
                            map[id] = deserializeMetadata(o.getJSONObject(KEY_METADATA))
                        } catch (_: Exception) {
                            // 崩溃可能留下半行，跳过
                        }
                    }
                    writtenIds.clear()
                    writtenIds.addAll(map.keys)
                    return StoredAnalysis(
                        meta.getInt(KEY_TOTAL_PHOTOS),
                        meta.getInt(KEY_ANALYZED_COUNT),
                        meta.getBoolean(KEY_IS_COMPLETE),
                        map
                    )
                }
                if (!legacyFile.exists()) return null
                val json = JSONObject(legacyFile.readText())
                if (json.optInt(KEY_VERSION, 0) != CURRENT_VERSION) return null
                val map = mutableMapOf<Long, PhotoMetadata>()
                val arr = json.getJSONArray(KEY_RESULTS)
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val photoId = entry.getLong(KEY_PHOTO_ID)
                    map[photoId] = deserializeMetadata(entry.getJSONObject(KEY_METADATA))
                }
                writtenIds.clear()
                writtenIds.addAll(map.keys)
                StoredAnalysis(
                    json.getInt(KEY_TOTAL_PHOTOS),
                    json.getInt(KEY_ANALYZED_COUNT),
                    json.getBoolean(KEY_IS_COMPLETE),
                    map
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            writtenIds.clear()
            listOf(lineFile, metaFile, legacyFile).forEach { if (it.exists()) it.delete() }
        }
    }

    fun hasProgress(): Boolean = load()?.let { !it.isComplete && it.analyzedCount > 0 } == true

    // --- 内部：迁移与 meta ---

    private fun ensureMigrated() {
        if (lineFile.exists()) return
        val sb = StringBuilder()
        readLegacy()?.forEach { (id, m) ->
            sb.append(JSONObject().apply {
                put(KEY_PHOTO_ID, id)
                put(KEY_METADATA, serializeMetadata(m))
            }.toString()).append('\n')
            writtenIds.add(id)
        }
        val tmp = File(filesDir, "$LINE_FILE.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(lineFile)
        writeMeta(0, writtenIds.size, false)
    }

    private fun readLegacy(): Map<Long, PhotoMetadata>? {
        if (!legacyFile.exists()) return null
        return try {
            val json = JSONObject(legacyFile.readText())
            if (json.optInt(KEY_VERSION, 0) != CURRENT_VERSION) return null
            val arr = json.optJSONArray(KEY_RESULTS) ?: return null
            val map = mutableMapOf<Long, PhotoMetadata>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val id = entry.getLong(KEY_PHOTO_ID)
                map[id] = deserializeMetadata(entry.getJSONObject(KEY_METADATA))
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(totalPhotos: Int, analyzedCount: Int, isComplete: Boolean) {
        val json = JSONObject().apply {
            put(KEY_VERSION, CURRENT_VERSION)
            put(KEY_TOTAL_PHOTOS, totalPhotos)
            put(KEY_ANALYZED_COUNT, analyzedCount)
            put(KEY_IS_COMPLETE, isComplete)
        }
        val tmp = File(filesDir, "$META_FILE.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(metaFile)
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

            put("vlmKeywords", JSONArray().apply {
                m.vlmKeywords.forEach { put(it) }
            })

            put("labels", JSONArray().apply {
                m.labels.forEach { l ->
                    put(JSONObject().apply {
                        put("text", l.text)
                        put("confidence", l.confidence.toDouble())
                    })
                }
            })

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
