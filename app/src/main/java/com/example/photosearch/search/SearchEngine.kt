package com.example.photosearch.search

import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.toSearchableTags

/**
 * Semantic search engine that matches photos against parsed search intents.
 *
 * Priority order for matching:
 * 1. VLM description text (most accurate - rich Chinese natural language)
 * 2. VLM keywords (structured keywords from VLM)
 * 3. Searchable tags (derived from ML Kit labels + scene type)
 * 4. ML Kit labels (raw English labels)
 */
object SearchEngine {

    data class SearchResult(
        val photo: PhotoItem,
        val score: Float,
        val matchedConditions: List<String>,
        val matchSource: String = "" // For debugging: "description", "vlmKeywords", "tags", "labels"
    )

    fun search(
        intent: SearchIntent,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> {
        if (intent.conditions.isEmpty()) return emptyList()

        val results = photos.mapNotNull { photo ->
            val metadata = metadataMap[photo.id] ?: return@mapNotNull null

            when (intent.logicOperator) {
                LogicOperator.AND -> {
                    val allMatch = intent.conditions.all { condition ->
                        matchCondition(condition, metadata) != condition.negated
                    }
                    if (allMatch) {
                        val (score, source) = calculateScore(intent.conditions, metadata)
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" }, source)
                    } else null
                }
                LogicOperator.OR -> {
                    val anyMatch = intent.conditions.any { condition ->
                        matchCondition(condition, metadata) != condition.negated
                    }
                    if (anyMatch) {
                        val (score, source) = calculateScore(intent.conditions, metadata)
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" }, source)
                    } else null
                }
                LogicOperator.RANK -> {
                    val (score, source) = calculateScore(intent.conditions, metadata)
                    if (score > 0.05f) {
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" }, source)
                    } else null
                }
            }
        }

        return results.sortedByDescending { it.score }
    }

    /**
     * Check if a condition matches any level of the photo's metadata.
     */
    private fun matchCondition(condition: SearchCondition, metadata: PhotoMetadata): Boolean {
        return condition.keywords.any { keyword ->
            val kw = keyword.lowercase().trim()
            if (kw.isEmpty()) return@any false

            // Level 1: VLM description match (most powerful)
            if (metadata.description.isNotBlank() && matchAgainstText(kw, metadata.description)) {
                return@any true
            }

            // Level 2: VLM keywords match
            if (metadata.vlmKeywords.any { vlmKw ->
                val v = vlmKw.lowercase()
                v.contains(kw) || kw.contains(v) || fuzzyMatch(kw, v)
            }) {
                return@any true
            }

            // Level 3: Searchable tags match
            val tags = metadata.toSearchableTags()
            if (tags.any { tag -> tag.contains(kw) || kw.contains(tag) || fuzzyMatch(kw, tag) }) {
                return@any true
            }

            // Level 4: ML Kit labels match
            if (metadata.labels.any { label ->
                val lt = label.text.lowercase()
                (lt.contains(kw) || kw.contains(lt)) && label.confidence >= condition.minConfidence
            }) {
                return@any true
            }

            false
        }
    }

    /**
     * Match a keyword against a long text (description).
     * Returns true if the keyword appears in the text or is semantically close.
     */
    private fun matchAgainstText(keyword: String, text: String): Boolean {
        val normalizedText = text.lowercase()

        // Direct substring match
        if (normalizedText.contains(keyword)) return true

        // For Chinese keywords, check if key characters appear near each other
        if (keyword.length >= 2) {
            // Check 2-char substrings
            for (i in 0..keyword.length - 2) {
                val sub = keyword.substring(i, i + 2)
                if (normalizedText.contains(sub)) return true
            }
        }

        // Fuzzy match against words in the text
        val words = normalizedText.split(Regex("[\\s，。、；！？,.\\s]+")).filter { it.length >= 2 }
        for (word in words) {
            if (fuzzyMatch(keyword, word)) return true
        }

        return false
    }

    /**
     * Calculate a relevance score with source tracking.
     * Returns (score, bestMatchSource).
     */
    private fun calculateScore(
        conditions: List<SearchCondition>,
        metadata: PhotoMetadata
    ): Pair<Float, String> {
        var totalScore = 0f
        var totalWeight = 0f
        var bestSource = "labels"
        var bestSourceScore = 0f

        for (condition in conditions) {
            totalWeight += condition.weight

            var conditionScore = 0.0
            var conditionSource = "labels"

            for (keyword in condition.keywords) {
                val kw = keyword.lowercase().trim()
                if (kw.isEmpty()) continue

                var kwScore = 0.0

                // Description match: highest score (0.8-1.0)
                if (metadata.description.isNotBlank()) {
                    val descScore = scoreDescriptionMatch(kw, metadata.description)
                    if (descScore > kwScore) {
                        kwScore = descScore
                        conditionSource = "description"
                    }
                }

                // VLM keywords match: high score (0.6-0.8)
                val vlmKwScore = metadata.vlmKeywords.maxOfOrNull { vlmKw ->
                    val v = vlmKw.lowercase()
                    when {
                        v == kw -> 0.8
                        v.contains(kw) || kw.contains(v) -> 0.7
                        fuzzyMatch(kw, v) -> 0.5
                        else -> 0.0
                    }
                } ?: 0.0
                if (vlmKwScore > kwScore) {
                    kwScore = vlmKwScore
                    conditionSource = "vlmKeywords"
                }

                // Tags match: medium score (0.4-0.6)
                val tags = metadata.toSearchableTags()
                val tagScore = tags.maxOfOrNull { tag ->
                    when {
                        tag == kw -> 0.6
                        tag.contains(kw) || kw.contains(tag) -> 0.5
                        fuzzyMatch(kw, tag) -> 0.3
                        else -> 0.0
                    }
                } ?: 0.0
                if (tagScore > kwScore) {
                    kwScore = tagScore
                    conditionSource = "tags"
                }

                // Label match: lower score (0.2-0.4)
                val labelScore = metadata.labels.maxOfOrNull { label ->
                    val lt = label.text.lowercase()
                    if ((lt.contains(kw) || kw.contains(lt)) && label.confidence >= condition.minConfidence) {
                        label.confidence.toDouble() * 0.4
                    } else 0.0
                } ?: 0.0
                if (labelScore > kwScore) {
                    kwScore = labelScore
                    conditionSource = "labels"
                }

                conditionScore = maxOf(conditionScore, kwScore)
            }

            // Apply negation
            val finalScore = if (condition.negated) {
                (1.0 - conditionScore).toFloat()
            } else {
                conditionScore.toFloat()
            }

            totalScore += finalScore * condition.weight

            // Track best source
            if (conditionScore > bestSourceScore) {
                bestSourceScore = conditionScore.toFloat()
                bestSource = conditionSource
            }
        }

        val normalizedScore = if (totalWeight > 0f) totalScore / totalWeight else 0f
        return normalizedScore to bestSource
    }

    /**
     * Score how well a keyword matches a description text.
     */
    private fun scoreDescriptionMatch(keyword: String, description: String): Double {
        val desc = description.lowercase()

        // Exact keyword in description
        if (desc.contains(keyword)) {
            // Longer keyword matches are more significant
            val lengthBonus = (keyword.length / 10.0).coerceAtMost(0.2)
            return 0.8 + lengthBonus
        }

        // For Chinese: check character overlap with nearby context
        if (keyword.length >= 2) {
            var matchedChars = 0
            for (i in 0..keyword.length - 2) {
                val sub = keyword.substring(i, i + 2)
                if (desc.contains(sub)) matchedChars++
            }
            if (matchedChars > 0) {
                val ratio = matchedChars.toFloat() / (keyword.length - 1)
                return (ratio * 0.6).toDouble()
            }
        }

        // Fuzzy match against words in description
        val words = desc.split(Regex("[\\s，。、；！？,.\\s]+")).filter { it.length >= 2 }
        val bestFuzzy = words.maxOfOrNull { word ->
            if (fuzzyMatch(keyword, word)) 0.4 else 0.0
        } ?: 0.0

        return bestFuzzy
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        // For Chinese: check if any 2-char substring matches
        if (a.length >= 2 && b.length >= 2) {
            for (i in 0..a.length - 2) {
                val sub = a.substring(i, i + 2)
                if (b.contains(sub)) return true
            }
        }

        // For English: check character overlap
        val charsA = a.toCharArray().toSet()
        val charsB = b.toCharArray().toSet()
        val overlap = charsA.intersect(charsB).size.toFloat()
        val maxLen = maxOf(charsA.size, charsB.size).toFloat()
        return maxLen > 0 && (overlap / maxLen) > 0.6f
    }

    fun quickSearch(
        query: String,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> {
        val intent = IntentParser.parse(query)
        return search(intent, photos, metadataMap)
    }
}
