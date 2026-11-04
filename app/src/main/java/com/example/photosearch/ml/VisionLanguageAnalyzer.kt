package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.photosearch.data.AppConfig
import com.example.photosearch.data.PhotoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vision-Language Model analyzer that uses cloud VLM APIs to generate
 * rich, detailed descriptions of photos for accurate semantic search.
 *
 * Supported providers:
 * - Google Gemini (gemini-2.0-flash / gemini-1.5-flash) — free tier available
 * - OpenAI GPT-4o — requires paid API key
 *
 * The analyzer sends each photo to the VLM with a structured prompt,
 * receives a detailed Chinese description + keywords, and stores them
 * in PhotoMetadata for search.
 */
class VisionLanguageAnalyzer(private val context: Context) {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_IMAGE_SIZE = 1024
        private const val JPEG_QUALITY = 80
    }

    data class VlmResult(
        val description: String,
        val keywords: List<String>,
        val success: Boolean,
        val error: String? = null
    )

    /**
     * Analyze a photo using the configured VLM API.
     */
    suspend fun analyzePhoto(uri: Uri): VlmResult {
        return withContext(Dispatchers.IO) {
            val provider = AppConfig.getApiProvider(context)
            val apiKey = AppConfig.getApiKey(context)

            if (apiKey.isBlank()) {
                return@withContext VlmResult("", emptyList(), false, "API Key 未配置")
            }

            try {
                val bitmap = loadAndResizeBitmap(uri) ?: return@withContext VlmResult("", emptyList(), false, "无法加载图片")
                val base64Image = bitmapToBase64(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()

                when (provider) {
                    "gemini" -> analyzeWithGemini(apiKey, base64Image)
                    "openai" -> analyzeWithOpenAI(apiKey, base64Image)
                    else -> VlmResult("", emptyList(), false, "不支持的 API 提供商: $provider")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                VlmResult("", emptyList(), false, "分析失败: ${e.message}")
            }
        }
    }

    /**
     * Call Google Gemini API for image understanding.
     */
    private fun analyzeWithGemini(apiKey: String, base64Image: String): VlmResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().apply {
                        put("text", ANALYSIS_PROMPT)
                    })
                    .put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                )
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
            })
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Content-Type", "application/json")
        }

        connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val responseBody = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            return VlmResult("", emptyList(), false, "Gemini API 错误 ($responseCode): $error")
        }

        return parseGeminiResponse(responseBody)
    }

    /**
     * Call OpenAI GPT-4o API for image understanding.
     */
    private fun analyzeWithOpenAI(apiKey: String, base64Image: String): VlmResult {
        val url = URL("https://api.openai.com/v1/chat/completions")

        val requestBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("max_tokens", 1024)
            put("temperature", 0.1)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", ANALYSIS_PROMPT)
                    })
                    .put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                )
            }))
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val responseBody = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            return VlmResult("", emptyList(), false, "OpenAI API 错误 ($responseCode): $error")
        }

        return parseOpenAIResponse(responseBody)
    }

    private fun parseGeminiResponse(responseBody: String): VlmResult {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return VlmResult("", emptyList(), false, "Gemini 返回为空")
            }
            val text = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: ""

            parseVlmText(text)
        } catch (e: Exception) {
            VlmResult("", emptyList(), false, "解析 Gemini 响应失败: ${e.message}")
        }
    }

    private fun parseOpenAIResponse(responseBody: String): VlmResult {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return VlmResult("", emptyList(), false, "OpenAI 返回为空")
            }
            val text = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content") ?: ""

            parseVlmText(text)
        } catch (e: Exception) {
            VlmResult("", emptyList(), false, "解析 OpenAI 响应失败: ${e.message}")
        }
    }

    /**
     * Parse the VLM text response into structured description + keywords.
     * Expected format:
     *   描述: [detailed Chinese description]
     *   关键词: [keyword1], [keyword2], ...
     */
    private fun parseVlmText(text: String): VlmResult {
        val cleaned = text.trim().removePrefix("```").removeSuffix("```").trim()

        var description = ""
        var keywords = listOf<String>()

        // Try structured format first
        val descMatch = Regex("描述[：:]\\s*(.+?)(?=\\n(?:关键词|标签|关键字)[：:]|$)", RegexOption.DOT_MATCHES_ALL)
            .find(cleaned)
        val kwMatch = Regex("(?:关键词|标签|关键字)[：:]\\s*(.+?)(?=\\n|$)", RegexOption.DOT_MATCHES_ALL)
            .find(cleaned)

        if (descMatch != null) {
            description = descMatch.groupValues[1].trim()
        }
        if (kwMatch != null) {
            keywords = kwMatch.groupValues[1]
                .split(Regex("[,，、;；\\s]+"))
                .map { it.trim() }
                .filter { it.length >= 1 }
        }

        // Fallback: use entire text as description
        if (description.isBlank()) {
            description = cleaned
        }

        // Extract additional keywords from description if none provided
        if (keywords.isEmpty() && description.isNotBlank()) {
            keywords = extractKeywordsFromDescription(description)
        }

        return VlmResult(description, keywords, true)
    }

    /**
     * Extract meaningful keywords from a Chinese description.
     */
    private fun extractKeywordsFromDescription(desc: String): List<String> {
        val keywords = mutableSetOf<String>()

        // Common object/scene patterns in Chinese
        val patterns = listOf(
            "汽车", "轿车", "卡车", "公交车", "摩托车", "自行车", "车辆",
            "山", "泰山", "山峰", "山顶", "登山", "爬山",
            "海", "大海", "海滩", "沙滩", "海边", "海洋",
            "河", "湖", "河流", "湖泊",
            "猫", "狗", "鸟", "鱼", "动物", "宠物",
            "花", "树", "草", "植物", "森林",
            "建筑", "房子", "大楼", "桥", "塔",
            "食物", "菜", "水果", "甜点", "饮料",
            "日落", "日出", "夕阳", "夜景",
            "运动", "跑步", "健身", "游泳", "篮球", "足球",
            "婚礼", "聚会", "演唱会",
            "自拍", "合影", "合照",
            "雪", "雨", "云",
            "手机", "电脑", "相机",
            "衣服", "裙子", "裤子", "外套", "衬衫", "T恤",
            "眼镜", "帽子", "鞋",
            "微笑", "开心",
            "室内", "户外", "室外",
            "白天", "夜晚", "晚上",
            "红色", "蓝色", "绿色", "白色", "黑色", "黄色"
        )

        for (pattern in patterns) {
            if (desc.contains(pattern)) {
                keywords.add(pattern)
            }
        }

        // Check for shirtless / bare chest
        if (desc.contains("光膀子") || desc.contains("赤裸上身") || desc.contains("没穿上衣") ||
            desc.contains("光着上身") || desc.contains("裸露上身") || desc.contains("赤膊")) {
            keywords.add("光膀子")
        }

        return keywords.toList()
    }

    private fun loadAndResizeBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, MAX_IMAGE_SIZE)

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private val ANALYSIS_PROMPT = """请仔细观察这张照片，然后用以下格式回复：

描述：用中文详细描述这张照片的内容，包括：
1. 场景中有什么（物体、建筑、自然景观等）
2. 人物在做什么、穿什么衣服、什么表情
3. 这是什么地方（如果能识别出具体地点请说明，比如泰山、长城、某个海滩等）
4. 什么时间（白天/夜晚、什么季节）
5. 天气和环境
6. 整体氛围

关键词：列出10-20个关键词，用逗号分隔，涵盖照片中的物体、人物、场景、活动、颜色等

注意：描述要尽量详细准确，关键词要覆盖所有可搜索的内容。如果照片中有人光着上身/没穿上衣，请在描述和关键词中明确说明。""".trimIndent()
}
