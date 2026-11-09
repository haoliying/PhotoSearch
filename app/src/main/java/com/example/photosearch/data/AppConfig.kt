package com.example.photosearch.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App configuration stored in SharedPreferences.
 */
object AppConfig {
    private const val PREFS_NAME = "photo_search_prefs"
    private const val KEY_SEGMENT_SIZE = "analysis_segment_size"
    private const val KEY_API_KEY = "vlm_api_key"
    private const val KEY_API_PROVIDER = "vlm_api_provider"
    private const val KEY_USE_VLM = "use_vlm"
    private const val KEY_AUTO_RESUME = "auto_resume_analysis"
    private const val DEFAULT_SEGMENT_SIZE = 200

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSegmentSize(context: Context): Int {
        return prefs(context).getInt(KEY_SEGMENT_SIZE, DEFAULT_SEGMENT_SIZE)
    }

    fun setSegmentSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_SEGMENT_SIZE, size).apply()
    }

    fun getApiKey(context: Context): String {
        return prefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiProvider(context: Context): String {
        return prefs(context).getString(KEY_API_PROVIDER, "zhipu") ?: "zhipu"
    }

    fun setApiProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_API_PROVIDER, provider).apply()
    }

    fun isVlmEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_USE_VLM, true)
    }

    fun setVlmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_VLM, enabled).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * v3.9.0：应用回到前台时，若上次分析未完成，自动从剩余照片继续。
     * 只续「已经开始过」的分析，不会在首次安装时擅自启动（云端 AI 会消耗流量）。
     */
    fun isAutoResumeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_RESUME, true)
    }

    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }
}
