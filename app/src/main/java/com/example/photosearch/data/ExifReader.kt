package com.example.photosearch.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.example.photosearch.search.PlaceCatalog
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 从照片原始流读取 EXIF 拍摄时间与 GPS，并把经纬度解析成可检索的地名（v3.6.0）。
 *
 * 时间优先级：EXIF DateTimeOriginal > DateTime > 文件名时间戳 > MediaStore DATE_TAKEN。
 * GPS 优先级：EXIF GPS > MediaStore LATITUDE/LONGITUDE（旧系统）。
 * 地名优先级：Geocoder（若可用） > 内置城市表 > 经纬度字符串。
 *
 * Android 10+ 分区存储下，MediaStore 的经纬度会被涂改，必须用
 * MediaStore.setRequireOriginal(uri) 拿原始文件流（需要 ACCESS_MEDIA_LOCATION 权限）；
 * 无权限时自动降级为普通流，拍摄时间仍可读，只是 GPS 可能缺失。
 */
object ExifReader {

    private const val MAX_GEOCODER_CALLS = 60
    private val geoCalls = AtomicInteger(0)

    private data class GeoParts(
        val country: String, val admin: String, val locality: String,
        val sub: String, val street: String, val poi: String
    )

    // ConcurrentHashMap 不接受 null 值，用哨兵对象表示「查过但失败」，避免重复调用 Geocoder
    private val EMPTY_GEO = GeoParts("", "", "", "", "", "")
    private val geoCache = ConcurrentHashMap<String, GeoParts>()

    private val FILENAME_DATE = Regex("""(20\d{2}|19\d{2})[-_]?(\d{2})[-_]?(\d{2})""")

    fun read(context: Context, photo: PhotoItem): PhotoExif {
        var exifTime = 0L
        var dateSource = "unknown"
        var lat: Double? = null
        var lon: Double? = null

        val stream = openStream(context, photo.uri)
        if (stream != null) {
            try {
                stream.use { input ->
                    val exif = ExifInterface(input)
                    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    parseExifDate(raw)?.let { exifTime = it }
                    exif.latLong?.let { if (it.size >= 2) { lat = it[0]; lon = it[1] } }
                }
            } catch (_: Exception) {
                // 某些 HEIC/损坏文件读取失败，走兜底链
            }
        }

        if (exifTime > 0L) dateSource = "exif"

        // 文件名兜底：IMG_20230615_123456.jpg / Screenshot_2023-06-15-... / wx_camera_20230615...
        if (exifTime <= 0L) {
            filenameTime(photo.displayName)?.let {
                exifTime = it
                dateSource = "filename"
            }
        }

        // MediaStore 兜底（注意部分厂商把单位写成秒）
        if (exifTime <= 0L) {
            val t = photo.dateTaken
            if (t > 0L) {
                exifTime = if (t < 100_000_000_000L) t * 1000L else t
                dateSource = "mediastore"
            }
        }

        if (lat == null && photo.latitude != null && photo.longitude != null) {
            lat = photo.latitude
            lon = photo.longitude
        }

        val parts = resolvePlace(context, lat, lon)
        val placeSource = when {
            lat == null -> ""
            parts.second == "geocoder" -> "geocoder"
            else -> "gps-city"
        }

        return PhotoExif(
            photoId = photo.id,
            dateTaken = exifTime,
            dateSource = dateSource,
            latitude = lat,
            longitude = lon,
            country = parts.first.country,
            adminArea = parts.first.admin,
            locality = parts.first.locality,
            subLocality = parts.first.sub,
            thoroughfare = parts.first.street,
            poi = parts.first.poi,
            placeSource = placeSource
        )
    }

    /** 用户手动标注地点：保留已有 GPS/时间，只覆盖地点字段。 */
    fun withManualLabel(base: PhotoExif?, photoId: Long, label: String): PhotoExif {
        val b = base ?: PhotoExif(photoId = photoId)
        return b.copy(manualLabel = label.trim(), placeSource = "manual")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            } else uri
            context.contentResolver.openInputStream(original)
        } catch (_: Exception) {
            // 没有 ACCESS_MEDIA_LOCATION 或原始流不可用时，退回普通流
            try {
                context.contentResolver.openInputStream(uri)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseExifDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().take(19)
        if (s.startsWith("0000") || s.startsWith("    ")) return null
        return try {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()   // EXIF 不含时区，按设备本地时区解释
            val t = fmt.parse(s)?.time ?: return null
            val y = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)
            if (y in 1980..2100) t else null
        } catch (_: Exception) {
            null
        }
    }

    private fun filenameTime(name: String): Long? {
        val m = FILENAME_DATE.find(name) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return try {
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, mo - 1, d, 12, 0, 0)
            c.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    /** 返回 (地名组件, 来源标记)。Geocoder 失败时退回内置城市表，绝不阻塞太久。 */
    private fun resolvePlace(context: Context, lat: Double?, lon: Double?): Pair<GeoParts, String> {
        if (lat == null || lon == null) return GeoParts("", "", "", "", "", "") to ""
        val a = lat
        val b = lon
        val nearest = PlaceCatalog.nearest(a, b)
        val fallback = if (nearest != null && nearest.second <= 120.0) {
            val p = nearest.first
            GeoParts(p.country, p.province, p.fullName, "", "", "")
        } else {
            val country = nearest?.first?.country ?: ""
            val coord = String.format(Locale.US, "%.4f,%.4f", a, b)
            GeoParts(country, "", "", "", "", coord)
        }

        val geo = resolveGeocoder(context, a, b) ?: return fallback to "gps-city"
        val merged = GeoParts(
            country = geo.country.ifBlank { fallback.country },
            admin = geo.admin.ifBlank { fallback.admin },
            locality = geo.locality.ifBlank { fallback.locality },
            sub = geo.sub,
            street = geo.street,
            poi = geo.poi
        )
        return merged to "geocoder"
    }

    @Suppress("DEPRECATION")
    private fun resolveGeocoder(context: Context, lat: Double, lon: Double): GeoParts? {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)  // ~1km 网格，同地点照片只查一次
        geoCache[key]?.let { return if (it === EMPTY_GEO) null else it }
        if (!Geocoder.isPresent() || geoCalls.get() >= MAX_GEOCODER_CALLS) return null
        geoCalls.incrementAndGet()
        val result = try {
            val list: List<Address>? = Geocoder(context, Locale.CHINESE).getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull()
            if (a == null) null else GeoParts(
                country = a.countryName.orEmpty(),
                admin = a.adminArea.orEmpty(),
                locality = (a.locality ?: a.subAdminArea).orEmpty(),
                sub = a.subLocality.orEmpty(),
                street = a.thoroughfare.orEmpty(),
                poi = a.featureName.orEmpty()
            )
        } catch (_: Exception) {
            null
        }
        geoCache[key] = result ?: EMPTY_GEO
        return result
    }
}
