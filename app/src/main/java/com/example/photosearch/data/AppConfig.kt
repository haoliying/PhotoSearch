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
    // v3.10.0：后台自动分析守护
    private const val KEY_BACKGROUND_WATCH = "background_watch_enabled"
    private const val KEY_WATCH_EVER_STARTED = "background_watch_ever_started"
    private const val KEY_WATCH_PENDING = "background_watch_pending"

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

    /**
     * v3.10.0：后台自动分析守护开关。默认开启（用户明确要求后台运行 + 新照片自动分析）。
     * 关闭后服务停止常驻，分析回到「手动开始 / 回到前台续跑」模式。
     * 只有在照片权限已授予、且 App 曾在前台主动拉起过守护后才会真正常驻，
     * 首次安装不会在后台擅自启动云端 AI 偷跑流量。
     */
    fun isBackgroundWatchEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BACKGROUND_WATCH, true)
    }

    fun setBackgroundWatchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_WATCH, enabled).apply()
    }

    /** 用户是否曾经启用过守护；开机广播据此决定是否尝试恢复。 */
    fun wasWatchEverStarted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WATCH_EVER_STARTED, false)
    }

    fun markWatchStarted(context: Context) {
        if (!wasWatchEverStarted(context)) {
            prefs(context).edit().putBoolean(KEY_WATCH_EVER_STARTED, true).apply()
        }
    }

    /** 开机 / 升级后台启动受系统限制时置位，App 下次回到前台兜底恢复守护。 */
    fun isWatchPending(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WATCH_PENDING, false)
    }

    fun setWatchPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCH_PENDING, pending).apply()
    }
}
