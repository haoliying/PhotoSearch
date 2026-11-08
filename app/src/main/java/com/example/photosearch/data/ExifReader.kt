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
 * 从照片原始流读取 EXIF 时空与拍摄参数，并把经纬度解析成可检索的地名。
 *
 * 时间优先级：EXIF DateTimeOriginal > DateTime > 文件名时间戳 > MediaStore DATE_TAKEN。
 * GPS 优先级：EXIF GPS > MediaStore LATITUDE/LONGITUDE（旧系统）。
 * 地名优先级：Geocoder（若可用） > 内置城市表 > 经纬度字符串。
 *
 * v3.7.0：除时间/GPS 外，补齐相机、镜头、曝光、闪光灯、白平衡、方向、
 * 分辨率、海拔、文件格式与大小，全部生成中文标签供检索与列表展示。
 *
 * Android 10+ 分区存储下，MediaStore 的经纬度会被涂改，必须用
 * MediaStore.setRequireOriginal(uri) 拿原始文件流（需要 ACCESS_MEDIA_LOCATION 权限）；
 * 无权限时自动降级为普通流，拍摄时间仍可读，只是 GPS 可能缺失。
 *
 * EXIF 标签一律用标准字符串名访问，避免不同 exifinterface 版本的常量差异。
 */
object ExifReader {

    private const val MAX_GEOCODER_CALLS = 60
    private val geoCalls = AtomicInteger(0)

    // ---- EXIF 标准标签名（与 ExifInterface.TAG_* 的值一致）----
    private const val T_DATETIME_ORIGINAL = "DateTimeOriginal"
    private const val T_DATETIME = "DateTime"
    private const val T_DATETIME_DIGITIZED = "DateTimeDigitized"
    private const val T_OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"
    private const val T_MAKE = "Make"
    private const val T_MODEL = "Model"
    private const val T_LENS_MAKE = "LensMake"
    private const val T_LENS_MODEL = "LensModel"
    private const val T_LENS_SPEC = "LensSpecification"
    private const val T_SOFTWARE = "Software"
    private const val T_ARTIST = "Artist"
    private const val T_IMAGE_DESCRIPTION = "ImageDescription"
    private const val T_F_NUMBER = "FNumber"
    private const val T_EXPOSURE_TIME = "ExposureTime"
    private const val T_ISO = "PhotographicSensitivity"
    private const val T_ISO_LEGACY = "ISOSpeedRatings"
    private const val T_FOCAL_LENGTH = "FocalLength"
    private const val T_FOCAL_35 = "FocalLengthIn35mmFilm"
    private const val T_FLASH = "Flash"
    private const val T_WHITE_BALANCE = "WhiteBalance"
    private const val T_EXPOSURE_BIAS = "ExposureBiasValue"
    private const val T_METERING_MODE = "MeteringMode"
    private const val T_ORIENTATION = "Orientation"
    private const val T_IMAGE_WIDTH = "ImageWidth"
    private const val T_IMAGE_LENGTH = "ImageLength"
    private const val T_GPS_ALTITUDE = "GPSAltitude"
    private const val T_GPS_ALTITUDE_REF = "GPSAltitudeRef"
    private const val T_GPS_DATESTAMP = "GPSDateStamp"

    private data class GeoParts(
        val country: String, val admin: String, val locality: String,
        val sub: String, val street: String, val poi: String
    )

    /** 一次 EXIF 读取得到的拍摄参数，随后合并进 PhotoExif。 */
    private data class CameraInfo(
        val make: String = "",
        val model: String = "",
        val lensMake: String = "",
        val lensModel: String = "",
        val software: String = "",
        val artist: String = "",
        val imageDescription: String = "",
        val fNumber: Double = 0.0,
        val exposureTime: Double = 0.0,
        val iso: Int = 0,
        val focalLength: Double = 0.0,
        val focalLength35: Int = 0,
        val flash: Int = -1,
        val whiteBalance: Int = -1,
        val exposureBias: Double? = null,
        val meteringMode: Int = -1,
        val orientation: Int = 1,
        val width: Int = 0,
        val height: Int = 0,
        val altitude: Double? = null,
        val gpsDateStamp: String = "",
        val offsetTimeOriginal: String = ""
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
        var info = CameraInfo()

        val stream = openStream(context, photo.uri)
        if (stream != null) {
            try {
                stream.use { input ->
                    val exif = ExifInterface(input)
                    val raw = exif.getAttribute(T_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(T_DATETIME)
                        ?: exif.getAttribute(T_DATETIME_DIGITIZED)
                    parseExifDate(raw)?.let { exifTime = it }
                    exif.latLong?.let { if (it.size >= 2) { lat = it[0]; lon = it[1] } }
                    info = readCameraInfo(exif)
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

        // MediaStore 的宽高 / 大小 / MIME 比 EXIF 更可靠（手机 JPEG 常不写 ImageWidth）
        val width = if (info.width > 0) info.width else photo.width
        val height = if (info.height > 0) info.height else photo.height

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
            placeSource = placeSource,
            cameraMake = info.make,
            cameraModel = info.model,
            lensMake = info.lensMake,
            lensModel = info.lensModel,
            software = info.software,
            artist = info.artist,
            imageDescription = info.imageDescription,
            fNumber = info.fNumber,
            exposureTime = info.exposureTime,
            iso = info.iso,
            focalLength = info.focalLength,
            focalLength35 = info.focalLength35,
            flash = info.flash,
            whiteBalance = info.whiteBalance,
            exposureBias = info.exposureBias,
            meteringMode = info.meteringMode,
            orientation = info.orientation,
            imageWidth = width,
            imageHeight = height,
            altitude = info.altitude,
            gpsDateStamp = info.gpsDateStamp,
            offsetTimeOriginal = info.offsetTimeOriginal,
            mimeType = photo.mimeType,
            fileSize = photo.sizeBytes,
            fileExtension = extensionOf(photo.displayName),
            schemaVersion = 2
        )
    }

    /** 用户手动标注地点：保留已有 GPS/时间/拍摄参数，只覆盖地点字段。 */
    fun withManualLabel(base: PhotoExif?, photoId: Long, label: String): PhotoExif {
        val b = base ?: PhotoExif(photoId = photoId)
        return b.copy(manualLabel = label.trim(), placeSource = "manual")
    }

    // ------------------------------------------------------------------
    // 内部：EXIF 参数读取
    // ------------------------------------------------------------------

    private fun readCameraInfo(exif: ExifInterface): CameraInfo {
        fun s(tag: String): String = exif.getAttribute(tag)?.trim().orEmpty()
        fun d(tag: String): Double = parseRational(s(tag)) ?: 0.0
        fun i(tag: String, def: Int = 0): Int = s(tag).toIntOrNull() ?: def

        val altRaw = parseRational(s(T_GPS_ALTITUDE))
        val altRef = s(T_GPS_ALTITUDE_REF)
        val altitude = altRaw?.let { if (altRef == "1") -it else it }

        val lensSpec = s(T_LENS_SPEC)
        val lensModel = s(T_LENS_MODEL).ifBlank { lensSpec }

        return CameraInfo(
            make = s(T_MAKE),
            model = s(T_MODEL),
            lensMake = s(T_LENS_MAKE),
            lensModel = lensModel,
            software = s(T_SOFTWARE),
            artist = s(T_ARTIST),
            imageDescription = s(T_IMAGE_DESCRIPTION),
            fNumber = d(T_F_NUMBER),
            exposureTime = d(T_EXPOSURE_TIME),
            iso = i(T_ISO, i(T_ISO_LEGACY, 0)),
            focalLength = d(T_FOCAL_LENGTH),
            focalLength35 = i(T_FOCAL_35, 0),
            flash = if (s(T_FLASH).isBlank()) -1 else i(T_FLASH, -1),
            whiteBalance = if (s(T_WHITE_BALANCE).isBlank()) -1 else i(T_WHITE_BALANCE, -1),
            exposureBias = parseRational(s(T_EXPOSURE_BIAS)),
            meteringMode = if (s(T_METERING_MODE).isBlank()) -1 else i(T_METERING_MODE, -1),
            orientation = i(T_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1,
            width = i(T_IMAGE_WIDTH, 0),
            height = i(T_IMAGE_LENGTH, 0),
            altitude = altitude,
            gpsDateStamp = s(T_GPS_DATESTAMP),
            offsetTimeOriginal = s(T_OFFSET_TIME_ORIGINAL)
        )
    }

    /** EXIF rational 字符串："4/1" / "1/120" / "0.0083" / "f/1.8" / "4.25 mm" / "-1/3"。 */
    private fun parseRational(raw: String): Double? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("f/").removePrefix("F/")
            .replace(Regex("""\s*mm\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val frac = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*/\s*(-?\d+(?:\.\d+)?)\s*$""").find(cleaned)
        if (frac != null) {
            val num = frac.groupValues[1].toDoubleOrNull() ?: return null
            val den = frac.groupValues[2].toDoubleOrNull() ?: return null
            return if (den == 0.0) null else num / den
        }
        return cleaned.toDoubleOrNull()
    }

    private fun extensionOf(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx >= 0 && idx < name.length - 1) name.substring(idx + 1) else ""
    }

    // ------------------------------------------------------------------
    // 内部：时间 / 地点
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
        if (lat == null || lon == null) return EMPTY_GEO to ""
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
