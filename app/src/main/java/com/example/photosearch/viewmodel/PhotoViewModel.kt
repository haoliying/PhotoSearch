package com.example.photosearch.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.search.*
import com.example.photosearch.service.AnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.8.0 性能重构要点（面向几万张照片）：
 *  1. 搜索输入只做 O(1) 状态赋值，实际检索 280ms 防抖后放 Dispatchers.Default；
 *     新输入取消旧任务，并用代次号丢弃过期结果 —— 输入不再被检索阻塞；
 *  2. 每张照片的概念证据与全文索引跨查询复用（ConceptRegistry.SearchDocCache），
 *     不再每次查询重建全部索引；
 *  3. EXIF 增量行式落盘 + 同一 Map 引用 + exifVersion 触发刷新，
 *     不再每 25 张全量 toMap / 全量 JSON 重写；
 *  4. 分析服务进度节流，分析期间只更新进度条，完成时才做一次全量合并与检索。
 */
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
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val batchReanalyzing: Boolean = false,
    val batchProgress: Int = 0,
    val batchTotal: Int = 0,
    val timeRange: TimeRange? = null,
    val locationQuery: LocationQuery? = null,
    val availablePlaces: List<PlaceSuggestion> = emptyList(),
    val exifMap: Map<Long, PhotoExif> = emptyMap(),
    val isExtractingExif: Boolean = false,
    val exifProgress: Int = 0,
    val exifTotal: Int = 0,
    val filterSummary: String? = null,
    val noTimeCount: Int = 0,
    val noLocationCount: Int = 0,
    // v3.8.0
    val isSearching: Boolean = false,
    /** exifMap 是同一个 ConcurrentHashMap 引用，靠版本号触发 UI 刷新，避免全量复制。 */
    val exifVersion: Int = 0
)

/** computeFilters 的产物，主线程一次性写入 StateFlow。 */
private data class FilterOutcome(
    val filtered: List<PhotoItem>,
    val count: Int,
    val description: String?,
    val summary: String?,
    val noTime: Int,
    val noLoc: Int
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 280L
        private const val EXIF_BATCH = 200
        private const val SYNC_PROGRESS_STEP = 100
    }

    private val repository = PhotoRepository(application)
    private val resultStore = AnalysisResultStore(application)
    private val exifStore = PhotoExifStore(application)

    // 并发安全：检索在 Default 线程读，Service / EXIF 提取在别的线程写
    private val metadataCache = ConcurrentHashMap<Long, PhotoMetadata>()
    private var exifCache = ConcurrentHashMap<Long, PhotoExif>()
    private val docCache = ConceptRegistry.SearchDocCache()

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    private var filterJob: Job? = null
    @Volatile
    private var filterGeneration = 0L
    private var lastSyncedProgress = -1

    init {
        loadPhotos()
        observeServiceState()
    }

    /**
     * 重新扫描相册。用于「照片权限授予后」「从系统设置返回」「用户手动刷新」。
     * 修复 v3.5.0 缺陷：照片只在 ViewModel init 时加载一次，而权限对话框在其后才弹出。
     */
    fun reloadPhotos() { loadPhotos() }

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
                val saved = withContext(Dispatchers.IO) { resultStore.load() }

                metadataCache.clear()
                saved?.metadataMap?.let { metadataCache.putAll(it) }
                metadataCache.putAll(AnalysisService.analyzedMetadata)

                val merged = withContext(Dispatchers.Default) {
                    photos.map { photo ->
                        val metadata = metadataCache[photo.id]
                        if (metadata != null && metadata.isAnalyzed) {
                            photo.copy(labels = metadata.labels, metadata = metadata, isAnalyzed = true)
                        } else photo
                    }
                }

                val hasAnalyzed = AnalysisService.analysisState.value.isComplete ||
                        saved?.isComplete == true ||
                        metadataCache.isNotEmpty()
                val canResume = saved != null && !saved.isComplete &&
                        saved.analyzedCount > 0 && saved.totalPhotos == photos.size

                docCache.clear()
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
                scheduleFilters(0)
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
     * v3.8.0：增量追加落盘，进度每 200 张刷新一次，中间不复制 exifMap、
     * 不重算地点候选，最后统一失效检索缓存并刷新一次界面。
     */
    private fun extractExif(photos: List<PhotoItem>) {
        if (_state.value.isExtractingExif) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { exifStore.load() }
            val need = photos.filter { p ->
                val e = cached[p.id]
                e == null || !e.hasExtendedInfo
            }
            exifCache = ConcurrentHashMap(cached)
            _state.value = _state.value.copy(
                exifMap = exifCache,
                exifVersion = _state.value.exifVersion + 1,
                isExtractingExif = need.isNotEmpty(),
                exifProgress = 0,
                exifTotal = need.size
            )
            recomputePlaces()

            if (need.isNotEmpty()) {
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    val buffer = ArrayList<PhotoExif>(EXIF_BATCH)
                    need.forEachIndexed { i, p ->
                        ensureActive()
                        val fresh = try {
                            ExifReader.read(app, p)
                        } catch (_: Exception) {
                            PhotoExif(
                                photoId = p.id,
                                dateTaken = effectiveTimeOf(p, null),
                                dateSource = "mediastore"
                            )
                        }
                        val old = cached[p.id]
                        val value = if (old != null && old.manualLabel.isNotBlank()) {
                            ExifReader.withManualLabel(fresh, p.id, old.manualLabel)
                        } else fresh
                        exifCache[p.id] = value
                        buffer.add(value)

                        if (buffer.size >= EXIF_BATCH) {
                            exifStore.append(buffer)
                            buffer.clear()
                            val done = i + 1
                            withContext(Dispatchers.Main.immediate) {
                                _state.value = _state.value.copy(exifProgress = done)
                            }
                        }
                    }
                    if (buffer.isNotEmpty()) exifStore.append(buffer)
                }
            }

            _state.value = _state.value.copy(
                isExtractingExif = false,
                exifProgress = need.size,
                exifMap = exifCache,
                exifVersion = _state.value.exifVersion + 1
            )
            docCache.clear()
            recomputePlaces()
            scheduleFilters(0)
        }
    }

    /** 时间区间变化时，地点候选按「该时间内的照片」重新统计 —— 时空联动。 */
    private fun recomputePlaces() {
        val st = _state.value
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { computePlaces(st) }
            _state.value = _state.value.copy(availablePlaces = list)
        }
    }

    private fun computePlaces(st: PhotoSearchState): List<PlaceSuggestion> {
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
        return counts.entries
            .sortedByDescending { it.value }
            .take(24)
            .map { (label, count) ->
                val ll = coords[label]
                PlaceSuggestion(label, count, ll?.first, ll?.second, label)
            }
    }

    // ------------------------------------------------------------------
    // 检索调度：防抖 + 后台 + 可取消 + 代次校验
    // ------------------------------------------------------------------

    /**
     * 所有筛选入口统一走这里。主线程只做 O(1) 的状态赋值与结果写入，
     * 全库检索在 Dispatchers.Default 上执行，输入变化会立刻取消旧任务。
     */
    private fun scheduleFilters(delayMs: Long = 0L) {
        filterJob?.cancel()
        val gen = ++filterGeneration
        filterJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (gen != filterGeneration) return@launch
            val snapshot = _state.value
            _state.value = _state.value.copy(isSearching = true)
            val outcome = withContext(Dispatchers.Default) {
                val job = currentCoroutineContext()[Job]
                computeFilters(snapshot, gen) { job?.isActive != false }
            }
            if (gen != filterGeneration) return@launch          // 已有更新的查询
            if (outcome == null) return@launch                    // 本次被取消
            _state.value = _state.value.copy(
                filteredPhotos = outcome.filtered,
                searchResultCount = outcome.count,
                searchDescription = outcome.description,
                filterSummary = outcome.summary,
                noTimeCount = outcome.noTime,
                noLocationCount = outcome.noLoc,
                isSearching = false
            )
        }
    }

    /** 纯计算：可在后台线程调用；返回 null 表示被取消。 */
    private fun computeFilters(
        st: PhotoSearchState,
        gen: Long,
        isActive: () -> Boolean
    ): FilterOutcome? {
        val q = st.searchQuery
        val parsed = if (q.isBlank()) null
        else IntentParser.parse(q, st.availablePlaces.map { it.label }.toSet(), System.currentTimeMillis())

        val time = st.timeRange ?: parsed?.timeRange
        val loc = st.locationQuery ?: parsed?.locationQuery

        if (q.isBlank() && time == null && loc == null) {
            return FilterOutcome(st.allPhotos, 0, null, null, 0, 0)
        }

        val intent = if (parsed != null) {
            parsed.copy(timeRange = time, locationQuery = loc)
        } else {
            SearchIntent(q, emptyList(), timeRange = time, locationQuery = loc)
        }

        val results = try {
            SearchEngine.search(
                intent = intent,
                photos = st.allPhotos,
                metadataMap = metadataCache,
                exifMap = st.exifMap,
                cache = docCache,
                isActive = { gen == filterGeneration && isActive() }
            )
        } catch (_: SearchEngine.SearchCancelledException) {
            return null
        }

        val pieces = mutableListOf<String>()
        time?.let { pieces += "时间 ${TimeQueryParser.displayLabel(it)}" }
        loc?.let { pieces += "地点 ${it.raw}" }
        val summary = pieces.joinToString(" · ").takeIf { it.isNotBlank() }

        val noTime = if (time != null) {
            st.allPhotos.count { effectiveTimeOf(it, st.exifMap[it.id]) <= 0L }
        } else 0
        val noLoc = if (loc != null) {
            st.allPhotos.count { st.exifMap[it.id]?.hasPlace != true }
        } else 0

        return FilterOutcome(
            filtered = results.map { it.photo },
            count = results.size,
            description = if (q.isBlank()) null else IntentParser.getDescription(q),
            summary = summary,
            noTime = noTime,
            noLoc = noLoc
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
    // 对 UI 暴露的筛选 API
    // ------------------------------------------------------------------

    fun setTimeRange(range: TimeRange?) {
        _state.value = _state.value.copy(timeRange = range)
        recomputePlaces()
        scheduleFilters(0)
    }

    fun setLocationQuery(query: LocationQuery?) {
        _state.value = _state.value.copy(locationQuery = query)
        scheduleFilters(0)
    }

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
        scheduleFilters(0)
    }

    /** 手动标注地点：让没有 GPS 的照片也能参与地点检索。 */
    fun setPlaceLabel(photoId: Long, label: String) {
        viewModelScope.launch {
            val updated = ExifReader.withManualLabel(exifCache[photoId], photoId, label)
            exifCache[photoId] = updated
            withContext(Dispatchers.IO) { exifStore.update(photoId, updated) }
            _state.value = _state.value.copy(
                exifMap = exifCache,
                exifVersion = _state.value.exifVersion + 1
            )
            docCache.invalidate(listOf(photoId))
            recomputePlaces()
            scheduleFilters(0)
        }
    }

    fun exifFor(photoId: Long): PhotoExif? = exifCache[photoId]

    // ------------------------------------------------------------------
    // 分析服务状态
    // ------------------------------------------------------------------

    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { serviceProgress ->
                _state.value = _state.value.copy(
                    isAnalyzing = serviceProgress.isRunning,
                    analysisProgress = serviceProgress.progress,
                    analysisTotal = serviceProgress.total,
                    hasAnalyzed = serviceProgress.isComplete ||
                            _state.value.hasAnalyzed ||
                            AnalysisService.analyzedMetadata.isNotEmpty(),
                    canResume = if (serviceProgress.isRunning || serviceProgress.isComplete) false
                    else _state.value.canResume
                )

                // v3.8.0：分析期间绝不跑全量 map / 全库检索，只更新进度条；
                // 完成（或服务停止且有结果）时做一次全量合并。
                if (serviceProgress.isComplete ||
                    (!serviceProgress.isRunning && serviceProgress.progress > 0 &&
                            serviceProgress.progress - lastSyncedProgress >= SYNC_PROGRESS_STEP)
                ) {
                    lastSyncedProgress = serviceProgress.progress
                    syncServiceResults()
                }
            }
        }
    }

    private fun syncServiceResults() {
        viewModelScope.launch {
            val snapshot = _state.value.allPhotos
            val merged = withContext(Dispatchers.Default) {
                metadataCache.putAll(AnalysisService.analyzedMetadata)
                snapshot.map { photo ->
                    val metadata = metadataCache[photo.id]
                    if (metadata != null && metadata.isAnalyzed) {
                        photo.copy(labels = metadata.labels, metadata = metadata, isAnalyzed = true)
                    } else photo
                }
            }
            _state.value = _state.value.copy(
                allPhotos = merged,
                canResume = false,
                savedProgress = 0,
                savedTotal = 0
            )
            docCache.clear()
            scheduleFilters(0)
        }
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
            docCache.clear()
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
            scheduleFilters(0)
            startAnalysis()
        }
    }

    /** Re-analyze a single photo (on-device + VLM if enabled). */
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
                            _state.value = _state.value.copy(reanalyzeError = "VLM 分析失败: ${vlmResult.error}")
                        }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(reanalyzeError = "VLM 分析异常: ${e.message}")
                    }
                }

                val metadata = onDeviceMetadata.copy(
                    photoId = photo.id,
                    description = description,
                    vlmKeywords = vlmKeywords
                )
                metadataCache[photo.id] = metadata
                AnalysisService.analyzedMetadata[photo.id] = metadata
                docCache.invalidate(listOf(photo.id))

                withContext(Dispatchers.IO) {
                    resultStore.saveSegment(
                        totalPhotos = _state.value.allPhotos.size,
                        analyzedCount = metadataCache.size,
                        isComplete = _state.value.allPhotos.all { metadataCache.containsKey(it.id) },
                        metadataMap = metadataCache
                    )
                }

                val updatedPhoto = photo.copy(
                    labels = metadata.labels,
                    metadata = metadata,
                    isAnalyzed = true
                )
                val merged = withContext(Dispatchers.Default) {
                    _state.value.allPhotos.map { if (it.id == photo.id) updatedPhoto else it }
                }
                _state.value = _state.value.copy(
                    selectedPhoto = updatedPhoto,
                    allPhotos = merged,
                    hasAnalyzed = true,
                    reanalyzingPhoto = false
                )
                scheduleFilters(0)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    reanalyzingPhoto = false,
                    reanalyzeError = "重新分析失败: ${e.message}"
                )
            }
        }
    }

    /**
     * v3.8.0：输入只做 O(1) 赋值，检索交给 scheduleFilters 防抖 + 后台执行。
     * 这是「搜索框无法输入 / 输入后假死」的根本修复。
     */
    fun search(query: String) {
        if (_state.value.searchQuery == query) return
        _state.value = _state.value.copy(searchQuery = query)
        scheduleFilters(if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS)
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
            shareFile.outputStream().use { output -> inputStream.copyTo(output) }
            inputStream.close()
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", shareFile
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
        _state.value = _state.value.copy(
            selectionMode = true,
            selectedIds = _state.value.filteredPhotos.map { it.id }.toSet()
        )
    }

    fun reanalyzeSelectedPhotos() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchReanalyzing) return

        exitSelectionMode()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                batchReanalyzing = true, batchProgress = 0, batchTotal = ids.size
            )
            val context = getApplication<Application>()
            val useVlm = AppConfig.isVlmEnabled(context) && AppConfig.hasApiKey(context)
            val analyzer = EnhancedImageAnalyzer(context)
            val vlmAnalyzer = if (useVlm) VisionLanguageAnalyzer(context) else null
            // v3.8.0：批量过程中不再每张复制整个照片列表，结束时合并一次
            val updatedById = HashMap<Long, PhotoItem>(ids.size)

            try {
                for ((index, photoId) in ids.withIndex()) {
                    ensureActive()
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
                            photoId = photo.id, description = description, vlmKeywords = vlmKeywords
                        )
                        metadataCache[photo.id] = metadata
                        AnalysisService.analyzedMetadata[photo.id] = metadata
                        updatedById[photo.id] = photo.copy(
                            labels = metadata.labels, metadata = metadata, isAnalyzed = true
                        )
                    } catch (_: Exception) { }

                    // 进度每 10 张刷新一次，避免高频重组
                    if ((index + 1) % 10 == 0 || index == ids.lastIndex) {
                        _state.value = _state.value.copy(batchProgress = index + 1)
                    }
                }

                withContext(Dispatchers.IO) {
                    resultStore.saveSegment(
                        totalPhotos = _state.value.allPhotos.size,
                        analyzedCount = metadataCache.size,
                        isComplete = _state.value.allPhotos.all { metadataCache.containsKey(it.id) },
                        metadataMap = metadataCache
                    )
                }
                if (updatedById.isNotEmpty()) {
                    docCache.invalidate(updatedById.keys)
                    val merged = withContext(Dispatchers.Default) {
                        _state.value.allPhotos.map { updatedById[it.id] ?: it }
                    }
                    _state.value = _state.value.copy(allPhotos = merged, hasAnalyzed = true)
                    scheduleFilters(0)
                }
            } finally {
                analyzer.close()
                _state.value = _state.value.copy(
                    batchReanalyzing = false, batchProgress = 0, batchTotal = 0
                )
            }
        }
    }
}
