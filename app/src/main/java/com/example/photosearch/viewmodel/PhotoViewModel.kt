package com.example.photosearch.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import com.example.photosearch.ml.SearchConditionMapper
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.search.*
import com.example.photosearch.service.AnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoSearchState(
    val allPhotos: List<PhotoItem> = emptyList(),
    val filteredPhotos: List<PhotoItem> = emptyList(),
    val searchQuery: String = "",
    val isAnalyzing: Boolean = false,
    val analysisProgress: Int = 0,
    val analysisTotal: Int = 0,
    val hasAnalyzed: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedPhoto: PhotoItem? = null,
    val canResume: Boolean = false,
    val savedProgress: Int = 0,
    val savedTotal: Int = 0,
    val searchResultCount: Int = 0,
    val searchDescription: String? = null,
    val reanalyzingPhoto: Boolean = false,
    val reanalyzeError: String? = null,
    // Multi-select for batch re-analyze
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val batchReanalyzing: Boolean = false,
    val batchProgress: Int = 0,
    val batchTotal: Int = 0,
    // v3.6.0 时间 / 地点筛选
    val timeRange: TimeRange? = null,
    val locationQuery: LocationQuery? = null,
    val availablePlaces: List<PlaceSuggestion> = emptyList(),
    val exifMap: Map<Long, PhotoExif> = emptyMap(),
    val isExtractingExif: Boolean = false,
    val exifProgress: Int = 0,
    val exifTotal: Int = 0,
    val filterSummary: String? = null,
    val noTimeCount: Int = 0,
    val noLocationCount: Int = 0
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PhotoRepository(application)
    private val resultStore = AnalysisResultStore(application)
    private val exifStore = PhotoExifStore(application)

    // In-memory metadata cache
    private val metadataCache = mutableMapOf<Long, PhotoMetadata>()
    private var exifCache = mutableMapOf<Long, PhotoExif>()

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    init {
        loadPhotos()
        observeServiceState()
    }

    /**
     * 重新扫描相册。用于「照片权限授予后」「从系统设置返回」「用户手动刷新」。
     * 修复 v3.5.0 缺陷：照片只在 ViewModel init 时加载一次，而权限对话框在其后才弹出。
     */
    fun reloadPhotos() {
        loadPhotos()
    }

    /** onResume 兜底：仅当列表为空且当前未在加载时重扫。 */
    fun reloadIfEmpty() {
        if (_state.value.allPhotos.isEmpty() && !_state.value.isLoading) loadPhotos()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            if (_state.value.isLoading) return@launch
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val photos = repository.loadAllPhotos()

                // Load saved metadata from disk
                val saved = withContext(Dispatchers.IO) { resultStore.load() }

                metadataCache.clear()
                saved?.metadataMap?.let { metadataCache.putAll(it) }
                metadataCache.putAll(AnalysisService.analyzedMetadata)

                val merged = photos.map { photo ->
                    val metadata = metadataCache[photo.id]
                    if (metadata != null && metadata.isAnalyzed) {
                        photo.copy(labels = metadata.labels, metadata = metadata, isAnalyzed = true)
                    } else photo
                }

                val hasAnalyzed = AnalysisService.analysisState.value.isComplete ||
                        saved?.isComplete == true ||
                        metadataCache.isNotEmpty()

                val canResume = saved != null && !saved.isComplete &&
                        saved.analyzedCount > 0 && saved.totalPhotos == photos.size

                // 先把相册显示出来，EXIF 在后台异步提取，避免首屏白屏
                _state.value = _state.value.copy(
                    allPhotos = merged,
                    filteredPhotos = merged,
                    isLoading = false,
                    hasAnalyzed = hasAnalyzed,
                    canResume = canResume,
                    savedProgress = saved?.analyzedCount ?: 0,
                    savedTotal = saved?.totalPhotos ?: 0
                )
                applyFilters()
                extractExif(photos)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "加载照片失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 批量提取 EXIF（缓存优先，只处理新增照片）。
     * 相机时间/GPS 是确定性数据，不依赖 AI 分析，升级后无需重新分析即可用。
     */
    private fun extractExif(photos: List<PhotoItem>) {
        if (_state.value.isExtractingExif) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { exifStore.load() }
            // v3.7.0：旧缓存（schema 1）缺少拍摄参数，重新提取一次以补齐中文标签；
            // 重新提取时保留用户手动标注的地点，避免升级后标注丢失。
            val need = photos.filter { p ->
                val e = cached[p.id]
                e == null || !e.hasExtendedInfo
            }
            exifCache = cached
            _state.value = _state.value.copy(
                exifMap = exifCache.toMap(),
                isExtractingExif = need.isNotEmpty(),
                exifProgress = 0,
                exifTotal = need.size
            )
            recomputePlaces()

            if (need.isNotEmpty()) {
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    need.forEachIndexed { i, p ->
                        try {
                            val fresh = ExifReader.read(app, p)
                            val old = cached[p.id]
                            exifCache[p.id] = if (old != null && old.manualLabel.isNotBlank()) {
                                ExifReader.withManualLabel(fresh, p.id, old.manualLabel)
                            } else fresh
                        } catch (_: Exception) {
                            exifCache[p.id] = PhotoExif(
                                photoId = p.id,
                                dateTaken = effectiveTimeOf(p, null),
                                dateSource = "mediastore"
                            )
                        }
                        // 分批落盘 + 刷新进度，中断也不丢已提取部分
                        if ((i + 1) % 25 == 0 || i == need.lastIndex) {
                            exifStore.save(exifCache.toMap())
                            _state.value = _state.value.copy(
                                exifMap = exifCache.toMap(), exifProgress = i + 1
                            )
                            recomputePlaces()
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                isExtractingExif = false,
                exifProgress = need.size,
                exifMap = exifCache.toMap()
            )
            recomputePlaces()
            applyFilters()
        }
    }

    /** 时间区间变化时，地点候选按「该时间内的照片」重新统计 —— 时空联动。 */
    private fun recomputePlaces() {
        val st = _state.value
        val time = st.timeRange
        val counts = linkedMapOf<String, Int>()
        val coords = hashMapOf<String, Pair<Double?, Double?>>()
        for (p in st.allPhotos) {
            if (time != null && !time.contains(effectiveTimeOf(p, st.exifMap[p.id]))) continue
            val e = st.exifMap[p.id] ?: continue
            val label = e.shortPlaceLabel()
            if (label.isBlank()) continue
            counts[label] = (counts[label] ?: 0) + 1
            if (!coords.containsKey(label)) coords[label] = e.latitude to e.longitude
        }
        val list = counts.entries
            .sortedByDescending { it.value }
            .take(24)
            .map { (label, count) ->
                val ll = coords[label]
                PlaceSuggestion(label, count, ll?.first, ll?.second, label)
            }
        _state.value = _state.value.copy(availablePlaces = list)
    }

    /**
     * 统一的筛选入口：搜索词 + 时间区间 + 地点三者按 AND 联动。
     * 搜索词里也支持自然语言时间/地点（「2023年在北京拍的汽车」），
     * 筛选面板里的设定优先覆盖查询里解析出的同类型条件。
     */
    private fun applyFilters() {
        val st = _state.value
        val q = st.searchQuery
        val parsed = if (q.isBlank()) null
        else IntentParser.parse(q, st.availablePlaces.map { it.label }.toSet(), System.currentTimeMillis())

        val time = st.timeRange ?: parsed?.timeRange
        val loc = st.locationQuery ?: parsed?.locationQuery

        if (q.isBlank() && time == null && loc == null) {
            _state.value = st.copy(
                filteredPhotos = st.allPhotos,
                searchResultCount = 0,
                searchDescription = null,
                filterSummary = null,
                noTimeCount = 0,
                noLocationCount = 0
            )
            return
        }

        val intent = if (parsed != null) {
            parsed.copy(timeRange = time, locationQuery = loc)
        } else {
            SearchIntent(q, emptyList(), timeRange = time, locationQuery = loc)
        }

        val results = SearchEngine.search(intent, st.allPhotos, metadataCache, st.exifMap)
        val pieces = mutableListOf<String>()
        time?.let { pieces += "时间 ${TimeQueryParser.displayLabel(it)}" }
        loc?.let { pieces += "地点 ${it.raw}" }
        val summary = pieces.joinToString(" · ").takeIf { it.isNotBlank() }

        val noTime = if (time != null) st.allPhotos.count { effectiveTimeOf(it, st.exifMap[it.id]) <= 0L } else 0
        val noLoc = if (loc != null) st.allPhotos.count { st.exifMap[it.id]?.hasPlace != true } else 0

        _state.value = st.copy(
            filteredPhotos = results.map { it.photo },
            searchResultCount = results.size,
            searchDescription = if (q.isBlank()) null else IntentParser.getDescription(q),
            filterSummary = summary,
            noTimeCount = noTime,
            noLocationCount = noLoc
        )
    }

    private fun effectiveTimeOf(photo: PhotoItem, exif: PhotoExif?): Long {
        if (exif != null && exif.hasTime) return exif.dateTaken
        val t = photo.dateTaken
        return when {
            t <= 0L -> 0L
            t < 100_000_000_000L -> t * 1000L
            else -> t
        }
    }

    // ------------------------------------------------------------------
    // v3.6.0 对 UI 暴露的筛选 API
    // ------------------------------------------------------------------

    fun setTimeRange(range: TimeRange?) {
        _state.value = _state.value.copy(timeRange = range)
        recomputePlaces()
        applyFilters()
    }

    fun setLocationQuery(query: LocationQuery?) {
        _state.value = _state.value.copy(locationQuery = query)
        applyFilters()
    }

    /** 从候选地点里「选定」一个：带中心点，启用半径匹配。 */
    fun selectPlace(suggestion: PlaceSuggestion) {
        val place = PlaceCatalog.findByQuery(suggestion.label)
        val q = if (place != null) {
            PlaceCatalog.queryFor(place, suggestion.label)
        } else {
            LocationQuery(
                raw = suggestion.label,
                normalized = PlaceCatalog.normalize(suggestion.label),
                centerLat = suggestion.lat,
                centerLon = suggestion.lon,
                radiusKm = 60.0
            )
        }
        setLocationQuery(q)
    }

    fun clearFilters() {
        _state.value = _state.value.copy(timeRange = null, locationQuery = null)
        recomputePlaces()
        applyFilters()
    }

    /** 手动标注地点：让没有 GPS 的照片也能参与地点检索。 */
    fun setPlaceLabel(photoId: Long, label: String) {
        viewModelScope.launch {
            val updated = ExifReader.withManualLabel(exifCache[photoId], photoId, label)
            exifCache[photoId] = updated
            withContext(Dispatchers.IO) { exifStore.update(photoId, updated) }
            _state.value = _state.value.copy(exifMap = exifCache.toMap())
            recomputePlaces()
            applyFilters()
        }
    }

    fun exifFor(photoId: Long): PhotoExif? = exifCache[photoId]

    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { serviceProgress ->
                if (AnalysisService.analyzedMetadata.isNotEmpty()) {
                    metadataCache.putAll(AnalysisService.analyzedMetadata)
                }

                _state.value = _state.value.copy(
                    isAnalyzing = serviceProgress.isRunning,
                    analysisProgress = serviceProgress.progress,
                    analysisTotal = serviceProgress.total,
                    hasAnalyzed = serviceProgress.isComplete || _state.value.hasAnalyzed || metadataCache.isNotEmpty(),
                    canResume = if (serviceProgress.isRunning || serviceProgress.isComplete) false
                    else _state.value.canResume
                )

                if (serviceProgress.isComplete || serviceProgress.progress > 0) {
                    syncServiceResults()
                }
            }
        }
    }

    private fun syncServiceResults() {
        _state.value = _state.value.copy(
            allPhotos = _state.value.allPhotos.map { photo ->
                val metadata = metadataCache[photo.id]
                if (metadata != null && metadata.isAnalyzed) {
                    photo.copy(labels = metadata.labels, metadata = metadata, isAnalyzed = true)
                } else photo
            },
            canResume = false,
            savedProgress = 0,
            savedTotal = 0
        )
        applyFilters()
    }

    fun startAnalysis() {
        if (_state.value.isAnalyzing || AnalysisService.isRunning()) return

        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AnalysisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        _state.value = _state.value.copy(isAnalyzing = true)
    }

    fun restartAnalysis() {
        if (_state.value.isAnalyzing || AnalysisService.isRunning()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) { resultStore.clear() }
            AnalysisService.reset()
            metadataCache.clear()

            val stripped = _state.value.allPhotos.map {
                it.copy(labels = emptyList(), metadata = null, isAnalyzed = false)
            }
            _state.value = _state.value.copy(
                allPhotos = stripped,
                hasAnalyzed = false,
                canResume = false,
                savedProgress = 0,
                savedTotal = 0
            )
            applyFilters()
            startAnalysis()
        }
    }

    /**
     * Re-analyze a single photo (on-device + VLM if enabled).
     */
    fun reanalyzePhoto(photo: PhotoItem) {
        if (_state.value.reanalyzingPhoto) return

        viewModelScope.launch {
            _state.value = _state.value.copy(reanalyzingPhoto = true, reanalyzeError = null)

            try {
                val context = getApplication<Application>()
                val useVlm = AppConfig.isVlmEnabled(context) && AppConfig.hasApiKey(context)

                val analyzer = EnhancedImageAnalyzer(context)
                val onDeviceMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyzePhoto(photo.uri, context)
                }
                analyzer.close()

                var description = ""
                var vlmKeywords = listOf<String>()
                if (useVlm) {
                    try {
                        val vlmAnalyzer = VisionLanguageAnalyzer(context)
                        val vlmResult = vlmAnalyzer.analyzePhoto(photo.uri)
                        if (vlmResult.success) {
                            description = vlmResult.description
                            vlmKeywords = vlmResult.keywords
                        } else {
                            _state.value = _state.value.copy(
                                reanalyzeError = "VLM 分析失败: ${vlmResult.error}"
                            )
                        }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            reanalyzeError = "VLM 分析异常: ${e.message}"
                        )
                    }
                }

                val metadata = onDeviceMetadata.copy(
                    photoId = photo.id,
                    description = description,
                    vlmKeywords = vlmKeywords
                )

                metadataCache[photo.id] = metadata
                AnalysisService.analyzedMetadata[photo.id] = metadata

                withContext(Dispatchers.IO) {
                    resultStore.saveSegment(
                        totalPhotos = _state.value.allPhotos.size,
                        analyzedCount = metadataCache.size,
                        isComplete = _state.value.allPhotos.all { metadataCache.containsKey(it.id) },
                        metadataMap = metadataCache.toMap()
                    )
                }

                val updatedPhoto = photo.copy(
                    labels = metadata.labels,
                    metadata = metadata,
                    isAnalyzed = true
                )

                _state.value = _state.value.copy(
                    selectedPhoto = updatedPhoto,
                    allPhotos = _state.value.allPhotos.map {
                        if (it.id == photo.id) updatedPhoto else it
                    },
                    filteredPhotos = _state.value.filteredPhotos.map {
                        if (it.id == photo.id) updatedPhoto else it
                    },
                    hasAnalyzed = true,
                    reanalyzingPhoto = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    reanalyzingPhoto = false,
                    reanalyzeError = "重新分析失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Search photos. v3.6.0：内容词与时间/地点筛选统一交给 applyFilters 联动。
     */
    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        applyFilters()
    }

    fun selectPhoto(photo: PhotoItem?) {
        _state.value = _state.value.copy(selectedPhoto = photo)
    }

    fun getShareUri(photo: PhotoItem): Uri? {
        return try {
            val context = getApplication<Application>()
            val inputStream = context.contentResolver.openInputStream(photo.uri) ?: return null
            val shareDir = java.io.File(context.cacheDir, "shared_photos")
            shareDir.mkdirs()
            val shareFile = java.io.File(shareDir, photo.displayName)
            shareFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSuggestions(): List<String> = IntentParser.getSuggestions()

    fun getRuleDescription(): String? = _state.value.searchDescription

    // --- Multi-select for batch re-analyze ---

    fun enterSelectionMode() {
        _state.value = _state.value.copy(selectionMode = true, selectedIds = emptySet())
    }

    fun exitSelectionMode() {
        _state.value = _state.value.copy(selectionMode = false, selectedIds = emptySet())
    }

    fun togglePhotoSelection(photoId: Long) {
        val current = _state.value.selectedIds.toMutableSet()
        if (current.contains(photoId)) {
            current.remove(photoId)
            if (current.isEmpty()) {
                _state.value = _state.value.copy(selectionMode = false, selectedIds = emptySet())
                return
            }
        } else {
            current.add(photoId)
        }
        _state.value = _state.value.copy(selectedIds = current)
    }

    fun selectAllFiltered() {
        val ids = _state.value.filteredPhotos.map { it.id }.toSet()
        _state.value = _state.value.copy(selectionMode = true, selectedIds = ids)
    }

    fun reanalyzeSelectedPhotos() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchReanalyzing) return

        exitSelectionMode()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                batchReanalyzing = true,
                batchProgress = 0,
                batchTotal = ids.size
            )

            val context = getApplication<Application>()
            val useVlm = AppConfig.isVlmEnabled(context) && AppConfig.hasApiKey(context)
            val analyzer = EnhancedImageAnalyzer(context)
            val vlmAnalyzer = if (useVlm) VisionLanguageAnalyzer(context) else null

            try {
                for ((index, photoId) in ids.withIndex()) {
                    val photo = _state.value.allPhotos.find { it.id == photoId } ?: continue

                    try {
                        val onDeviceMetadata = withContext(Dispatchers.IO) {
                            analyzer.analyzePhoto(photo.uri, context)
                        }

                        var description = ""
                        var vlmKeywords = listOf<String>()
                        if (vlmAnalyzer != null) {
                            try {
                                val vlmResult = vlmAnalyzer.analyzePhoto(photo.uri)
                                if (vlmResult.success) {
                                    description = vlmResult.description
                                    vlmKeywords = vlmResult.keywords
                                }
                            } catch (_: Exception) { }
                        }

                        val metadata = onDeviceMetadata.copy(
                            photoId = photo.id,
                            description = description,
                            vlmKeywords = vlmKeywords
                        )

                        metadataCache[photo.id] = metadata
                        AnalysisService.analyzedMetadata[photo.id] = metadata

                        val updatedPhoto = photo.copy(
                            labels = metadata.labels,
                            metadata = metadata,
                            isAnalyzed = true
                        )

                        _state.value = _state.value.copy(
                            allPhotos = _state.value.allPhotos.map {
                                if (it.id == photo.id) updatedPhoto else it
                            },
                            filteredPhotos = _state.value.filteredPhotos.map {
                                if (it.id == photo.id) updatedPhoto else it
                            },
                            batchProgress = index + 1
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _state.value = _state.value.copy(batchProgress = index + 1)
                    }
                }

                withContext(Dispatchers.IO) {
                    resultStore.saveSegment(
                        totalPhotos = _state.value.allPhotos.size,
                        analyzedCount = metadataCache.size,
                        isComplete = _state.value.allPhotos.all { metadataCache.containsKey(it.id) },
                        metadataMap = metadataCache.toMap()
                    )
                }

                _state.value = _state.value.copy(hasAnalyzed = true)
                applyFilters()
            } finally {
                analyzer.close()
                _state.value = _state.value.copy(
                    batchReanalyzing = false,
                    batchProgress = 0,
                    batchTotal = 0
                )
            }
        }
    }
}
