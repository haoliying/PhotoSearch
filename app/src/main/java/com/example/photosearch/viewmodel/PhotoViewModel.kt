package com.example.photosearch.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.search.*
import com.example.photosearch.service.AnalysisEvents
import com.example.photosearch.service.AnalysisPlanner
import com.example.photosearch.service.AnalysisScheduling
import com.example.photosearch.service.AnalysisService
import com.example.photosearch.service.IncrementBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val exifVersion: Int = 0,
    // v3.9.0 断点续分析：按 photoId 统计，不再依赖「照片总数相等」或列表下标
    val doneCount: Int = 0,
    val pendingCount: Int = 0,
    val autoResumeEnabled: Boolean = true,
    // v3.10.0 后台常驻守护 + 相册变化自动分析
    val backgroundWatchEnabled: Boolean = true,
    val backgroundWatchActive: Boolean = false,
    /** 本次守护期间自动分析完成的张数。 */
    val autoAnalyzedCount: Int = 0,
    /** 相册变化后正在增量刷新列表，避免重复触发。 */
    val isAlbumRefreshing: Boolean = false,
    // v3.11.0 大模型使用策略 + 不依赖大模型的本地准确度增强
    val vlmModeId: String = "all",
    val vlmSummary: String = "",
    val localEnhanceEnabled: Boolean = true,
    val vlmSelectedCount: Int = 0,
    val vlmUncertainThreshold: Float = 0.55f
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

/** v3.10.0：相册变化增量刷新的计算结果（O(N) 计算放在 Dispatchers.Default）。 */
private data class AlbumRefresh(
    val merged: List<PhotoItem>,
    val removedIds: List<Long>,
    val added: List<PhotoItem>
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 280L
        private const val EXIF_BATCH = 200
    }

    private val repository = PhotoRepository(application)
    private val resultStore = AnalysisResultStore(application)
    private val exifStore = PhotoExifStore(application)

    // 并发安全：检索在 Default 线程读，Service / EXIF 提取在别的线程写
    private val metadataCache = ConcurrentHashMap<Long, PhotoMetadata>()

    /** v3.11.1：并发批量重分析时「手动选择消费」的互斥锁（防 SharedPreferences 读改写竞态）。 */
    private val vlmConsumeLock = Any()
    private var exifCache = ConcurrentHashMap<Long, PhotoExif>()
    private val docCache = ConceptRegistry.SearchDocCache()

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    private var filterJob: Job? = null
    @Volatile
    private var filterGeneration = 0L

    init {
        loadPhotos()
        observeServiceState()
        observeAnalysisEvents()
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

                // v3.9.0：与 Service 共用同一个计划器，按 photoId 差集算进度。
                // 照片增删、排序变化、isComplete 标记都不再影响「还剩几张」的判断。
                val plan = withContext(Dispatchers.Default) { computePlan(photos) }
                val hasAnalyzed = plan.doneCount > 0 || saved?.isComplete == true ||
                        AnalysisService.analysisState.value.isComplete

                docCache.clear()
                // 先把相册显示出来，EXIF 在后台异步提取，避免首屏白屏
                _state.value = _state.value.copy(
                    allPhotos = merged,
                    filteredPhotos = merged,
                    isLoading = false,
                    hasAnalyzed = hasAnalyzed,
                    canResume = plan.resumed,
                    savedProgress = plan.doneCount,
                    savedTotal = plan.totalCount,
                    doneCount = plan.doneCount,
                    pendingCount = plan.pendingCount,
                    autoResumeEnabled = AppConfig.isAutoResumeEnabled(getApplication()),
                    backgroundWatchEnabled = AppConfig.isBackgroundWatchEnabled(getApplication()),
                    vlmModeId = AppConfig.getVlmModeId(getApplication()),
                    localEnhanceEnabled = AppConfig.isLocalEnhanceEnabled(getApplication()),
                    vlmSelectedCount = AppConfig.getVlmSelectedIds(getApplication()).size,
                    vlmUncertainThreshold = AppConfig.getUncertainThreshold(getApplication()),
                    vlmSummary = vlmSummaryOf()
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
                isActive = { gen == filterGeneration && isActive() },
                // v3.11.0：设置页可关闭本地增强，关闭后检索行为与 v3.10.0 完全一致
                localEnhance = st.localEnhanceEnabled
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

    /** v3.9.0：用纯逻辑计划器算「已完成 / 待分析」，UI 与 Service 口径完全一致。
     *  v3.11.0：改为使用同一份「大模型使用策略」快照，避免 UI 与后台守护口径不一致。 */
    private fun computePlan(photos: List<PhotoItem>): AnalysisPlanner.Plan {
        val config = readVlmConfig()
        return AnalysisPlanner.plan(
            photos.map { it.id }, metadataCache,
            AnalysisPlanner.VlmPlanPolicy(
                enabled = config.enabled,
                modeId = config.mode.id,
                selectedIds = config.selectedIds,
                uncertainThreshold = config.uncertainThreshold
            )
        )
    }

    /** v3.11.0：读取一次策略快照，供 UI 计划、单张/批量重分析复用。 */
    private fun readVlmConfig(): VlmPolicyConfig {
        val app = getApplication<Application>()
        return VlmPolicyConfig(
            mode = VlmMode.fromId(AppConfig.getVlmModeId(app)),
            uncertainThreshold = AppConfig.getUncertainThreshold(app),
            selectedIds = AppConfig.getVlmSelectedIds(app),
            apiKeyPresent = AppConfig.hasApiKey(app),
            wifiOnly = AppConfig.isVlmWifiOnly(app),
            wifiAvailable = isWifiAvailable()
        )
    }

    private fun isWifiAvailable(): Boolean {
        return try {
            val cm = getApplication<Application>()
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            true
        }
    }

    private fun vlmSummaryOf(): String = VlmPolicy.summary(readVlmConfig())

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

    /**
     * v3.10.0：只把服务进度映射到 UI，**不再触发全量合并**。
     * 旧版在服务完成/停止时调 syncServiceResults() → 全量 map + docCache.clear()
     * + scheduleFilters(0) 全库检索；后台守护不断有小批次完成时会反复清空几万张
     * 索引、卡住前端搜索。现在结果一律由 [observeAnalysisEvents] 增量吸收。
     */
    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { p ->
                val remaining = (p.total - p.progress).coerceAtLeast(0)
                _state.value = _state.value.copy(
                    isAnalyzing = p.isRunning,
                    analysisProgress = p.progress,
                    analysisTotal = p.total,
                    doneCount = p.progress,
                    pendingCount = remaining,
                    hasAnalyzed = p.isComplete || p.progress > 0 ||
                            _state.value.hasAnalyzed || metadataCache.isNotEmpty(),
                    // 守护开启时 pending 会被自动处理，无需再显示「继续」
                    canResume = if (p.isRunning || p.isComplete || _state.value.backgroundWatchActive) false
                    else remaining > 0 && p.progress > 0
                )
            }
        }
    }

    /**
     * v3.10.0：后台分析 → 前端的三类增量事件。磁盘是唯一权威，事件只是运行期加速，
     * 丢失也不影响正确性（冷启动 loadPhotos 会读全量）。
     */
    private fun observeAnalysisEvents() {
        viewModelScope.launch {
            AnalysisEvents.results.collect { batch -> absorbIncrement(batch) }
        }
        viewModelScope.launch {
            AnalysisEvents.albumChanges.collect { onAlbumChanged() }
        }
        viewModelScope.launch {
            AnalysisEvents.watchState.collect { w ->
                _state.value = _state.value.copy(
                    backgroundWatchActive = w.active,
                    autoAnalyzedCount = w.autoAnalyzed,
                    canResume = if (w.active) false else _state.value.canResume
                )
            }
        }
    }

    /**
     * 增量吸收一批分析结果 —— 「后台分析不影响前端搜索」的核心。
     *  1. metadataCache 只 putAll 这 k 张（O(k)），不碰全库；
     *  2. 只 invalidate 这 k 张的文档缓存，**绝不全量 clear**（否则下一条查询
     *     会重建几万张索引）；
     *  3. O(N) 的列表更新放 Dispatchers.Default，主线程只做 O(1) 状态写入；
     *  4. 无活动查询时直接替换列表、完全不跑检索；有查询时才后台防抖重排，
     *     用户输入期间不会叠加、不会阻塞。
     */
    private suspend fun absorbIncrement(batch: AnalysisEvents.ResultBatch) {
        if (batch.results.isEmpty()) return
        metadataCache.putAll(batch.results)
        docCache.invalidate(IncrementBuffer.idsToInvalidate(batch.results))

        val st = _state.value
        val hasQuery = st.searchQuery.isNotBlank() || st.timeRange != null || st.locationQuery != null
        val (merged, done) = withContext(Dispatchers.Default) {
            val list = st.allPhotos.map { p ->
                batch.results[p.id]?.let { m ->
                    p.copy(labels = m.labels, metadata = m, isAnalyzed = true)
                } ?: p
            }
            list to list.count { metadataCache.containsKey(it.id) }
        }
        _state.value = st.copy(
            allPhotos = merged,
            filteredPhotos = if (hasQuery) st.filteredPhotos else merged,
            hasAnalyzed = true,
            doneCount = done,
            pendingCount = (merged.size - done).coerceAtLeast(0)
        )
        if (hasQuery) scheduleFilters(SEARCH_DEBOUNCE_MS)
    }

    /** 相册变化：增量刷新列表，新照片立即显示、删除的移除，O(N) 计算放后台。 */
    private suspend fun onAlbumChanged() {
        if (_state.value.isAlbumRefreshing) return
        _state.value = _state.value.copy(isAlbumRefreshing = true)
        try {
            val photos = repository.loadAllPhotos()
            val snapshot = _state.value.allPhotos
            val st = _state.value
            val refresh = withContext(Dispatchers.Default) {
                val byCurrent = snapshot.associateBy { it.id }
                val freshIds = HashSet<Long>(photos.size * 2)
                photos.forEach { freshIds.add(it.id) }
                val removedIds = byCurrent.keys.filter { it !in freshIds }
                val added = photos.filter { it.id !in byCurrent }
                val merged = photos.map { p -> byCurrent[p.id] ?: decorateWithCache(p) }
                AlbumRefresh(merged, removedIds, added)
            }

            if (refresh.removedIds.isNotEmpty()) {
                refresh.removedIds.forEach { metadataCache.remove(it); exifCache.remove(it) }
                docCache.invalidate(refresh.removedIds)
            }

            val hasQuery = st.searchQuery.isNotBlank() || st.timeRange != null || st.locationQuery != null
            val done = withContext(Dispatchers.Default) {
                refresh.merged.count { metadataCache.containsKey(it.id) }
            }
            _state.value = st.copy(
                allPhotos = refresh.merged,
                // 无查询时直接展示新列表；有查询时保留现有结果，交给防抖后台重排
                filteredPhotos = if (hasQuery) st.filteredPhotos else refresh.merged,
                isAlbumRefreshing = false,
                savedProgress = done,
                savedTotal = refresh.merged.size,
                doneCount = done,
                pendingCount = (refresh.merged.size - done).coerceAtLeast(0)
            )

            if (refresh.added.isNotEmpty()) extractExifIncremental(refresh.added)
            recomputePlaces()
            if (hasQuery) scheduleFilters(SEARCH_DEBOUNCE_MS)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isAlbumRefreshing = false)
        }
    }

    private fun decorateWithCache(p: PhotoItem): PhotoItem {
        val m = metadataCache[p.id] ?: return p
        return if (m.isAnalyzed) p.copy(labels = m.labels, metadata = m, isAnalyzed = true) else p
    }

    /**
     * 只给新照片提取 EXIF：直接写同一个并发 Map 引用 + 增量 append 落盘，
     * 不重新 load() 全量缓存，也不清空检索缓存 —— 几万张下仍是 O(新增数)。
     */
    private fun extractExifIncremental(photos: List<PhotoItem>) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val buffer = ArrayList<PhotoExif>(photos.size)
            withContext(Dispatchers.IO) {
                photos.forEach { p ->
                    ensureActive()
                    val e = try {
                        ExifReader.read(app, p)
                    } catch (_: Exception) {
                        PhotoExif(
                            photoId = p.id,
                            dateTaken = effectiveTimeOf(p, null),
                            dateSource = "mediastore"
                        )
                    }
                    exifCache[p.id] = e
                    buffer.add(e)
                }
                if (buffer.isNotEmpty()) exifStore.append(buffer)
            }
            _state.value = _state.value.copy(
                exifMap = exifCache,
                exifVersion = _state.value.exifVersion + 1
            )
            recomputePlaces()
        }
    }

    /** 手动「开始/继续分析」：确保守护运行并立即对账一次。 */
    fun startAnalysis() {
        val context = getApplication<Application>()
        if (!AnalysisService.isWatchActive()) {
            AnalysisService.requestWatch(context)
        } else {
            AnalysisService.requestScan(context)
        }
        // 立即反馈，随后由 analysisState 事件校准
        _state.value = _state.value.copy(isAnalyzing = true)
    }

    /** 「继续分析」与「开始分析」走同一入口：Service 按 photoId 差集决定从哪开始。 */
    fun resumeAnalysis() = startAnalysis()

    /**
     * v3.10.0：应用回到前台时确保后台守护在运行。
     * - 守护开启（默认）：服务未常驻则拉起；开机启动受限留下的 pending 触发一次对账。
     * - 守护关闭：沿用 v3.9.0 的「自动继续未完成分析」降级 —— 若仍有未分析照片或
     *   存在 pending，启动一次性服务续跑（开关关闭时服务跑完即退，不常驻）。
     * 首次安装、已全部分析完成、且开关都关闭时都不会擅自启动云端 AI。
     */
    fun ensureBackgroundWatch() {
        val app = getApplication<Application>()
        val watch = AppConfig.isBackgroundWatchEnabled(app)
        val autoResume = AppConfig.isAutoResumeEnabled(app)
        _state.value = _state.value.copy(
            autoResumeEnabled = autoResume,
            backgroundWatchEnabled = watch
        )
        val pending = AppConfig.isWatchPending(app)
        if (watch) {
            if (!AnalysisService.isWatchActive()) {
                AnalysisService.requestWatch(app)
            } else if (pending) {
                AppConfig.setWatchPending(app, false)
                AnalysisService.requestScan(app)
            }
        } else if (autoResume && !AnalysisService.isRunning()) {
            val plan = computePlan(_state.value.allPhotos)
            if (plan.resumed || pending) {
                AppConfig.setWatchPending(app, false)
                AnalysisService.requestWatch(app)
            }
        }
    }

    /** 兼容旧调用名（MainActivity.onResume）。 */
    fun autoResumeIfNeeded() = ensureBackgroundWatch()

    /** 设置页开关：立即启停常驻守护。 */
    fun setBackgroundWatch(enabled: Boolean) {
        val app = getApplication<Application>()
        AppConfig.setBackgroundWatchEnabled(app, enabled)
        _state.value = _state.value.copy(backgroundWatchEnabled = enabled)
        if (enabled) {
            if (!AnalysisService.isWatchActive()) AnalysisService.requestWatch(app)
        } else {
            AnalysisService.requestStop(app)
            _state.value = _state.value.copy(backgroundWatchActive = false)
        }
    }

    /**
     * v3.11.0：设置页保存「大模型使用策略 / 本地增强开关」后调用。
     * 文档缓存内容随开关变化，必须整体失效；后台守护按新策略立即重新对账。
     */
    fun onVlmConfigChanged() {
        val app = getApplication<Application>()
        _state.value = _state.value.copy(
            vlmModeId = AppConfig.getVlmModeId(app),
            localEnhanceEnabled = AppConfig.isLocalEnhanceEnabled(app),
            vlmSelectedCount = AppConfig.getVlmSelectedIds(app).size,
            vlmUncertainThreshold = AppConfig.getUncertainThreshold(app),
            vlmSummary = vlmSummaryOf()
        )
        docCache.clear()
        val plan = computePlan(_state.value.allPhotos)
        _state.value = _state.value.copy(
            doneCount = plan.doneCount,
            pendingCount = plan.pendingCount,
            canResume = plan.resumed
        )
        scheduleFilters(0)
        if (AnalysisService.isWatchActive()) AnalysisService.requestScan(app)
    }

    /**
     * v3.11.0：把多选的照片明确交给大模型精修。
     * 这是「选择让大模型分析的照片」的主入口：写入持久化待办集合后，
     * 任何非 OFF 策略下都会被计划器强制纳入队列，分析完自动消费。
     */
    fun analyzeSelectedWithVlm() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val app = getApplication<Application>()
        if (AppConfig.getVlmModeId(app) == VlmMode.OFF.id) {
            _state.value = _state.value.copy(
                error = "当前为「不使用大模型」，请先在设置里选择一种大模型使用策略"
            )
            return
        }
        if (!AppConfig.hasApiKey(app)) {
            _state.value = _state.value.copy(error = "请先在设置里配置 API Key")
            return
        }
        AppConfig.addVlmSelectedIds(app, ids)
        exitSelectionMode()
        _state.value = _state.value.copy(
            error = null,
            vlmSelectedCount = AppConfig.getVlmSelectedIds(app).size,
            vlmSummary = vlmSummaryOf()
        )
        if (AnalysisService.isWatchActive()) AnalysisService.requestScan(app)
        else AnalysisService.requestWatch(app)
    }

    /** 清空结果全量重跑：磁盘由 Service 单点清理，避免两侧同时 clear 竞态。 */
    fun restartAnalysis() {
        viewModelScope.launch {
            metadataCache.clear()
            docCache.clear()
            val stripped = _state.value.allPhotos.map {
                it.copy(labels = emptyList(), metadata = null, isAnalyzed = false)
            }
            _state.value = _state.value.copy(
                allPhotos = stripped,
                filteredPhotos = stripped,
                hasAnalyzed = false,
                canResume = false,
                savedProgress = 0,
                savedTotal = stripped.size,
                doneCount = 0,
                pendingCount = stripped.size
            )
            scheduleFilters(0)
            // Service 自己清磁盘 + 全量重跑；服务未运行时会一并拉起
            AnalysisService.requestReanalyzeAll(getApplication<Application>())
        }
    }

    /** Re-analyze a single photo (on-device + VLM if enabled). */
    fun reanalyzePhoto(photo: PhotoItem) {
        if (_state.value.reanalyzingPhoto) return
        viewModelScope.launch {
            _state.value = _state.value.copy(reanalyzingPhoto = true, reanalyzeError = null)
            try {
                val context = getApplication<Application>()
                // v3.11.0：单张重分析同样遵守「大模型使用策略」
                val vlmConfig = readVlmConfig()

                val analyzer = EnhancedImageAnalyzer(context)
                val onDeviceMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyzePhoto(photo.uri, context)
                }
                analyzer.close()

                var description = ""
                var vlmKeywords = listOf<String>()
                val decision = VlmPolicy.decide(
                    vlmConfig, photo.id,
                    isNew = false,
                    hasDescription = false,
                    confidence = VlmPolicy.localConfidence(onDeviceMetadata)
                )
                if (decision.useVlm) {
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
                    AppConfig.removeVlmSelectedIds(context, listOf(photo.id))
                }

                val metadata = onDeviceMetadata.copy(
                    photoId = photo.id,
                    description = description,
                    vlmKeywords = vlmKeywords
                )
                metadataCache[photo.id] = metadata
                AnalysisService.analyzedMetadata[photo.id] = metadata
                docCache.invalidate(listOf(photo.id))

                // v3.9.0：只追加这一张，不对几万条缓存做全表扫描
                withContext(Dispatchers.IO) {
                    resultStore.appendResults(listOf(photo.id), metadataCache)
                    val all = _state.value.allPhotos
                    val done = all.count { metadataCache.containsKey(it.id) }
                    if (done == all.size) resultStore.markComplete(all.size, done)
                    else resultStore.markProgress(all.size, done)
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
            // v3.11.0：批量重分析也遵守「大模型使用策略」，逐张判定
            val vlmConfig = readVlmConfig()
            val analyzer = EnhancedImageAnalyzer(context)
            val vlmAnalyzer = if (vlmConfig.enabled) VisionLanguageAnalyzer(context) else null
            // v3.8.0：批量过程中不再每张复制整个照片列表，结束时合并一次
            val updatedById = HashMap<Long, PhotoItem>(ids.size)

            try {
                // v3.11.1：批量重分析同样按「分析并发数」分块并发 —— 块内多张并行
                // 分析（ML Kit 检测器线程安全），块间串行更新 UI 状态与进度。
                val parallelism = AnalysisScheduling.coerceParallelism(
                    AppConfig.getAnalysisParallelism(context)
                )
                val chunks = AnalysisScheduling.chunksOf(ids, parallelism)
                var processedCount = 0

                for (chunk in chunks) {
                    ensureActive()
                    val processed: List<PhotoItem> = coroutineScope {
                        chunk.map { photoId ->
                            async(Dispatchers.IO) {
                                val photo = _state.value.allPhotos.find { it.id == photoId }
                                    ?: return@async null
                                try {
                                    val onDeviceMetadata = analyzer.analyzePhoto(photo.uri, context)
                                    var description = ""
                                    var vlmKeywords = listOf<String>()
                                    val decision = VlmPolicy.decide(
                                        vlmConfig, photo.id,
                                        isNew = false,
                                        hasDescription = false,
                                        confidence = VlmPolicy.localConfidence(onDeviceMetadata)
                                    )
                                    if (decision.useVlm && vlmAnalyzer != null) {
                                        try {
                                            val vlmResult = vlmAnalyzer.analyzePhoto(photo.uri)
                                            if (vlmResult.success) {
                                                description = vlmResult.description
                                                vlmKeywords = vlmResult.keywords
                                            }
                                        } catch (_: Exception) { }
                                        synchronized(vlmConsumeLock) {
                                            AppConfig.removeVlmSelectedIds(context, listOf(photo.id))
                                        }
                                    }
                                    val metadata = onDeviceMetadata.copy(
                                        photoId = photo.id, description = description, vlmKeywords = vlmKeywords
                                    )
                                    metadataCache[photo.id] = metadata
                                    AnalysisService.analyzedMetadata[photo.id] = metadata
                                    photo.copy(
                                        labels = metadata.labels, metadata = metadata, isAnalyzed = true
                                    )
                                } catch (_: Exception) { null }
                            }
                        }.mapNotNull { it.await() }
                    }

                    processed.forEach { updatedById[it.id] = it }
                    processedCount += processed.size

                    // 进度刷新节流：约每 10 张或每块末更新一次，避免高频重组
                    if (processedCount % 10 == 0 || chunk === chunks.last()) {
                        _state.value = _state.value.copy(batchProgress = processedCount)
                    }
                }

                // v3.9.0：只追加本批变化的 id，避免全表扫描
                withContext(Dispatchers.IO) {
                    resultStore.appendResults(updatedById.keys, metadataCache)
                    val all = _state.value.allPhotos
                    val done = all.count { metadataCache.containsKey(it.id) }
                    if (done == all.size) resultStore.markComplete(all.size, done)
                    else resultStore.markProgress(all.size, done)
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
