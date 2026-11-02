package com.example.photosearch.search

/**
 * Represents a parsed search intent from natural language input.
 * Supports compound conditions with AND/OR/NOT logic.
 */
data class SearchIntent(
    val rawQuery: String,
    val conditions: List<SearchCondition>,
    val logicOperator: LogicOperator = LogicOperator.AND,
    val confidence: Float = 1.0f
)

data class SearchCondition(
    val type: ConditionType,
    val keywords: List<String>,          // Keywords to match against tags
    val negated: Boolean = false,         // NOT condition
    val minConfidence: Float = 0.3f,      // Minimum label confidence
    val weight: Float = 1.0f             // Importance weight for scoring
)

enum class ConditionType {
    OBJECT,          // Specific object (car, dog, person)
    SCENE,           // Scene type (beach, mountain, indoor)
    ACTION,          // Action/activity (running, eating, swimming)
    ATTRIBUTE,       // Attribute (shirtless, smiling, outdoor)
    COLOR,           // Color-based search
    COUNT,           // Quantity-based (multiple people, group)
    TEXT_CONTENT,    // Photo contains text
    TIME,            // Time-based (night, sunset)
    COMPOSITE        // Composite condition (multiple sub-conditions)
}

enum class LogicOperator {
    AND,  // All conditions must match
    OR,   // Any condition can match
    RANK  // Rank by total score (soft matching)
}
