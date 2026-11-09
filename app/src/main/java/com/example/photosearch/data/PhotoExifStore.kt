package com.example.photosearch.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * EXIF 时空与拍摄参数缓存。
 *
 * 独立于 analysis_results.json：读取 EXIF 不需要 AI 分析，也不影响旧缓存，
 * 用户升级后无需重新分析照片即可使用时间/地点/拍摄参数筛选。
 *
 * v3.7.0：缓存结构升级到 version 2（新增相机/镜头/曝光/格式字段）。
 * version 1 的旧缓存仍可读取，但 PhotoViewModel 会对缺少扩展字段的记录
 * 重新提取一次，以便用户无需手动清除缓存即可用上新标签。
 *
 * v3.8.0：改为**增量行式存储**。
 * 旧实现每提取 25 张就把整个 Map 序列化成一个 JSON 重新落盘，
 * 3 万张照片时约 1200 次 × O(N) 序列化，后台线程被写文件拖死并频繁触发
 * 全屏重组。新实现每批只追加新增行（O(batch)），旧 v2 文件在首次写入时
 * 一次性迁移，之后永不重写全量。
 */
class PhotoExifStore(context: Context) {

    private val storeFile = File(context.filesDir, LEGACY_FILE)
    private val lineFile = File(context.filesDir, LINE_FILE)
    private val lock = Any()

    companion object {
        private const val LEGACY_FILE = "photo_exif.json"
        private const val LINE_FILE = "photo_exif_v3.jsonl"
        private const val VERSION = 2
    }

    fun load(): MutableMap<Long, PhotoExif> = synchronized(lock) {
        val out = mutableMapOf<Long, PhotoExif>()
        try {
            if (lineFile.exists()) {
                // 行式：后写的行覆盖同 id 的旧行（手动标注 / 重新提取）
                lineFile.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val o = JSONObject(line)
                        val id = o.optLong("photoId", -1)
                        if (id >= 0) out[id] = fromJson(id, o, VERSION)
                    } catch (_: Exception) {
                        // 崩溃可能留下半行，跳过即可
                    }
                }
                return@synchronized out
            }
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

    /**
     * v3.8.0：只追加本批新增/更新的记录，不再全量重写。
     * 首次调用时若存在旧 v2 文件会先一次性迁移为行式。
     */
    fun append(items: Collection<PhotoExif>) = synchronized(lock) {
        if (items.isEmpty()) return@synchronized
        try {
            ensureMigrated()
            val sb = StringBuilder()
            items.forEach { sb.append(toJson(it.photoId, it).toString()).append('\n') }
            lineFile.appendText(sb.toString())
        } catch (_: Exception) {
            // 落盘失败不阻断本次检索，内存中的数据仍可用
        }
    }

    /** 兼容旧调用：全量重写（仅供外部必要时使用，ViewModel 已不调用）。 */
    fun save(map: Map<Long, PhotoExif>) = synchronized(lock) {
        try {
            val sb = StringBuilder()
            map.forEach { (id, e) -> sb.append(toJson(id, e).toString()).append('\n') }
            val tmp = File(lineFile.parent, "$LINE_FILE.tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(lineFile)
        } catch (_: Exception) {
        }
    }

    /** 手动标注 / 单张更新：追加一行，读取时后写覆盖。 */
    fun update(photoId: Long, exif: PhotoExif) = append(listOf(exif))

    fun clear() = synchronized(lock) {
        if (storeFile.exists()) storeFile.delete()
        if (lineFile.exists()) lineFile.delete()
    }

    /** 旧 JSON → 行式的一次性迁移，只在第一次 append 时发生。 */
    private fun ensureMigrated() {
        if (lineFile.exists()) return
        val legacy = readLegacy()
        val sb = StringBuilder()
        legacy?.forEach { (id, e) -> sb.append(toJson(id, e).toString()).append('\n') }
        val tmp = File(lineFile.parent, "$LINE_FILE.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(lineFile)
    }

    private fun readLegacy(): Map<Long, PhotoExif>? {
        if (!storeFile.exists()) return null
        return try {
            val json = JSONObject(storeFile.readText())
            val version = json.optInt("version", 0)
            if (version !in 1..VERSION) return null
            val arr = json.optJSONArray("items") ?: return null
            val out = mutableMapOf<Long, PhotoExif>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("photoId", -1)
                if (id >= 0) out[id] = fromJson(id, o, version)
            }
            out
        } catch (_: Exception) {
            null
        }
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
