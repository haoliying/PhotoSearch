package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.photosearch.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vision-Language Model analyzer using cloud VLM APIs.
 *
 * 支持的国内免费 API（均兼容 OpenAI 格式）：
 * - 智谱AI (glm-4v-flash) — 完全免费
 * - 阿里通义千问 (qwen-vl-plus) — 有免费额度
 * - DeepSeek (deepseek-chat) — 有免费额度
 * - 硅基流动 (Qwen/Qwen2-VL-7B-Instruct) — 有免费额度
 */
class VisionLanguageAnalyzer(private val context: Context) {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_IMAGE_SIZE = 1024
        private const val JPEG_QUALITY = 80

        /**
         * Provider configurations: displayName, apiUrl, modelName, apiKeyUrl
         */
        data class ProviderConfig(
            val id: String,
            val displayName: String,
            val apiUrl: String,
            val modelName: String,
            val apiKeyUrl: String,
            val freeInfo: String
        )

        val PROVIDERS = listOf(
            ProviderConfig(
                id = "zhipu",
                displayName = "智谱AI (免费)",
                apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                modelName = "glm-4v-flash",
                apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
                freeInfo = "注册即送免费额度，glm-4v-flash 完全免费"
            ),
            ProviderConfig(
                id = "dashscope",
                displayName = "通义千问 (免费额度)",
                apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                modelName = "qwen-vl-plus",
                apiKeyUrl = "https://dashscope.console.aliyun.com/apiKey",
                freeInfo = "阿里云账号开通后有免费 token 额度"
            ),
            ProviderConfig(
                id = "deepseek",
                displayName = "DeepSeek (免费额度)",
                apiUrl = "https://api.deepseek.com/chat/completions",
                modelName = "deepseek-chat",
                apiKeyUrl = "https://platform.deepseek.com/api_keys",
                freeInfo = "注册后有免费额度"
            ),
            ProviderConfig(
                id = "siliconflow",
                displayName = "硅基流动 (免费额度)",
                apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
                modelName = "Qwen/Qwen2-VL-7B-Instruct",
                apiKeyUrl = "https://cloud.siliconflow.cn/account/ak",
                freeInfo = "注册后有免费额度，支持多种开源模型"
            )
        )

        fun getProviderConfig(providerId: String): ProviderConfig {
            return PROVIDERS.find { it.id == providerId } ?: PROVIDERS[0]
        }
    }

    data class VlmResult(
        val description: String,
        val keywords: List<String>,
        val success: Boolean,
        val error: String? = null
    )

    suspend fun analyzePhoto(uri: Uri): VlmResult {
        return withContext(Dispatchers.IO) {
            val providerId = AppConfig.getApiProvider(context)
            val apiKey = AppConfig.getApiKey(context)

            if (apiKey.isBlank()) {
                return@withContext VlmResult("", emptyList(), false, "API Key 未配置")
            }

            try {
                val bitmap = loadAndResizeBitmap(uri)
                    ?: return@withContext VlmResult("", emptyList(), false, "无法加载图片")
                val base64Image = bitmapToBase64(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()

                val config = getProviderConfig(providerId)
                analyzeWithOpenAICompatible(config, apiKey, base64Image)
            } catch (e: Exception) {
                e.printStackTrace()
                VlmResult("", emptyList(), false, "分析失败: ${e.message}")
            }
        }
    }

    /**
     * Unified OpenAI-compatible API call.
     * All supported Chinese providers use this format.
     */
    private fun analyzeWithOpenAICompatible(
        config: ProviderConfig,
        apiKey: String,
        base64Image: String
    ): VlmResult {
        val url = URL(config.apiUrl)

        val requestBody = JSONObject().apply {
            put("model", config.modelName)
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
            return VlmResult("", emptyList(), false,
                "${config.displayName} API 错误 ($responseCode): $error")
        }

        return parseResponse(responseBody, config.displayName)
    }

    private fun parseResponse(responseBody: String, providerName: String): VlmResult {
        return try {
            val json = JSONObject(responseBody)

            // Check for error
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                val msg = error.optString("message", "未知错误")
                return VlmResult("", emptyList(), false, "$providerName: $msg")
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return VlmResult("", emptyList(), false, "$providerName 返回为空")
            }

            val text = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content") ?: ""

            if (text.isBlank()) {
                return VlmResult("", emptyList(), false, "$providerName 返回内容为空")
            }

            parseVlmText(text)
        } catch (e: Exception) {
            VlmResult("", emptyList(), false, "解析 $providerName 响应失败: ${e.message}")
        }
    }

    private fun parseVlmText(text: String): VlmResult {
        val cleaned = text.trim()
            .removePrefix("```").removeSuffix("```").trim()

        var description = ""
        var keywords = listOf<String>()

        // Try structured format: 描述: ... 关键词: ...
        val descMatch = Regex(
            "描述[：:]\\s*(.+?)(?=\\n(?:关键词|标签|关键字)[：:]|$)",
            RegexOption.DOT_MATCHES_ALL
        ).find(cleaned)
        val kwMatch = Regex(
            "(?:关键词|标签|关键字)[：:]\\s*(.+?)(?=\\n|$)",
            RegexOption.DOT_MATCHES_ALL
        ).find(cleaned)

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

    private fun extractKeywordsFromDescription(desc: String): List<String> {
        val keywords = mutableSetOf<String>()

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

                val sampleSize = calculateSampleSize(
                    options.outWidth, options.outHeight, MAX_IMAGE_SIZE
                )

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
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
