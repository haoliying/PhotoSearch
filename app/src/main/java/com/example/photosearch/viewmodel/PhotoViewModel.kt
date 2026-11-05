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
import com.example.photosearch.search.IntentParser
import com.example.photosearch.search.SearchEngine
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
    val batchTotal: Int = 0
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PhotoRepository(application)
    private val resultStore = AnalysisResultStore(application)

    // In-memory metadata cache
    private val metadataCache = mutableMapOf<Long, PhotoMetadata>()

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    init {
        loadPhotos()
        observeServiceState()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val photos = repository.loadAllPhotos()

                // Load saved metadata from disk
                val saved = withContext(Dispatchers.IO) { resultStore.load() }

                // Build metadata map: service memory > disk cache
                metadataCache.clear()
                saved?.metadataMap?.let { metadataCache.putAll(it) }
                metadataCache.putAll(AnalysisService.analyzedMetadata)

                // Merge metadata into photo items
                val merged = photos.map { photo ->
                    val metadata = metadataCache[photo.id]
                    if (metadata != null && metadata.isAnalyzed) {
                        photo.copy(
                            labels = metadata.labels,
                            metadata = metadata,
                            isAnalyzed = true
                        )
                    } else photo
                }

                val hasAnalyzed = AnalysisService.analysisState.value.isComplete ||
                        saved?.isComplete == true ||
                        metadataCache.isNotEmpty()

                val canResume = saved != null && !saved.isComplete &&
                        saved.analyzedCount > 0 && saved.totalPhotos == photos.size

                _state.value = _state.value.copy(
                    allPhotos = merged,
                    filteredPhotos = merged,
                    isLoading = false,
                    hasAnalyzed = hasAnalyzed,
                    canResume = canResume,
                    savedProgress = saved?.analyzedCount ?: 0,
                    savedTotal = saved?.totalPhotos ?: 0
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "加载照片失败: ${e.message}"
                )
            }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { serviceProgress ->
                // Sync metadata from service
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
                    photo.copy(
                        labels = metadata.labels,
                        metadata = metadata,
                        isAnalyzed = true
                    )
                } else photo
            },
            canResume = false,
            savedProgress = 0,
            savedTotal = 0
        )

        // Re-apply search if there's a query
        if (_state.value.searchQuery.isNotBlank()) {
            search(_state.value.searchQuery)
        } else {
            _state.value = _state.value.copy(filteredPhotos = _state.value.allPhotos)
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

            _state.value = _state.value.copy(
                allPhotos = _state.value.allPhotos.map { it.copy(labels = emptyList(), metadata = null, isAnalyzed = false) },
                filteredPhotos = _state.value.allPhotos.map { it.copy(labels = emptyList(), metadata = null, isAnalyzed = false) },
                hasAnalyzed = false,
                canResume = false,
                savedProgress = 0,
                savedTotal = 0
            )

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

                // On-device analysis
                val analyzer = EnhancedImageAnalyzer(context)
                val onDeviceMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyzePhoto(photo.uri, context)
                }
                analyzer.close()

                // VLM analysis (if enabled)
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

                // Build final metadata
                val metadata = onDeviceMetadata.copy(
                    photoId = photo.id,
                    description = description,
                    vlmKeywords = vlmKeywords
                )

                // Update cache
                metadataCache[photo.id] = metadata
                AnalysisService.analyzedMetadata[photo.id] = metadata

                // Save to disk
                withContext(Dispatchers.IO) {
                    resultStore.saveSegment(
                        totalPhotos = _state.value.allPhotos.size,
                        analyzedCount = metadataCache.size,
                        isComplete = _state.value.allPhotos.all { metadataCache.containsKey(it.id) },
                        metadataMap = metadataCache.toMap()
                    )
                }

                // Update the photo in state
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
     * Search photos using the new semantic search engine.
     * Parses natural language intent and matches against rich metadata.
     */
    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)

        if (query.isBlank()) {
            _state.value = _state.value.copy(
                filteredPhotos = _state.value.allPhotos,
                searchResultCount = 0,
                searchDescription = null
            )
            return
        }

        // Use the new SearchEngine with IntentParser
        val results = SearchEngine.quickSearch(query, _state.value.allPhotos, metadataCache)

        val description = IntentParser.getDescription(query)

        _state.value = _state.value.copy(
            filteredPhotos = results.map { it.photo },
            searchResultCount = results.size,
            searchDescription = description
        )
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
        if (current.contains(photoId) ) {
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

                if (_state.value.searchQuery.isNotBlank()) {
                    search(_state.value.searchQuery)
                }
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
