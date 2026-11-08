package com.example.photosearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * EXIF 时空与拍摄参数缓存。
 *
 * 独立于 analysis_results.json：读取 EXIF 不需要 AI 分析，也不影响旧缓存，
 * 用户升级后无需重新分析照片即可使用时间/地点/拍摄参数筛选。
 * 断点续传友好：每批 25 张落盘一次，中断后已提取的部分不丢。
 *
 * v3.7.0：缓存结构升级到 version 2（新增相机/镜头/曝光/格式字段）。
 * version 1 的旧缓存仍可读取，但 PhotoViewModel 会对缺少扩展字段的记录
 * 重新提取一次，以便用户无需手动清除缓存即可用上新标签。
 */
class PhotoExifStore(context: Context) {

    private val storeFile = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    companion object {
        private const val FILE_NAME = "photo_exif.json"
        private const val VERSION = 2
    }

    fun load(): MutableMap<Long, PhotoExif> = synchronized(lock) {
        val out = mutableMapOf<Long, PhotoExif>()
        try {
            if (!storeFile.exists()) return@synchronized out
            val json = JSONObject(storeFile.readText())
            val version = json.optInt("version", 0)
            if (version !in 1..VERSION) return@synchronized out
            val arr = json.optJSONArray("items") ?: return@synchronized out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("photoId", -1)
                if (id < 0) continue
                out[id] = fromJson(id, o, version)
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

        put("schema", 2)
        put("cameraMake", e.cameraMake)
        put("cameraModel", e.cameraModel)
        put("lensMake", e.lensMake)
        put("lensModel", e.lensModel)
        put("software", e.software)
        put("artist", e.artist)
        put("imageDescription", e.imageDescription)
        put("fNumber", e.fNumber)
        put("exposureTime", e.exposureTime)
        put("iso", e.iso)
        put("focalLength", e.focalLength)
        put("focalLength35", e.focalLength35)
        put("flash", e.flash)
        put("whiteBalance", e.whiteBalance)
        if (e.exposureBias != null) put("exposureBias", e.exposureBias)
        put("meteringMode", e.meteringMode)
        put("orientation", e.orientation)
        put("imageWidth", e.imageWidth)
        put("imageHeight", e.imageHeight)
        if (e.altitude != null) put("altitude", e.altitude)
        put("gpsDateStamp", e.gpsDateStamp)
        put("offsetTimeOriginal", e.offsetTimeOriginal)
        put("mimeType", e.mimeType)
        put("fileSize", e.fileSize)
        put("fileExtension", e.fileExtension)
    }

    private fun fromJson(id: Long, o: JSONObject, version: Int): PhotoExif = PhotoExif(
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
        placeSource = o.optString("placeSource", ""),
        cameraMake = o.optString("cameraMake", ""),
        cameraModel = o.optString("cameraModel", ""),
        lensMake = o.optString("lensMake", ""),
        lensModel = o.optString("lensModel", ""),
        software = o.optString("software", ""),
        artist = o.optString("artist", ""),
        imageDescription = o.optString("imageDescription", ""),
        fNumber = o.optDouble("fNumber", 0.0),
        exposureTime = o.optDouble("exposureTime", 0.0),
        iso = o.optInt("iso", 0),
        focalLength = o.optDouble("focalLength", 0.0),
        focalLength35 = o.optInt("focalLength35", 0),
        flash = o.optInt("flash", -1),
        whiteBalance = o.optInt("whiteBalance", -1),
        exposureBias = if (o.has("exposureBias") && !o.isNull("exposureBias"))
            o.optDouble("exposureBias") else null,
        meteringMode = o.optInt("meteringMode", -1),
        orientation = o.optInt("orientation", 1),
        imageWidth = o.optInt("imageWidth", 0),
        imageHeight = o.optInt("imageHeight", 0),
        altitude = if (o.has("altitude") && !o.isNull("altitude")) o.optDouble("altitude") else null,
        gpsDateStamp = o.optString("gpsDateStamp", ""),
        offsetTimeOriginal = o.optString("offsetTimeOriginal", ""),
        mimeType = o.optString("mimeType", ""),
        fileSize = o.optLong("fileSize", 0L),
        fileExtension = o.optString("fileExtension", ""),
        // 旧缓存缺字段：标记为 v1，ViewModel 会重新提取以补齐拍摄参数
        schemaVersion = if (version >= 2) 2 else 1
    )
}
