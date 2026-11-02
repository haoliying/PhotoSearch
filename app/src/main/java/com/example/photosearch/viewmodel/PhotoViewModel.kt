package com.example.photosearch.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.*
import com.example.photosearch.ml.SearchConditionMapper
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
    val searchDescription: String? = null
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
}
