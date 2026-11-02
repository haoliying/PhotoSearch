package com.example.photosearch.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App configuration stored in SharedPreferences.
 */
object AppConfig {
    private const val PREFS_NAME = "photo_search_prefs"
    private const val KEY_SEGMENT_SIZE = "analysis_segment_size"
    private const val DEFAULT_SEGMENT_SIZE = 200

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var segmentSize: Int
        get() = DEFAULT_SEGMENT_SIZE  // Default, can be made configurable via UI later
        set(value) { /* Reserved for future UI configuration */ }

    fun getSegmentSize(context: Context): Int {
        return prefs(context).getInt(KEY_SEGMENT_SIZE, DEFAULT_SEGMENT_SIZE)
    }

    fun setSegmentSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_SEGMENT_SIZE, size).apply()
    }
}
