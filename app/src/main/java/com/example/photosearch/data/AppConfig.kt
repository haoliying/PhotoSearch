package com.example.photosearch.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App configuration stored in SharedPreferences.
 *
 * v3.11.0：把「是否使用大模型」从单个布尔开关扩展为「使用策略」——
 * 不使用 / 全部 / 仅新增 / 仅识别不确定 / 仅手动选择，并持久化用户手动勾选
 * 的照片 id 集合。旧的 KEY_USE_VLM 仍然读取，保证升级后行为平滑：
 * 旧版关闭过 AI 的用户继续是「不使用」，其余默认「全部」。
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
    // v3.11.0：大模型使用策略
    private const val KEY_VLM_MODE = "vlm_mode"
    private const val KEY_VLM_UNCERTAIN = "vlm_uncertain_threshold"
    private const val KEY_VLM_WIFI_ONLY = "vlm_wifi_only"
    private const val KEY_VLM_SELECTED_IDS = "vlm_selected_ids"
    private const val KEY_LOCAL_ENHANCE = "local_enhance_enabled"
    // v3.11.1：端侧分析并发数（不用大模型时的分析速度）
    private const val KEY_ANALYSIS_PARALLELISM = "analysis_parallelism"

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

    // ------------------------------------------------------------------
    // v3.11.0 大模型使用策略
    // ------------------------------------------------------------------

    /** 策略 id，见 [com.example.photosearch.search.VlmMode]。兼容旧版布尔开关。 */
    fun getVlmModeId(context: Context): String {
        val stored = prefs(context).getString(KEY_VLM_MODE, null)
        if (stored != null) return stored
        return if (prefs(context).getBoolean(KEY_USE_VLM, true)) "all" else "off"
    }

    fun setVlmModeId(context: Context, modeId: String) {
        prefs(context).edit()
            .putString(KEY_VLM_MODE, modeId)
            .putBoolean(KEY_USE_VLM, modeId != "off")
            .apply()
    }

    /** 兼容旧调用点：关闭 = OFF，开启 = ALL。 */
    fun isVlmEnabled(context: Context): Boolean = getVlmModeId(context) != "off"

    fun setVlmEnabled(context: Context, enabled: Boolean) {
        setVlmModeId(context, if (enabled) "all" else "off")
    }

    /** UNCERTAIN 模式的可信度阈值，0..1，默认 0.55。 */
    fun getUncertainThreshold(context: Context): Float {
        val pct = prefs(context).getInt(KEY_VLM_UNCERTAIN, 55)
        return (pct.coerceIn(0, 100) / 100f)
    }

    fun setUncertainThreshold(context: Context, value: Float) {
        prefs(context).edit().putInt(KEY_VLM_UNCERTAIN, (value * 100).toInt().coerceIn(0, 100)).apply()
    }

    fun isVlmWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VLM_WIFI_ONLY, false)

    fun setVlmWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VLM_WIFI_ONLY, enabled).apply()
    }

    /** 是否启用不依赖大模型的本地准确度增强（视觉标签 + 查询扩展）。默认开启。 */
    fun isLocalEnhanceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCAL_ENHANCE, true)

    fun setLocalEnhanceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_ENHANCE, enabled).apply()
    }

    // ------------------------------------------------------------------
    // v3.11.1 端侧分析并发数
    // ------------------------------------------------------------------

    /**
     * 端侧分析并发数（1..4，默认 2）。
     * 1 = 与 v3.11.0 一致的逐张串行（最省电、对前台零干扰）；
     * 4 = 极速（四路并行，首次全量分析明显更快，可能发热）。
     */
    fun getAnalysisParallelism(context: Context): Int =
        com.example.photosearch.service.AnalysisScheduling.coerceParallelism(
            prefs(context).getInt(
                KEY_ANALYSIS_PARALLELISM,
                com.example.photosearch.service.AnalysisScheduling.DEFAULT_PARALLELISM
            )
        )

    fun setAnalysisParallelism(context: Context, parallelism: Int) {
        prefs(context).edit()
            .putInt(
                KEY_ANALYSIS_PARALLELISM,
                com.example.photosearch.service.AnalysisScheduling.coerceParallelism(parallelism)
            )
            .apply()
    }

    /** 用户手动勾选、明确要求大模型精修的照片 id。 */
    fun getVlmSelectedIds(context: Context): Set<Long> {
        val raw = prefs(context).getStringSet(KEY_VLM_SELECTED_IDS, null) ?: return emptySet()
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** SharedPreferences 的 StringSet 必须复制成新集合再写回，避免修改不可变集合。 */
    fun addVlmSelectedIds(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val merged = HashSet<String>(getVlmSelectedIds(context).map { it.toString() })
        ids.forEach { merged.add(it.toString()) }
        prefs(context).edit().putStringSet(KEY_VLM_SELECTED_IDS, merged).apply()
    }

    fun setVlmSelectedIds(context: Context, ids: Collection<Long>) {
        prefs(context).edit()
            .putStringSet(KEY_VLM_SELECTED_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    /** 大模型已尝试过这几张后消费掉，避免下一轮对账无限重跑。 */
    fun removeVlmSelectedIds(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val merged = HashSet<String>(getVlmSelectedIds(context).map { it.toString() })
        val removeSet = ids.map { it.toString() }.toSet()
        if (!merged.removeAll(removeSet)) return
        prefs(context).edit().putStringSet(KEY_VLM_SELECTED_IDS, merged).apply()
    }

    fun clearVlmSelectedIds(context: Context) {
        prefs(context).edit().remove(KEY_VLM_SELECTED_IDS).apply()
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
