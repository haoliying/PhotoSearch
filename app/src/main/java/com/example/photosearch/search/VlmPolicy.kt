package com.example.photosearch.search

import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.SceneType

/**
 * v3.11.0：大模型使用策略。
 *
 * 旧版只有一个「启用云端 AI 分析」布尔开关，且一旦开启，[AnalysisPlanner.plan]
 * 会把所有「本地已分析但描述为空」的照片全部排进补描述队列 —— 用户既不能
 * 完全关掉大模型，也不能挑选「哪些照片交给大模型」，几万张照片会全部走一遍
 * 云端 API，流量与耗时都不可控。
 *
 * 现在把「是否用」与「给谁用」拆开：
 *  - OFF       完全不调用大模型，纯端侧识别（配合本地准确度增强）
 *  - ALL       所有照片（旧行为）
 *  - NEW_ONLY  仅新增 / 从未分析过的照片，存量结果不动
 *  - UNCERTAIN 仅本地识别可信度低于阈值的照片（推荐：把大模型用在最需要的照片上）
 *  - SELECTED  仅用户在相册里手动勾选的照片
 *
 * 另外任何非 OFF 模式下，用户手动勾选的照片都会被**强制**纳入队列，
 * 这样「多选 → 用大模型分析所选」在任何策略下都能立即生效。
 *
 * 本文件是纯 Kotlin，不依赖 Android，可离线评测。
 */
enum class VlmMode(val id: String, val label: String, val hint: String) {
    OFF(
        "off", "不使用大模型",
        "完全依赖端侧 ML Kit 识别与本地增强（颜色 / 构图 / 人数 / 文字 / 查询扩展），不联网、不消耗流量。"
    ),
    ALL(
        "all", "全部照片",
        "每张照片都调用云端大模型生成中文描述与关键词，准确度最高，但流量与耗时也最大。"
    ),
    NEW_ONLY(
        "new_only", "仅新增 / 未分析的照片",
        "只分析新拍、新下载或尚未有结果的照片；已经用本地模型分析过的存量照片不再补描述。"
    ),
    UNCERTAIN(
        "uncertain", "仅识别不确定的照片（推荐）",
        "先用端侧模型识别，只有标签少、置信度低、场景不明确的照片才交给大模型精修，省流量且补短板。"
    ),
    SELECTED(
        "selected", "仅手动选择的照片",
        "只有你在相册里多选并点「用大模型分析所选」的照片才会联网分析，其余一律走本地识别。"
    );

    companion object {
        fun fromId(id: String?): VlmMode = values().firstOrNull { it.id == id } ?: ALL
    }
}

/** 一次分析任务的策略快照。由 AppConfig 读取后传给计划器与服务，避免逐张读 SharedPreferences。 */
data class VlmPolicyConfig(
    val mode: VlmMode = VlmMode.ALL,
    /** UNCERTAIN 模式的可信度阈值：低于此值才送大模型，0..1。 */
    val uncertainThreshold: Float = 0.55f,
    /** 用户手动勾选、明确要求大模型精修的照片 id。 */
    val selectedIds: Set<Long> = emptySet(),
    val apiKeyPresent: Boolean = false,
    val wifiOnly: Boolean = false,
    val wifiAvailable: Boolean = true
) {
    /** 大模型通道是否可用（开关 + Key + 网络条件）。OFF 时恒为 false。 */
    val enabled: Boolean
        get() = mode != VlmMode.OFF && apiKeyPresent && (!wifiOnly || wifiAvailable)

    /** 关闭原因，用于设置页提示；null 表示可用。 */
    fun disabledReason(): String? = when {
        mode == VlmMode.OFF -> "当前策略为「不使用大模型」"
        !apiKeyPresent -> "尚未配置 API Key"
        wifiOnly && !wifiAvailable -> "已设为「仅 Wi-Fi 下使用」，当前不是 Wi-Fi"
        else -> null
    }
}

data class VlmDecision(val useVlm: Boolean, val reason: String)

object VlmPolicy {

    /**
     * 端侧识别可信度 0..1。综合以下信号：
     *  - 最高标签置信度、高置信标签数量、可用标签数量；
     *  - 对象检测结果、场景是否明确；
     *  - 是否已有文字 / OCR 文本等额外信息。
     *
     * 这是对「这张照片本地识别得够不够好」的估计，用于 UNCERTAIN 模式挑选照片，
     * 也用于在界面上提示用户哪些照片值得交给大模型。
     */
    fun localConfidence(m: PhotoMetadata): Float {
        if (!m.isAnalyzed) return 0f
        val labels = m.labels.filter { it.text.isNotBlank() }
        val top = labels.maxOfOrNull { it.confidence } ?: 0f
        val strong = labels.count { it.confidence >= 0.50f }
        val usable = labels.count { it.confidence >= 0.25f }
        val objects = m.detectedObjects.count { it.confidence >= 0.40f }
        val sceneKnown = m.sceneType != SceneType.UNKNOWN
        val hasExtraText = m.vlmKeywords.isNotEmpty() ||
                m.description.isNotBlank() || m.ocrText.isNotBlank() ||
                m.localTags.isNotEmpty()

        var s = 0f
        s += top.coerceIn(0f, 1f) * 0.40f
        s += (strong.coerceAtMost(4) / 4f) * 0.22f
        s += (usable.coerceAtMost(6) / 6f) * 0.16f
        s += (objects.coerceAtMost(3) / 3f) * 0.08f
        if (sceneKnown) s += 0.08f
        if (hasExtraText) s += 0.06f
        return s.coerceIn(0f, 1f)
    }

    /**
     * 决定某张照片是否调用大模型。
     *
     * @param isNew 是否从未分析过（磁盘里没有该 id 的结果）
     * @param hasDescription 是否已经有大模型描述（已有则不重复联网）
     * @param confidence 该照片端侧识别可信度，通常由 [localConfidence] 提供；
     *                   对尚未分析的新照片，服务会先跑端侧识别再传真实值。
     */
    fun decide(
        config: VlmPolicyConfig,
        photoId: Long,
        isNew: Boolean,
        hasDescription: Boolean,
        confidence: Float = 0f
    ): VlmDecision {
        if (!config.enabled) return VlmDecision(false, config.disabledReason() ?: "大模型未启用")
        if (hasDescription && photoId !in config.selectedIds) {
            return VlmDecision(false, "已有大模型描述")
        }
        // 手动勾选在任何非 OFF 策略下都强制生效
        if (photoId in config.selectedIds) {
            return VlmDecision(true, "手动选择")
        }
        return when (config.mode) {
            VlmMode.OFF -> VlmDecision(false, "不使用大模型")
            VlmMode.ALL -> VlmDecision(true, "全部照片")
            VlmMode.NEW_ONLY ->
                if (isNew) VlmDecision(true, "新增照片")
                else VlmDecision(false, "存量照片按策略跳过")
            VlmMode.UNCERTAIN ->
                if (confidence < config.uncertainThreshold) {
                    VlmDecision(true, "本地识别不确定(${((confidence * 100).toInt())}%)")
                } else {
                    VlmDecision(false, "本地识别已足够(${((confidence * 100).toInt())}%)")
                }
            VlmMode.SELECTED -> VlmDecision(false, "未手动选择")
        }
    }

    /** 供 UI 显示的一句话策略摘要。 */
    fun summary(config: VlmPolicyConfig): String = when {
        config.mode == VlmMode.OFF -> "当前：纯本地识别，不调用大模型"
        !config.apiKeyPresent -> "当前：未配置 API Key，实际按纯本地识别运行"
        config.mode == VlmMode.ALL -> "当前：所有照片都调用大模型"
        config.mode == VlmMode.NEW_ONLY -> "当前：仅新增照片调用大模型"
        config.mode == VlmMode.SELECTED -> "当前：仅手动选择的 ${config.selectedIds.size} 张调用大模型"
        else -> "当前：仅识别可信度低于 ${(config.uncertainThreshold * 100).toInt()}% 的照片调用大模型"
    }
}
