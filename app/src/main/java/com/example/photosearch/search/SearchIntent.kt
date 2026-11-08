package com.example.photosearch.search

/**
 * 解析后的搜索意图。
 * 替换路径：app/src/main/java/com/example/photosearch/search/SearchIntent.kt
 *
 * 变更：SearchCondition 新增 concept 字段（规范概念 ID）。
 * keywords 保留，仅用于词表未收录的自定义关键词兜底匹配。
 * 其余字段保持原样，PhotoViewModel / UI 无需改动。
 */
data class SearchIntent(
    val rawQuery: String,
    val conditions: List<SearchCondition>,
    val logicOperator: LogicOperator = LogicOperator.AND,
    val confidence: Float = 1.0f
)

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
