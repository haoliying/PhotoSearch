package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.example.photosearch.data.AppConfig
import com.example.photosearch.data.LabelTranslator
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
                displayName = "DeepSeek (不支持图片，勿选)",
                apiUrl = "https://api.deepseek.com/chat/completions",
                modelName = "deepseek-chat",
                apiKeyUrl = "https://platform.deepseek.com/api_keys",
                freeInfo = "deepseek-chat 是纯文本模型，无法分析图片；请改用智谱AI / 通义千问 / 硅基流动"
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
            if (providerId == "deepseek") {
                return@withContext VlmResult("", emptyList(), false,
                    "DeepSeek 是纯文本模型，无法分析图片；请在设置里改用智谱AI / 通义千问 / 硅基流动")
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
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()

        // 优先解析结构化 JSON（v3.5.2 提示词格式）
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                val obj = JSONObject(cleaned.substring(jsonStart, jsonEnd + 1))
                val desc = obj.optString("description", "").trim()
                val kws = mutableListOf<String>()
                for (key in listOf("keywords", "objects", "attributes", "scene")) {
                    when (val v = obj.opt(key)) {
                        is JSONArray -> for (i in 0 until v.length()) {
                            val s = v.optString(i, "").trim()
                            // v3.7.0：模型偶尔夹带英文关键词，统一转成中文标签
                            if (s.isNotBlank()) LabelTranslator.toChinese(s)?.let { kws += it }
                        }
                        is String -> if (v.isNotBlank()) {
                            LabelTranslator.toChinese(v.trim())?.let { kws += it }
                        }
                    }
                }
                if (desc.isNotBlank() || kws.isNotEmpty()) {
                    return VlmResult(desc, kws.distinct(), true)
                }
            } catch (_: Exception) {
                // 模型没按 JSON 输出，回退到自由文本解析
            }
        }

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

        // v3.7.0：无论走哪条解析路径，输出给界面的关键词一律中文
        return VlmResult(description, LabelTranslator.toChineseLabels(keywords), true)
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
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
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
            if (bitmap == null) null else rotateByExif(uri, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 应用 EXIF 旋转。BitmapFactory.decodeStream 不会自动处理 orientation，
     * 竖拍照片会「躺着」送进 VLM / ML Kit，物体与文字识别质量明显下降。
     */
    @Suppress("DEPRECATION")
    private fun rotateByExif(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val degrees = when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) bitmap else {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    val rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    if (rotated !== bitmap) bitmap.recycle()
                    rotated
                }
            } ?: bitmap
        } catch (e: Exception) {
            bitmap
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

    private val ANALYSIS_PROMPT = """你是一个照片索引器，为「按内容搜照片」建立索引。只描述画面中确实可见的内容。
严格输出一个 JSON 对象，不要输出 JSON 以外的任何文字，不要用代码块包裹：
{"description":"用中文一到两句话客观描述画面主体、场景、人物动作、衣着与显著物体","objects":["画面中确实存在的具体物体名称，用中文常用名词，例如 轿车、咖啡杯、笔记本电脑、雨伞、气球、篮球、书本、猫"],"scene":"场景类型，例如 室内、户外、海滩、山景、城市街道、餐厅、夜景","attributes":["显著属性，例如 夜晚、下雪、多人合影、光着上身、微笑"],"keywords":["8 到 20 个可用于检索的中文关键词，必须来自上面各项，尽量覆盖所有可搜索内容"]}

规则：
- **所有字段（description / objects / scene / attributes / keywords）一律使用简体中文**，不要输出英文单词、拼音或中英混排；仅品牌、型号等专有名词允许保留英文原文。
- 只写画面里真实存在的东西。看到山不要写「泰山」，除非画面里有可辨认的文字标识；不要猜品牌、地名、人名。
- 物体名必须用具体名词，不要用「物体」「东西」「物品」「景观」这类泛称，否则用户搜不到。
- 若画面中有人光着上身 / 没穿上衣，必须在 attributes 和 keywords 里写「光膀子」。
- 描述开头不要写「这张照片」「画面中」这类套话。""".trimIndent()
}
