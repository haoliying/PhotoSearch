package com.example.photosearch.search

import com.example.photosearch.data.LabelTranslator
import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.SceneType
import kotlin.math.max
import kotlin.math.min

/**
 * v3.11.0：**不依赖大模型的本地准确度增强**。
 *
 * 旧版端侧分析其实已经算出了不少信号，但绝大多数从未进入检索索引：
 *  - dominantColors（主色）算完只存不用；
 *  - width / height（横竖幅、全景比例）没变成可检索词；
 *  - faces 只贡献了「有人 / 微笑」，人数信息（单人 / 合影 / 人群）全丢；
 *  - hasText 只给 0.50 的弱权重，低于全文命中阈值 0.55，等于搜不到；
 *  - 标签置信度 < 0.30 的整批丢弃，而车、宠物这类目标在远景 / 遮挡下
 *    常常正好落在这个区间 —— 这是「有汽车的照片」漏召的主要原因之一；
 *  - classifyScene 只保留得分最高的**一个**场景，一张「夜晚 + 城市 + 车」
 *    的照片只留下一个标签。
 *
 * 本对象把这些**已经落盘的结构化字段**重新编译成中文可检索标签。它是纯函数，
 * 因此：① 升级到新版后，旧的分析结果无需重跑即可立刻享受增强；
 * ② 完全离线、不联网、不占用大模型额度；③ 可离线评测。
 *
 * 权重与 SearchEngine.TEXT_MIN_EVIDENCE(0.55) 对齐：
 *  - CONCRETE(0.66)：确定性高的构图 / 人数 / 文字信号，可单独命中；
 *  - STRONG(0.60)：颜色 / 明暗 / 场景等推断信号，可单独命中但排序靠后；
 *  - WEAK(0.44)：低置信标签与次要氛围，单独不命中，靠「多弱证据叠加」生效。
 */
object LocalVisualTags {

    const val W_CONCRETE = 0.66f
    const val W_STRONG = 0.60f
    const val W_WEAK = 0.44f
    /** 低于此置信度的 ML Kit 标签，旧版直接丢弃，现在作为弱证据纳入。 */
    const val LOW_LABEL_FLOOR = 0.15f
    const val LOW_LABEL_CEILING = 0.30f

    data class Tagged(val tag: String, val weight: Float)

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    private fun brightnessOf(r: Int, g: Int, b: Int): Float =
        (0.299f * r + 0.587f * g + 0.114f * b) / 255f

    private fun saturationOf(r: Int, g: Int, b: Int): Float {
        val mx = max(r, max(g, b)).coerceAtLeast(1)
        val mn = min(r, min(g, b))
        return (mx - mn).toFloat() / mx
    }

    /** 从 dominantColors 估算亮度 / 饱和度，兼容没有新字段的历史缓存。 */
    private fun legacyStats(m: PhotoMetadata): Pair<Float, Float> {
        if (m.dominantColors.isEmpty()) return -1f to -1f
        var bs = 0f
        var ss = 0f
        for (c in m.dominantColors) {
            val r = red(c); val g = green(c); val b = blue(c)
            bs += brightnessOf(r, g, b)
            ss += saturationOf(r, g, b)
        }
        val n = m.dominantColors.size
        return (bs / n) to (ss / n)
    }

    fun build(m: PhotoMetadata, exif: PhotoExif? = null): List<Tagged> {
        val out = ArrayList<Tagged>(40)
        fun add(tag: String, weight: Float) {
            val t = tag.trim().lowercase()
            if (t.length >= 2) out += Tagged(t, weight)
        }
        fun addAll(tags: Collection<String>, weight: Float) = tags.forEach { add(it, weight) }

        // ---------------------------------------------------------------
        // 1. 新字段：分析时已经算好的本地增强标签（含 OCR 文本等）
        // ---------------------------------------------------------------
        m.localTags.forEach { add(it, W_STRONG) }
        m.sceneTags.forEach { add(it, W_STRONG) }
        if (m.ocrText.isNotBlank()) {
            add(m.ocrText.take(800), 0.85f)
            m.ocrText.split(Regex("""[\s,，。、;；:：!！?？\n]+"""))
                .map { it.trim() }
                .filter { it.length in 2..20 }
                .distinct()
                .take(30)
                .forEach { add(it, 0.82f) }
        }

        // ---------------------------------------------------------------
        // 2. 颜色 / 明暗：主色、色调、夜景、黑白、鲜艳
        // ---------------------------------------------------------------
        val (legacyBrightness, legacySaturation) = legacyStats(m)
        val brightness = if (m.brightness >= 0f) m.brightness else legacyBrightness
        val saturation = if (m.saturation >= 0f) m.saturation else legacySaturation

        val colors = m.dominantColors.take(5)
        if (colors.isNotEmpty()) {
            val primary = colors[0]
            val r = red(primary); val g = green(primary); val b = blue(primary)
            val sat = saturationOf(r, g, b)
            val bri = brightnessOf(r, g, b)

            when {
                b > r * 1.25f && b > g * 1.08f && b > 90 -> {
                    addAll(listOf("蓝色调", "蓝色"), W_STRONG)
                    if (bri > 0.45f) addAll(listOf("蓝天", "晴朗", "天空"), W_STRONG)
                }
                g > r * 1.12f && g > b * 1.12f && g > 70 -> {
                    addAll(listOf("绿色调", "绿色", "绿植", "草地", "自然"), W_STRONG)
                }
                r > g * 1.30f && r > b * 1.35f && r > 110 -> {
                    addAll(listOf("暖色调", "红色调", "红色", "秋色", "夕阳色"), W_STRONG)
                }
                r > 195 && g > 195 && b > 195 -> {
                    addAll(listOf("白色调", "明亮", "高调"), W_STRONG)
                    if (sat < 0.12f) add("雪白", W_STRONG)
                }
                r < 70 && g < 70 && b < 70 -> addAll(listOf("暗色调", "深色"), W_WEAK)
            }
            // 次要颜色作为弱证据，帮助「多弱证据叠加」
            colors.drop(1).take(2).forEach { c ->
                val cr = red(c); val cg = green(c); val cb = blue(c)
                when {
                    cb > cr * 1.25f && cb > cg * 1.08f -> add("蓝色调", W_WEAK)
                    cg > cr * 1.12f && cg > cb * 1.12f -> add("绿色调", W_WEAK)
                    cr > cg * 1.30f && cr > cb * 1.35f -> add("暖色调", W_WEAK)
                }
            }
        }

        if (brightness >= 0f) {
            when {
                brightness < 0.22f -> addAll(listOf("夜景", "夜晚", "暗光", "夜间", "低照度"), W_STRONG)
                brightness < 0.34f -> addAll(listOf("暗调", "弱光"), W_WEAK)
                brightness > 0.82f -> addAll(listOf("明亮", "高亮", "高调"), W_WEAK)
            }
        }
        if (saturation >= 0f) {
            when {
                saturation < 0.10f -> addAll(listOf("黑白", "灰调", "低饱和", "单色"), W_CONCRETE)
                saturation > 0.55f -> addAll(listOf("鲜艳", "高饱和", "色彩丰富"), W_WEAK)
            }
        }

        // ---------------------------------------------------------------
        // 3. 构图：横竖幅、方形、全景、长图、截图比例
        // ---------------------------------------------------------------
        val w = m.width
        val h = m.height
        if (w > 0 && h > 0) {
            val ratio = w.toFloat() / h
            when {
                ratio >= 2.2f -> addAll(listOf("全景", "宽幅", "横幅", "长图"), W_CONCRETE)
                ratio >= 1.25f -> addAll(listOf("横幅", "横图"), W_CONCRETE)
                ratio >= 0.80f -> addAll(listOf("方形", "方图"), W_CONCRETE)
                else -> addAll(listOf("竖幅", "竖图"), W_CONCRETE)
            }
            if (ratio <= 0.62f && m.hasText) {
                addAll(listOf("手机截图", "长截图", "聊天记录"), W_CONCRETE)
            }
            if (w >= 3000 || h >= 3000) add("高分辨率", W_WEAK)
        }

        // ---------------------------------------------------------------
        // 4. 人数：单人 / 双人 / 合影 / 人群 / 自拍
        // ---------------------------------------------------------------
        val faceCount = if (m.faceCount > 0) m.faceCount else m.faces.size
        when {
            faceCount == 1 -> {
                addAll(listOf("单人", "一个人", "独照"), W_CONCRETE)
                if (w > 0 && h > w) add("自拍", W_CONCRETE)
            }
            faceCount == 2 -> addAll(listOf("双人", "两个人", "合影", "合照"), W_CONCRETE)
            faceCount in 3..6 -> addAll(listOf("合影", "合照", "多人", "一群人", "集体照"), W_CONCRETE)
            faceCount >= 7 -> addAll(listOf("人群", "大合影", "多人", "集体照"), W_CONCRETE)
        }
        if (m.faces.any { it.smilingProbability > 0.6f }) addAll(listOf("微笑", "笑容", "开心"), W_WEAK)

        // ---------------------------------------------------------------
        // 5. 文字 / 文档：旧版 hasText 权重太低，等于搜不到。
        //    「文字 / 文档」是确定信号，可单独命中；
        //    「截图 / 招牌 / 票据」等**具体载体**只作弱证据 —— 一张有文字的
        //    书页照片不该被「截图」召回，否则精确率会明显下降。
        //    真正的截图另有构图信号（竖长图 + 文字 → 手机截图）给强权重。
        // ---------------------------------------------------------------
        if (m.hasText || m.ocrText.isNotBlank()) {
            addAll(listOf("文字", "文档", "含文字", "图片文字", "有文字"), W_CONCRETE)
            addAll(
                listOf("截图", "招牌", "字幕", "菜单", "票据", "海报", "屏幕截图"),
                W_WEAK
            )
        }

        // ---------------------------------------------------------------
        // 6. 多场景标签：旧版只保留一个 sceneType，这里补全同义中文词
        // ---------------------------------------------------------------
        val sceneWords: List<String> = when (m.sceneType) {
            SceneType.BEACH_SEA -> listOf("海滩", "海边", "沙滩", "大海", "海景", "海岸", "海滨")
            SceneType.MOUNTAIN_HILL -> listOf("山景", "登山", "爬山", "山峰", "山脉", "山")
            SceneType.NIGHT_SCENE -> listOf("夜景", "夜晚", "夜色", "夜间")
            SceneType.FOOD_DRINK -> listOf("美食", "食物", "饭菜", "餐饮")
            SceneType.VEHICLE_SCENE -> listOf("车辆", "汽车", "交通工具", "车")
            SceneType.ANIMAL_PET -> listOf("动物", "宠物", "猫", "狗")
            SceneType.PEOPLE_PORTRAIT -> listOf("人物", "人像", "肖像")
            SceneType.LANDSCAPE -> listOf("风景", "景色", "风光")
            SceneType.SPORT_EXERCISE -> listOf("运动", "健身", "体育")
            SceneType.OUTDOOR_NATURE -> listOf("户外", "自然", "野外")
            SceneType.OUTDOOR_URBAN -> listOf("城市", "街道", "都市", "建筑")
            SceneType.INDOOR_HOME, SceneType.INDOOR_PUBLIC, SceneType.INDOOR_EVENT ->
                listOf("室内", "室内照", "屋内")
            else -> emptyList()
        }
        addAll(sceneWords, W_STRONG)
        when (m.isOutdoor) {
            true -> addAll(listOf("户外", "室外", "外景"), W_WEAK)
            false -> addAll(listOf("室内", "室内照"), W_WEAK)
            null -> Unit
        }

        // ---------------------------------------------------------------
        // 7. 低置信标签：从「直接丢弃」改为弱证据（远景小车、遮挡宠物等）
        // ---------------------------------------------------------------
        m.labels
            .filter { it.confidence in LOW_LABEL_FLOOR..LOW_LABEL_CEILING && it.text.isNotBlank() }
            .take(8)
            .forEach { label ->
                add(label.text, W_WEAK)
                LabelTranslator.toChineseTags(label.text).forEach { add(it, W_WEAK) }
            }

        // ---------------------------------------------------------------
        // 8. EXIF 推断：闪光灯 / 高ISO / 长曝光 往往意味着夜景或室内
        // ---------------------------------------------------------------
        if (exif != null) {
            val exifTags = exif.searchableExifTags()
            if (exifTags.contains("已闪光") && brightness >= 0f && brightness < 0.5f) {
                add("闪光灯夜景", W_WEAK)
            }
            if (exifTags.contains("长曝光") || exifTags.contains("慢门")) add("夜景长曝光", W_WEAK)
            if (exifTags.contains("广角") && w > 0 && h > 0 && w.toFloat() / h >= 1.6f) {
                add("广角风景", W_WEAK)
            }
        }

        // 去重：同一标签只保留最高权重，避免索引膨胀
        return out.groupBy { it.tag }
            .map { (tag, list) -> Tagged(tag, list.maxOf { it.weight }) }
    }
}
