package com.example.photosearch.search

/**
 * 搜索准确度 —— 「标签与搜索关键字的匹配度」门槛。
 *
 * v3.11.4：把写死的通道阈值改为 max(通道质量基线, 用户准确度)，默认 50% 与 v3.11.3 逐位一致。
 *
 * v3.11.5 修复「设为严格档就搜不到，但照片上明明标着 90%」：
 *
 * 根因是当时存在三套互不相干的百分比：
 *  1. 详情页标签角标 = ML Kit 原始标签置信度（如 90%）；
 *  2. 检索判定 = 内部证据分，端侧标签被压缩成 `0.45 + 0.35 * 置信度`
 *     （全文通道 `0.55 + 0.25 * 置信度`），**上限恰好 0.80**；
 *  3. 列表「综合匹配度」= 各条件证据分的加权平均（又是另一套数）。
 * 用户设「严格 80%」时，判定阈值 = 0.80，而 0.90 置信度的标签只换算成 0.765，
 * 于是没开大模型的相册里几乎没有证据能达标 —— 严格档返回 0 张，
 * 可用户看照片详情却看到「90%」，自然认为功能坏了。
 *
 * 本版把口径统一为一条：**综合匹配度（50~100）既是列表展示的数，也是与准确度比较的数**。
 *  - 通道召回仍用 v3.11.3 的固定质量基线，保证默认档结果逐位不变；
 *  - 命中证据经 [toPercent] 拉伸到「基线 = 50 分、端侧证据上限 = 100 分」的相对刻度，
 *    使 0.90 置信度的标签得到 ~94 分，严格 80% 能正常筛出高置信度照片；
 *  - 最终判定追加 [meetsPercent]，展示值与判定值同源，不再出现「显示 90% 却查不到」。
 *
 * 豁免（不属于标签与关键字匹配度）：时间 / 地点结构化精确筛选、纯排除条件查询。
 *
 * 纯 Kotlin、无 Android 依赖，可直接被离线评测脚本编译验证。
 */
object MatchAccuracy {

    /** 引擎质量基线：低于此百分比不再放宽，避免把噪声证据放进结果。 */
    const val MIN_PERCENT = 50

    /** 上限。极严格档可能返回 0 张，属预期行为。 */
    const val MAX_PERCENT = 95

    /** 默认与 v3.11.3 / v3.11.4 行为完全一致。 */
    const val DEFAULT_PERCENT = 50

    /** 设置页滑杆步长（百分比）。 */
    const val STEP_PERCENT = 5

    /**
     * v3.11.5：端侧标签证据的理论上限。
     * 概念通道 `0.45 + 0.35 * confidence`、全文通道 `0.55 + 0.25 * confidence`，
     * confidence = 1.0 时都恰好是 0.80；只有大模型关键词(1.0)/描述(0.9)能超过它。
     * 以它作为综合匹配度 100 分的锚点，让端侧高置信标签也能达到 90+ 分。
     */
    const val EVIDENCE_CEILING = 0.80f

    data class Preset(val percent: Int, val label: String, val hint: String)

    val PRESETS: List<Preset> = listOf(
        Preset(50, "标准", "与旧版一致，保留所有可信匹配"),
        Preset(65, "较准", "剔除把握不大的照片"),
        Preset(80, "严格", "只保留高置信度命中"),
        Preset(90, "极严格", "近乎确定的命中，结果最少")
    )

    /** 任意输入（含旧配置 / 越界值）收敛到合法范围。 */
    fun coerce(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 百分比 -> 0..1 的匹配度。 */
    fun asFraction(percent: Int): Float = coerce(percent) / 100f

    /**
     * v3.11.4 的实际判定阈值 max(通道质量基线, 准确度)。
     * v3.11.5 起检索内核不再用它抬高高通道门槛（改为固定基线召回 +
     * 综合匹配度终判），但保留该函数供历史评测、外部调用与语义对照。
     */
    fun threshold(base: Float, percent: Int): Float = maxOf(base, asFraction(percent))

    /**
     * 匹配度是否达标（>= 才算匹配）。
     * EPS 用于消除 Float 累加误差导致的 0.54999995 >= 0.55 这类边界误判。
     */
    fun meets(score: Float, percent: Int): Boolean = score + EPS >= asFraction(percent)

    private const val EPS = 1e-5f

    /** 0..1 的匹配度换算成百分比（v3.11.4 旧展示口径，保留兼容）。 */
    fun percentOf(score: Float): Int =
        (score * 100f + 0.5f).toInt().coerceIn(0, 100)

    /**
     * v3.11.5：把内部证据分换算成用户可理解的「综合匹配度」(50..100)。
     *
     * 以该通道的质量基线为 50 分、端侧证据上限 [EVIDENCE_CEILING] 为 100 分做线性拉伸：
     *  - 刚过基线 -> 50 分，因此默认 50% 档不会误杀任何旧版结果；
     *  - 0.90 置信度的端侧标签（证据 0.765）-> ~94 分，严格 80% 正常命中；
     *  - 大模型关键词(1.0) / 描述(0.9) 超过上限，clamp 到 100 分。
     *
     * 单调、有界，且 score >= base 时结果恒 >= 50。
     */
    fun toPercent(score: Float, base: Float): Int {
        val span = (EVIDENCE_CEILING - base).coerceAtLeast(0.01f)
        val normalized = ((score - base) / span).coerceIn(0f, 1f)
        return (50f + 50f * normalized + 0.5f).toInt().coerceIn(MIN_PERCENT, 100)
    }

    /**
     * v3.11.5 唯一终判：综合匹配度 >= 准确度才算匹配。
     * 这个百分比与结果列表展示的是同一个数，保证「所见即所得」。
     */
    fun meetsPercent(matchPercent: Int, accuracyPercent: Int): Boolean =
        matchPercent >= coerce(accuracyPercent)

    /** 当前档位名称，供设置页 / 结果栏显示。 */
    fun labelOf(percent: Int): String {
        val p = coerce(percent)
        return PRESETS.lastOrNull { it.percent <= p }?.label ?: PRESETS.first().label
    }

    fun summary(percent: Int): String {
        val p = coerce(percent)
        return if (p <= MIN_PERCENT) {
            "标准：保留所有可信匹配（与旧版一致）"
        } else {
            "${labelOf(p)}：综合匹配度需 ≥ $p% 才算匹配"
        }
    }
}
