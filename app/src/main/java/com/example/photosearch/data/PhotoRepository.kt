package com.example.photosearch.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoRepository(private val context: Context) {

    companion object {
        // 用列名字符串而非已废弃的 MediaStore.Images.Media.LATITUDE/LONGITUDE 常量，
        // 避免不同 compileSdk 下的可用性差异；列名本身是稳定的 MediaStore 契约。
        private const val COL_LATITUDE = "latitude"
        private const val COL_LONGITUDE = "longitude"
    }

    suspend fun loadAllPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoItem>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            COL_LATITUDE,
            COL_LONGITUDE
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val latColumn = cursor.getColumnIndex(COL_LATITUDE)
            val lonColumn = cursor.getColumnIndex(COL_LONGITUDE)

            while (cursor.moveToNext()) {
                if (idColumn < 0) break
                val id = cursor.getLong(idColumn)
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) ?: "Unknown" else "Unknown"
                val rawTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                val addedSec = if (addedColumn >= 0) cursor.getLong(addedColumn) else 0L
                val modifiedSec = if (modifiedColumn >= 0) cursor.getLong(modifiedColumn) else 0L
                val contentUri = ContentUris.withAppendedId(collection, id)

                // 时间兜底链：DATE_TAKEN(ms) -> DATE_ADDED(s) -> DATE_MODIFIED(s)
                var date = when {
                    rawTaken > 0L && rawTaken < 100_000_000_000L -> rawTaken * 1000L  // 部分厂商写成秒
                    rawTaken > 0L -> rawTaken
                    addedSec > 0L -> addedSec * 1000L
                    modifiedSec > 0L -> modifiedSec * 1000L
                    else -> 0L
                }
                if (date < 0L) date = 0L

                // Q+ 分区存储下这两列通常被涂改为 0，作为旧系统的兜底即可
                val lat = if (latColumn >= 0) cursor.getDouble(latColumn).takeIf { it != 0.0 } else null
                val lon = if (lonColumn >= 0) cursor.getDouble(lonColumn).takeIf { it != 0.0 } else null

                photos.add(
                    PhotoItem(
                        id = id,
                        uri = contentUri,
                        dateTaken = date,
                        displayName = name,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
        }

        photos
    }

    fun getPhotoUri(photoId: Long): Uri {
        return ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoId
        )
    }
}
