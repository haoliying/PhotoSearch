package com.example.photosearch.ml

import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoLabel

/**
 * Maps natural language search conditions to ML Kit label matching rules.
 * Supports both predefined conditions and flexible keyword matching.
 */
object SearchConditionMapper {

    data class SearchRule(
        val keywords: List<String>,           // User input keywords (Chinese + English)
        val requiredLabels: List<LabelMatch>,  // Labels that must match
        val excludedLabels: List<LabelMatch> = emptyList(), // Labels that should NOT match
        val description: String
    )

    data class LabelMatch(
        val labelPatterns: List<String>,  // Patterns to match against label text
        val minConfidence: Float = 0.4f,
        val matchType: MatchType = MatchType.ANY
    )

    enum class MatchType {
        ANY,    // Any one label matches
        ALL     // All patterns must match (across different labels)
    }

    // Predefined search rules for common conditions
    private val predefinedRules = listOf(
        // 车辆相关
        SearchRule(
            keywords = listOf("车", "汽车", "轿车", "car", "vehicle", "truck", "车辆"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("car", "vehicle", "truck", "automobile", "van", "bus", "wheel", "tire"),
                    minConfidence = 0.5f
                )
            ),
            description = "包含车辆的照片"
        ),

        // 人物相关
        SearchRule(
            keywords = listOf("人", "人物", "person", "people", "human", "人像"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("person", "people", "human", "man", "woman", "child", "portrait"),
                    minConfidence = 0.6f
                )
            ),
            description = "包含人物的照片"
        ),

        // 光膀子的人
        SearchRule(
            keywords = listOf("光膀子", "赤裸上身", "没穿上衣", "shirtless", "bare chest", "光着上身"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("person", "people", "man", "human", "body"),
                    minConfidence = 0.5f
                ),
                LabelMatch(
                    labelPatterns = listOf("outdoor", "swimming", "beach", "sport", "exercise"),
                    minConfidence = 0.3f
                )
            ),
            excludedLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("suit", "formal", "jacket", "coat", "sweater", "long sleeve"),
                    minConfidence = 0.6f
                )
            ),
            description = "光膀子的人"
        ),

        // 山/泰山/登山
        SearchRule(
            keywords = listOf("山", "泰山", "登山", "爬山", "mountain", "hill", "peak", "hiking"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("mountain", "hill", "peak", "ridge", "cliff", "rock", "valley", "landscape"),
                    minConfidence = 0.5f
                )
            ),
            description = "山景/登山照片"
        ),

        // 海滩/海边
        SearchRule(
            keywords = listOf("海滩", "海边", "沙滩", "beach", "sea", "ocean", "coast", "shore"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("beach", "sand", "sea", "ocean", "water", "coast", "shore", "wave", "palm"),
                    minConfidence = 0.5f
                )
            ),
            description = "海滩/海边照片"
        ),

        // 宠物/动物
        SearchRule(
            keywords = listOf("宠物", "猫", "狗", "动物", "pet", "cat", "dog", "animal"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("cat", "dog", "pet", "animal", "kitten", "puppy", "bird", "fish"),
                    minConfidence = 0.5f
                )
            ),
            description = "宠物/动物照片"
        ),

        // 美食/食物
        SearchRule(
            keywords = listOf("美食", "食物", "吃的", "food", "meal", "dish", "restaurant", "cooking"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("food", "meal", "dish", "cuisine", "restaurant", "fruit", "vegetable", "dessert", "drink"),
                    minConfidence = 0.5f
                )
            ),
            description = "美食照片"
        ),

        // 建筑
        SearchRule(
            keywords = listOf("建筑", "房子", "大楼", "building", "house", "architecture", "structure"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("building", "house", "architecture", "structure", "tower", "bridge", "church", "temple"),
                    minConfidence = 0.5f
                )
            ),
            description = "建筑照片"
        ),

        // 自然风景
        SearchRule(
            keywords = listOf("风景", "自然", "景色", "landscape", "nature", "scenery", "view"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("landscape", "nature", "scenery", "forest", "tree", "flower", "garden", "park", "sunset", "sky"),
                    minConfidence = 0.5f
                )
            ),
            description = "自然风景照片"
        ),

        // 夜景
        SearchRule(
            keywords = listOf("夜景", "夜晚", "晚上", "night", "dark", "evening"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("night", "dark", "evening", "light", "neon", "city night"),
                    minConfidence = 0.4f
                )
            ),
            description = "夜景照片"
        ),

        // 运动
        SearchRule(
            keywords = listOf("运动", "跑步", "健身", "sport", "exercise", "running", "fitness", "gym"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("sport", "exercise", "running", "fitness", "gym", "football", "basketball", "swimming", "yoga"),
                    minConfidence = 0.5f
                )
            ),
            description = "运动照片"
        ),

        // 花/植物
        SearchRule(
            keywords = listOf("花", "植物", "树", "flower", "plant", "tree", "garden", "botanical"),
            requiredLabels = listOf(
                LabelMatch(
                    labelPatterns = listOf("flower", "plant", "tree", "garden", "leaf", "rose", "botanical", "flora"),
                    minConfidence = 0.5f
                )
            ),
            description = "花卉植物照片"
        )
    )

    /**
     * Match photos against a search query.
     * First tries predefined rules, then falls back to keyword matching.
     */
    fun matchPhotos(query: String, photos: List<PhotoItem>): List<PhotoItem> {
        val normalizedQuery = query.trim().lowercase()

        // Try predefined rules first
        val matchedRule = predefinedRules.find { rule ->
            rule.keywords.any { keyword -> normalizedQuery.contains(keyword.lowercase()) }
        }

        return if (matchedRule != null) {
            photos.filter { photo -> matchesRule(photo, matchedRule) }
        } else {
            // Fallback: flexible keyword matching against labels
            photos.filter { photo -> matchesKeywords(photo, normalizedQuery) }
        }
    }

    private fun matchesRule(photo: PhotoItem, rule: SearchRule): Boolean {
        if (photo.labels.isEmpty()) return false

        // Check required labels
        val requiredMatch = rule.requiredLabels.all { labelMatch ->
            photo.labels.any { label ->
                labelMatch.labelPatterns.any { pattern ->
                    label.text.lowercase().contains(pattern.lowercase()) &&
                    label.confidence >= labelMatch.minConfidence
                }
            }
        }

        if (!requiredMatch) return false

        // Check excluded labels
        val hasExcluded = rule.excludedLabels.any { labelMatch ->
            photo.labels.any { label ->
                labelMatch.labelPatterns.any { pattern ->
                    label.text.lowercase().contains(pattern.lowercase()) &&
                    label.confidence >= labelMatch.minConfidence
                }
            }
        }

        return !hasExcluded
    }

    private fun matchesKeywords(photo: PhotoItem, query: String): Boolean {
        if (photo.labels.isEmpty()) return false

        // Split query into tokens
        val tokens = query.split(Regex("[\\s,，、]+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) {
            // Single keyword, match against any label
            return photo.labels.any { label ->
                label.text.lowercase().contains(query) ||
                query.contains(label.text.lowercase())
            }
        }

        // Match if any token matches any label
        return tokens.any { token ->
            photo.labels.any { label ->
                label.text.lowercase().contains(token) ||
                token.contains(label.text.lowercase())
            }
        }
    }

    fun getSuggestions(): List<String> {
        return listOf(
            "有车", "人物", "光膀子", "泰山", "海滩",
            "宠物", "美食", "建筑", "风景", "夜景",
            "运动", "花卉"
        )
    }

    fun getRuleDescription(query: String): String? {
        val normalizedQuery = query.trim().lowercase()
        return predefinedRules.find { rule ->
            rule.keywords.any { keyword -> normalizedQuery.contains(keyword.lowercase()) }
        }?.description
    }
}
