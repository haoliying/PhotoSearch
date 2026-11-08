package com.example.photosearch.search

/**
 * 时间区间（毫秒，左闭右开）。endExclusive 为开区间上界。
 *
 * v3.6.0：时间 / 地点作为结构化筛选条件，与内容条件按 AND 联动。
 */
data class TimeRange(
    val startMillis: Long,
    val endExclusive: Long,
    val label: String = ""
) {
    fun contains(t: Long): Boolean = t > 0L && t >= startMillis && t < endExclusive
    val valid: Boolean get() = startMillis < endExclusive

    /** 两个区间取交集，用于「搜索框里写了年份 + 筛选面板也设了区间」的联动。 */
    fun intersect(other: TimeRange): TimeRange = TimeRange(
        startMillis = maxOf(startMillis, other.startMillis),
        endExclusive = minOf(endExclusive, other.endExclusive),
        label = when {
            label.isBlank() -> other.label
            other.label.isBlank() -> label
            label == other.label -> label
            else -> "$label · ${other.label}"
        }
    )

    companion object {
        const val NO_LOWER = 0L
        const val NO_UPPER = Long.MAX_VALUE
    }
}

/**
 * 地点查询。
 * - normalized：去后缀 / 别名归一后的标准地名，用于文本模糊匹配。
 * - centerLat/centerLon：从候选地点里「选定」时带入的中心点，启用半径匹配。
 * - fuzzy：是否允许编辑距离容错（仅对 >=3 字生效，避免「北京」误命中「南京」）。
 */
data class LocationQuery(
    val raw: String,
    val normalized: String = raw,
    val fuzzy: Boolean = true,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val radiusKm: Double = 60.0
)

/**
 * 解析后的搜索意图。
 * conditions 为内容条件；timeRange / locationQuery 为结构化硬过滤条件。
 */
data class SearchIntent(
    val rawQuery: String,
    val conditions: List<SearchCondition>,
    val logicOperator: LogicOperator = LogicOperator.AND,
    val confidence: Float = 1.0f,
    val timeRange: TimeRange? = null,
    val locationQuery: LocationQuery? = null
) {
    val hasFilters: Boolean get() = timeRange != null || locationQuery != null
    /** 只有时间/地点筛选、没有内容词：所有通过硬过滤的照片都应返回。 */
    val filtersOnly: Boolean get() = conditions.isEmpty() && hasFilters
}

data class SearchCondition(
    val type: ConditionType,
    val keywords: List<String> = emptyList(),   // 兜底文本词（严格匹配，不再双向 contains）
    val concept: Concept? = null,               // 规范概念：检索的主依据
    val negated: Boolean = false,
    val minConfidence: Float = 0.3f,
    val weight: Float = 1.0f
)

enum class ConditionType {
    OBJECT, SCENE, ACTION, ATTRIBUTE, COLOR, COUNT, TEXT_CONTENT, TIME, COMPOSITE
}

enum class LogicOperator {
    AND, OR, RANK
}
