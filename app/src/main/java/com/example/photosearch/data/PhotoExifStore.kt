package com.example.photosearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * EXIF 时空元数据缓存（v3.6.0）。
 *
 * 独立于 analysis_results.json：读取 EXIF 不需要 AI 分析，也不影响旧缓存，
 * 用户升级后无需重新分析照片即可使用时间/地点筛选。
 * 断点续传友好：每批 25 张落盘一次，中断后已提取的部分不丢。
 */
class PhotoExifStore(context: Context) {

    private val storeFile = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    companion object {
        private const val FILE_NAME = "photo_exif.json"
        private const val VERSION = 1
    }

    fun load(): MutableMap<Long, PhotoExif> = synchronized(lock) {
        val out = mutableMapOf<Long, PhotoExif>()
        try {
            if (!storeFile.exists()) return@synchronized out
            val json = JSONObject(storeFile.readText())
            if (json.optInt("version", 0) != VERSION) return@synchronized out
            val arr = json.optJSONArray("items") ?: return@synchronized out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("photoId", -1)
                if (id < 0) continue
                out[id] = fromJson(id, o)
            }
        } catch (_: Exception) {
            // 缓存损坏时按空处理，下次扫描会重新提取
        }
        out
    }

    fun save(map: Map<Long, PhotoExif>) = synchronized(lock) {
        try {
            val arr = JSONArray()
            for ((id, e) in map) arr.put(toJson(id, e))
            val json = JSONObject().apply {
                put("version", VERSION)
                put("count", map.size)
                put("items", arr)
            }
            val tmp = File(storeFile.parent, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(storeFile)
        } catch (_: Exception) {
            // 落盘失败不阻断本次检索，内存中的数据仍可用
        }
    }

    fun update(photoId: Long, exif: PhotoExif) = synchronized(lock) {
        val map = load()
        map[photoId] = exif
        save(map)
    }

    fun clear() = synchronized(lock) {
        if (storeFile.exists()) storeFile.delete()
    }

    private fun toJson(id: Long, e: PhotoExif): JSONObject = JSONObject().apply {
        put("photoId", id)
        put("dateTaken", e.dateTaken)
        put("dateSource", e.dateSource)
        if (e.latitude != null) put("lat", e.latitude)
        if (e.longitude != null) put("lon", e.longitude)
        put("country", e.country)
        put("adminArea", e.adminArea)
        put("locality", e.locality)
        put("subLocality", e.subLocality)
        put("thoroughfare", e.thoroughfare)
        put("poi", e.poi)
        put("manualLabel", e.manualLabel)
        put("placeSource", e.placeSource)
    }

    private fun fromJson(id: Long, o: JSONObject): PhotoExif = PhotoExif(
        photoId = id,
        dateTaken = o.optLong("dateTaken", 0L),
        dateSource = o.optString("dateSource", "unknown"),
        latitude = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
        longitude = if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon") else null,
        country = o.optString("country", ""),
        adminArea = o.optString("adminArea", ""),
        locality = o.optString("locality", ""),
        subLocality = o.optString("subLocality", ""),
        thoroughfare = o.optString("thoroughfare", ""),
        poi = o.optString("poi", ""),
        manualLabel = o.optString("manualLabel", ""),
        placeSource = o.optString("placeSource", "")
    )
}
