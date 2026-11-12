package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color as AndroidColor
import android.media.ExifInterface
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.example.photosearch.data.*
import com.example.photosearch.service.AnalysisScheduling
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceDetector
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Enhanced image analyzer that uses multiple ML Kit APIs to extract rich metadata:
 * - Image Labeling (objects, scenes, concepts)
 * - Object Detection (specific objects with bounding boxes)
 * - Face Detection (people, expressions)
 * - Color Analysis (dominant colors)
 * - Scene Classification (indoor/outdoor, type of scene)
 */
class EnhancedImageAnalyzer(context: Context) {

    private val labeler: com.google.mlkit.vision.label.ImageLabeler
    private val objectDetector: ObjectDetector
    private val faceDetector: FaceDetector

    init {
        // Image labeling with lower threshold for more tags
        val labelOptions = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.2f)
            .build()
        labeler = ImageLabeling.getClient(labelOptions)

        // Object detection in multiple mode for more objects
        val objectOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        objectDetector = ObjectDetection.getClient(objectOptions)

        // Face detection with classification
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)
    }

    /**
     * Analyze a photo and return rich metadata.
     *
     * v3.11.2：不再强制切到 Dispatchers.Default。调用方（AnalysisService）会把
     * 本方法放到「低优先级专用固定线程池」上，内部 4 个 async 继承同一个 dispatcher：
     *  - 分析与检索彻底隔离，不抢搜索线程的 CPU；
     *  - 多张照片真并行（v3.11.1 强制 Default 会让所有 worker 挤同一个池）。
     */
    suspend fun analyzePhoto(uri: Uri, context: Context): PhotoMetadata {
        return try {
                val bitmap = loadBitmap(uri, context) ?: return PhotoMetadata(photoId = 0)

                // Run all analyses in parallel
                val (labels, objects, faces, colors) = coroutineScope {
                    val labelsDeferred = async { detectLabels(bitmap) }
                    val objectsDeferred = async { detectObjects(bitmap) }
                    val facesDeferred = async { detectFaces(bitmap) }
                    val colorsDeferred = async { analyzeColors(bitmap) }

                    listOf(
                        labelsDeferred.await(),
                        objectsDeferred.await(),
                        facesDeferred.await(),
                        colorsDeferred.await()
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val labelList = labels as List<PhotoLabel>
                @Suppress("UNCHECKED_CAST")
                val objectList = objects as List<com.example.photosearch.data.DetectedObject>
                @Suppress("UNCHECKED_CAST")
                val faceList = faces as List<FaceInfo>
                @Suppress("UNCHECKED_CAST")
                val colorStats = colors as VisualStats

                // v3.11.0：保留全部达标场景，而不是只留一个 sceneType
                val sceneScores = scoreScenes(labelList, objectList)
                val sceneType = sceneScores.maxByOrNull { it.value }?.key ?: SceneType.UNKNOWN
                val sceneTags = multiSceneTags(sceneScores)
                val isOutdoor = inferOutdoor(labelList, sceneType)
                val width = bitmap.width
                val height = bitmap.height

                if (!bitmap.isRecycled) bitmap.recycle()

                PhotoMetadata(
                    photoId = 0, // Will be set by caller
                    labels = labelList,
                    detectedObjects = objectList,
                    faces = faceList,
                    sceneType = sceneType,
                    dominantColors = colorStats.colors,
                    isOutdoor = isOutdoor,
                    hasText = labelList.any { it.text.lowercase() in listOf("text", "document", "book", "sign", "writing", "lettering") },
                    width = width,
                    height = height,
                    isAnalyzed = true,
                    // v3.11.0 本地增强信号（纯端侧，不联网）
                    brightness = colorStats.brightness,
                    saturation = colorStats.saturation,
                    faceCount = faceList.size,
                    sceneTags = sceneTags
                )
        } catch (e: Exception) {
            e.printStackTrace()
            PhotoMetadata(photoId = 0, isAnalyzed = true)
        }
    }

    /**
     * v3.11.1：解码入口。
     * 优先走 [loadBitmapViaFd]（单文件描述符复用，一次打开完成 EXIF / 量尺寸 / 解码），
     * 失败自动回退 [loadBitmapViaStreams]（v3.11.0 的三次开流路径）。
     * 端侧分析慢的主因之一是每张照片要打开文件 3 次，这里降到 1 次。
     */
    private fun loadBitmap(uri: Uri, context: Context): Bitmap? {
        loadBitmapViaFd(uri, context)?.let { return it }
        return loadBitmapViaStreams(uri, context)
    }

    /**
     * 单 fd 复用：openAssetFileDescriptor 只打开一次，三个阶段间用 lseek 归零。
     * ExifInterface(FileDescriptor) 支持随机访问只读头部；decodeFileDescriptor
     * 从当前偏移开始解码，所以每次解码前 seek 回 0。
     * 任何一步失败都返回 null，由调用方回退流路径，不影响可用性。
     */
    private fun loadBitmapViaFd(uri: Uri, context: Context): Bitmap? {
        return try {
            val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            pfd.use { afd ->
                val fd = afd.fileDescriptor
                fun seekStart() {
                    try {
                        Os.lseek(fd, 0, OsConstants.SEEK_SET)
                    } catch (_: Exception) {
                    }
                }

                // 1) EXIF 方向（随机访问，只读头部）
                val orientation = try {
                    seekStart()
                    ExifInterface(fd).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (_: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }

                // 2) 量尺寸
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                seekStart()
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                // 3) v3.11.2：按采样解码（最大边长 768px，ML Kit 内部仍会缩放到
                //    224~320 推理，标签精度几乎不变，解码时间与内存明显下降）
                val sampleSize = calculateInSampleSize(
                    bounds.outWidth, bounds.outHeight,
                    AnalysisScheduling.DECODE_MAX_PX, AnalysisScheduling.DECODE_MAX_PX
                )
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                seekStart()
                val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions) ?: return null

                rotateBitmap(bitmap, AnalysisScheduling.orientationToDegrees(orientation))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** v3.11.0 的原始路径（保留作回退）：量尺寸 + 解码 + EXIF 各开一次流。 */
    private fun loadBitmapViaStreams(uri: Uri, context: Context): Bitmap? {
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // First pass: get dimensions
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                // Calculate sample size for analysis (max 1024px)
                val sampleSize = calculateInSampleSize(
                    options.outWidth, options.outHeight,
                    AnalysisScheduling.DECODE_MAX_PX, AnalysisScheduling.DECODE_MAX_PX
                )

                // Second pass: decode with sample size
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
            if (bitmap == null) null else rotateByExif(uri, bitmap, context)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 应用 EXIF 旋转，避免竖拍照片横躺着送入 ML Kit。 */
    @Suppress("DEPRECATION")
    private fun rotateByExif(uri: Uri, bitmap: Bitmap, context: Context): Bitmap {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val degrees = AnalysisScheduling.orientationToDegrees(
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                )
                rotateBitmap(bitmap, degrees)
            } ?: bitmap
        } catch (e: Exception) {
            bitmap
        }
    }

    /** 按角度旋转位图；0 度直接返回原对象，失败时也返回原图。 */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize.coerceAtLeast(1)
    }

    // --- Label Detection ---
    private suspend fun detectLabels(bitmap: Bitmap): List<PhotoLabel> {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val result = labels.map { label ->
                        PhotoLabel(text = label.text, confidence = label.confidence)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
    }

    // --- Object Detection ---
    private suspend fun detectObjects(bitmap: Bitmap): List<com.example.photosearch.data.DetectedObject> {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    val result = objects.mapNotNull { obj ->
                        val label = obj.labels.firstOrNull()
                        if (label != null) {
                            com.example.photosearch.data.DetectedObject(
                                label = label.text,
                                confidence = label.confidence,
                                boundingBox = obj.boundingBox.let {
                                    BoundingBox(it.left, it.top, it.width(), it.height())
                                }
                            )
                        } else null
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
    }

    // --- Face Detection ---
    private suspend fun detectFaces(bitmap: Bitmap): List<FaceInfo> {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    val result = faces.map { face ->
                        FaceInfo(
                            smilingProbability = face.smilingProbability ?: 0f,
                            leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                            rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                            facing = FaceFacing.STRAIGHT
                        )
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
    }

    // --- Color Analysis ---
    /** v3.11.0：主色 + 平均亮度 + 平均饱和度，一次采样全部算出，供本地检索增强使用。 */
    private data class VisualStats(
        val colors: List<Int>,
        val brightness: Float,
        val saturation: Float
    )

    private fun analyzeColors(bitmap: Bitmap): VisualStats {
        try {
            val colorCounts = mutableMapOf<Int, Int>()
            val step = maxOf(1, (bitmap.width * bitmap.height) / 500) // Sample ~500 pixels
            var sumBrightness = 0f
            var sumSaturation = 0f
            var samples = 0

            for (i in 0 until bitmap.width * bitmap.height step step) {
                val x = i % bitmap.width
                val y = i / bitmap.width
                if (y >= bitmap.height) break
                val pixel = bitmap.getPixel(x, y)
                val rawR = AndroidColor.red(pixel)
                val rawG = AndroidColor.green(pixel)
                val rawB = AndroidColor.blue(pixel)
                // Quantize color to reduce palette
                val r = (rawR / 32) * 32
                val g = (rawG / 32) * 32
                val b = (rawB / 32) * 32
                val quantized = AndroidColor.rgb(r, g, b)
                colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1

                sumBrightness += (0.299f * rawR + 0.587f * rawG + 0.114f * rawB) / 255f
                val mx = maxOf(rawR, rawG, rawB).coerceAtLeast(1)
                val mn = minOf(rawR, rawG, rawB)
                sumSaturation += (mx - mn).toFloat() / mx
                samples++
            }

            val top = colorCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }
            if (samples == 0) return VisualStats(top, -1f, -1f)
            return VisualStats(top, sumBrightness / samples, sumSaturation / samples)
        } catch (e: Exception) {
            return VisualStats(emptyList(), -1f, -1f)
        }
    }

    // --- Scene Classification ---
    /**
     * v3.11.0：返回**所有**场景的得分，而不是只留得分最高的一个。
     * 一张「夜晚 + 城市 + 车」的照片在旧版只会留下一个标签，另外两个概念
     * 全部丢失 —— 这是不用大模型时召回不足的重要原因之一。
     */
    private fun scoreScenes(
        labels: List<PhotoLabel>,
        objects: List<com.example.photosearch.data.DetectedObject>
    ): Map<SceneType, Float> {
        val labelTexts = labels.map { it.text.lowercase() }.toSet()
        val objectTexts = objects.map { it.label.lowercase() }.toSet()
        val allTexts = labelTexts + objectTexts

        // Score each scene type
        val scores = mutableMapOf<SceneType, Float>()

        fun addScore(scene: SceneType, score: Float) {
            scores[scene] = (scores[scene] ?: 0f) + score
        }

        // Beach/Sea detection
        if (allTexts.any { it in listOf("beach", "sand", "sea", "ocean", "water", "wave", "coast", "shore", "palm") }) {
            addScore(SceneType.BEACH_SEA, 3f)
        }

        // Mountain/Hiking detection
        if (allTexts.any { it in listOf("mountain", "hill", "peak", "cliff", "ridge", "valley", "rock", "hiking", "trail") }) {
            addScore(SceneType.MOUNTAIN_HILL, 3f)
        }

        // Vehicle detection
        if (allTexts.any { it in listOf("car", "vehicle", "truck", "bus", "motorcycle", "bicycle", "wheel", "tire", "automobile") }) {
            addScore(SceneType.VEHICLE_SCENE, 3f)
        }
        if (objects.any { it.label.lowercase() in listOf("car", "vehicle", "truck", "bus", "wheel") }) {
            addScore(SceneType.VEHICLE_SCENE, 2f)
        }

        // Food detection
        if (allTexts.any { it in listOf("food", "meal", "dish", "fruit", "vegetable", "dessert", "drink", "cooking", "restaurant", "cuisine") }) {
            addScore(SceneType.FOOD_DRINK, 3f)
        }

        // Animal/Pet detection
        if (allTexts.any { it in listOf("dog", "cat", "pet", "animal", "bird", "fish", "puppy", "kitten") }) {
            addScore(SceneType.ANIMAL_PET, 3f)
        }
        if (objects.any { it.label.lowercase() in listOf("dog", "cat", "animal") }) {
            addScore(SceneType.ANIMAL_PET, 2f)
        }

        // Sport/Exercise detection
        if (allTexts.any { it in listOf("sport", "exercise", "running", "fitness", "gym", "football", "basketball", "swimming", "yoga") }) {
            addScore(SceneType.SPORT_EXERCISE, 3f)
        }

        // Night scene detection
        if (allTexts.any { it in listOf("night", "dark", "evening", "neon", "light", "lamp") }) {
            addScore(SceneType.NIGHT_SCENE, 2f)
        }

        // Nature/Outdoor detection
        if (allTexts.any { it in listOf("tree", "flower", "plant", "forest", "garden", "leaf", "grass", "nature", "landscape", "sky", "sunset", "sunrise") }) {
            addScore(SceneType.OUTDOOR_NATURE, 2f)
        }

        // Urban detection
        if (allTexts.any { it in listOf("building", "house", "architecture", "tower", "bridge", "city", "street", "road") }) {
            addScore(SceneType.OUTDOOR_URBAN, 2f)
        }

        // People/Portrait detection
        if (allTexts.any { it in listOf("person", "people", "man", "woman", "child", "portrait", "face", "human") }) {
            addScore(SceneType.PEOPLE_PORTRAIT, 2f)
        }

        // Landscape detection (wide outdoor views)
        if (allTexts.any { it in listOf("landscape", "scenery", "view", "panorama", "horizon") }) {
            addScore(SceneType.LANDSCAPE, 2f)
        }

        return scores
    }

    /** 兼容旧调用名：取得分最高的场景。 */
    private fun classifyScene(
        labels: List<PhotoLabel>,
        objects: List<com.example.photosearch.data.DetectedObject>
    ): SceneType = scoreScenes(labels, objects).maxByOrNull { it.value }?.key ?: SceneType.UNKNOWN

    /** 把所有达标场景翻成中文可检索标签（多场景，不再是单一 sceneType）。 */
    private fun multiSceneTags(scores: Map<SceneType, Float>): List<String> {
        val tags = LinkedHashSet<String>()
        scores.filterValues { it >= 2f }.keys.forEach { scene ->
            when (scene) {
                SceneType.BEACH_SEA -> tags.addAll(listOf("海滩", "海边", "沙滩", "海景"))
                SceneType.MOUNTAIN_HILL -> tags.addAll(listOf("山景", "登山", "爬山"))
                SceneType.VEHICLE_SCENE -> tags.addAll(listOf("车辆", "汽车", "交通工具"))
                SceneType.FOOD_DRINK -> tags.addAll(listOf("美食", "食物", "餐饮"))
                SceneType.ANIMAL_PET -> tags.addAll(listOf("动物", "宠物"))
                SceneType.SPORT_EXERCISE -> tags.addAll(listOf("运动", "健身"))
                SceneType.NIGHT_SCENE -> tags.addAll(listOf("夜景", "夜晚"))
                SceneType.OUTDOOR_NATURE -> tags.addAll(listOf("户外", "自然", "风景"))
                SceneType.OUTDOOR_URBAN -> tags.addAll(listOf("城市", "街道", "建筑"))
                SceneType.PEOPLE_PORTRAIT -> tags.addAll(listOf("人物", "人像"))
                SceneType.LANDSCAPE -> tags.addAll(listOf("风景", "景色"))
                SceneType.INDOOR_HOME, SceneType.INDOOR_PUBLIC, SceneType.INDOOR_EVENT ->
                    tags.addAll(listOf("室内", "室内照"))
                else -> Unit
            }
        }
        return tags.toList()
    }

    private fun inferOutdoor(labels: List<PhotoLabel>, scene: SceneType): Boolean? {
        return when (scene) {
            SceneType.OUTDOOR_NATURE, SceneType.OUTDOOR_URBAN, SceneType.BEACH_SEA,
            SceneType.MOUNTAIN_HILL, SceneType.LANDSCAPE -> true
            SceneType.INDOOR_HOME, SceneType.INDOOR_PUBLIC, SceneType.INDOOR_EVENT -> false
            else -> {
                val outdoorLabels = setOf("sky", "outdoor", "landscape", "nature", "tree", "grass", "building", "road", "street")
                val indoorLabels = setOf("indoor", "room", "ceiling", "floor", "wall", "furniture")
                val labelTexts = labels.map { it.text.lowercase() }.toSet()
                when {
                    labelTexts.any { it in outdoorLabels } -> true
                    labelTexts.any { it in indoorLabels } -> false
                    else -> null
                }
            }
        }
    }

    fun close() {
        labeler.close()
        objectDetector.close()
        faceDetector.close()
    }
}
