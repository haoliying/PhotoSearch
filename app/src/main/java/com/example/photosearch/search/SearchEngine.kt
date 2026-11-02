package com.example.photosearch.search

import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.toSearchableTags

/**
 * Semantic search engine that matches photos against parsed search intents.
 * Uses tag matching, scoring, and compound logic for accurate results.
 */
object SearchEngine {

    data class SearchResult(
        val photo: PhotoItem,
        val score: Float,
        val matchedConditions: List<String>
    )

    /**
     * Search photos using a parsed SearchIntent.
     * Returns photos sorted by relevance score.
     */
    fun search(
        intent: SearchIntent,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> {
        if (intent.conditions.isEmpty()) return emptyList()

        val results = photos.mapNotNull { photo ->
            val metadata = metadataMap[photo.id] ?: return@mapNotNull null
            val tags = metadata.toSearchableTags()
            val labels = metadata.labels

            when (intent.logicOperator) {
                LogicOperator.AND -> {
                    // All conditions must match
                    val allMatch = intent.conditions.all { condition ->
                        matchCondition(condition, tags, labels) != condition.negated
                    }
                    if (allMatch) {
                        val score = calculateScore(intent.conditions, tags, labels)
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" })
                    } else null
                }
                LogicOperator.OR -> {
                    // Any condition can match
                    val anyMatch = intent.conditions.any { condition ->
                        matchCondition(condition, tags, labels) != condition.negated
                    }
                    if (anyMatch) {
                        val score = calculateScore(intent.conditions, tags, labels)
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" })
                    } else null
                }
                LogicOperator.RANK -> {
                    // Score-based ranking, include all photos with score > 0
                    val score = calculateScore(intent.conditions, tags, labels)
                    if (score > 0f) {
                        SearchResult(photo, score, intent.conditions.map { it.keywords.firstOrNull() ?: "" })
                    } else null
                }
            }
        }

        return results.sortedByDescending { it.score }
    }

    /**
     * Check if a condition matches the photo's tags.
     * Returns true if any keyword matches any tag.
     */
    private fun matchCondition(
        condition: SearchCondition,
        tags: Set<String>,
        labels: List<com.example.photosearch.data.PhotoLabel>
    ): Boolean {
        return condition.keywords.any { keyword ->
            val normalizedKeyword = keyword.lowercase().trim()
            if (normalizedKeyword.isEmpty()) return@any false

            // Direct tag match
            if (tags.any { tag -> tag.contains(normalizedKeyword) || normalizedKeyword.contains(tag) }) {
                return@any true
            }

            // Label match with confidence check
            if (labels.any { label ->
                val labelText = label.text.lowercase()
                (labelText.contains(normalizedKeyword) || normalizedKeyword.contains(labelText)) &&
                label.confidence >= condition.minConfidence
            }) {
                return@any true
            }

            // Fuzzy match: check if keyword shares characters with tags
            if (normalizedKeyword.length >= 2) {
                val fuzzyMatch = tags.any { tag ->
                    fuzzyMatch(normalizedKeyword, tag.lowercase())
                }
                if (fuzzyMatch) return@any true
            }

            false
        }
    }

    /**
     * Calculate a relevance score for a photo against conditions.
     */
    private fun calculateScore(
        conditions: List<SearchCondition>,
        tags: Set<String>,
        labels: List<com.example.photosearch.data.PhotoLabel>
    ): Float {
        var totalScore = 0f
        var totalWeight = 0f

        for (condition in conditions) {
            totalWeight += condition.weight

            val matchScore = condition.keywords.sumOf { keyword ->
                val normalizedKeyword = keyword.lowercase().trim()
                if (normalizedKeyword.isEmpty()) return@sumOf 0.0

                var score = 0.0

                // Exact tag match: high score
                if (tags.contains(normalizedKeyword)) {
                    score += 1.0
                }

                // Partial tag match: medium score
                tags.forEach { tag ->
                    if (tag.contains(normalizedKeyword) || normalizedKeyword.contains(tag)) {
                        score += 0.7
                    }
                }

                // Label match with confidence weighting
                labels.forEach { label ->
                    val labelText = label.text.lowercase()
                    if (labelText.contains(normalizedKeyword) || normalizedKeyword.contains(labelText)) {
                        score += label.confidence.toDouble() * 0.8
                    }
                }

                // Fuzzy match: low score
                if (normalizedKeyword.length >= 2) {
                    tags.forEach { tag ->
                        if (fuzzyMatch(normalizedKeyword, tag.lowercase())) {
                            score += 0.3
                        }
                    }
                }

                score
            }

            val normalizedMatchScore = if (condition.keywords.isNotEmpty()) {
                (matchScore / condition.keywords.size).coerceAtMost(1.0).toFloat()
            } else 0f

            // Apply negation: if negated, invert the score
            val finalScore = if (condition.negated) {
                1.0f - normalizedMatchScore
            } else {
                normalizedMatchScore
            }

            totalScore += finalScore * condition.weight
        }

        return if (totalWeight > 0f) totalScore / totalWeight else 0f
    }

    /**
     * Simple fuzzy matching: checks character overlap ratio.
     */
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

    /**
     * Quick search using raw query string.
     */
    fun quickSearch(
        query: String,
        photos: List<PhotoItem>,
        metadataMap: Map<Long, PhotoMetadata>
    ): List<SearchResult> {
        val intent = IntentParser.parse(query)
        return search(intent, photos, metadataMap)
    }
}
