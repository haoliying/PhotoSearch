package com.example.photosearch.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.AnalysisResultStore
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoRepository
import com.example.photosearch.ml.SearchConditionMapper
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
    // Resume support
    val canResume: Boolean = false,
    val savedProgress: Int = 0,
    val savedTotal: Int = 0
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PhotoRepository(application)
    private val resultStore = AnalysisResultStore(application)

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

                // Check for saved analysis results on disk
                val saved = withContext(Dispatchers.IO) { resultStore.load() }

                // Merge: service in-memory cache takes priority, then disk cache
                val merged = photos.map { photo ->
                    val labels = AnalysisService.analyzedLabels[photo.id]
                        ?: saved?.results?.get(photo.id)
                    if (labels != null) {
                        photo.copy(labels = labels, isAnalyzed = true)
                    } else photo
                }

                val hasAnalyzed = AnalysisService.analysisState.value.isComplete ||
                        saved?.isComplete == true ||
                        AnalysisService.analyzedLabels.isNotEmpty()

                // Determine if we can resume an incomplete analysis
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

    /**
     * Observe the foreground service's shared state flow and sync into UI state.
     */
    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { serviceProgress ->
                _state.value = _state.value.copy(
                    isAnalyzing = serviceProgress.isRunning,
                    analysisProgress = serviceProgress.progress,
                    analysisTotal = serviceProgress.total,
                    hasAnalyzed = serviceProgress.isComplete || _state.value.hasAnalyzed,
                    canResume = if (serviceProgress.isRunning || serviceProgress.isComplete) false
                                else _state.value.canResume
                )

                // When service finishes, sync all analyzed labels into photo list
                if (serviceProgress.isComplete && AnalysisService.analyzedLabels.isNotEmpty()) {
                    syncServiceResults()
                }
            }
        }
    }

    private fun syncServiceResults() {
        val labels = AnalysisService.analyzedLabels
        _state.value = _state.value.copy(
            allPhotos = _state.value.allPhotos.map { photo ->
                val photoLabels = labels[photo.id]
                if (photoLabels != null) {
                    photo.copy(labels = photoLabels, isAnalyzed = true)
                } else photo
            },
            canResume = false,
            savedProgress = 0,
            savedTotal = 0
        )
        if (_state.value.searchQuery.isNotBlank()) {
            search(_state.value.searchQuery)
        } else {
            _state.value = _state.value.copy(filteredPhotos = _state.value.allPhotos)
        }
    }

    /**
     * Start the foreground service for background analysis.
     * If there's saved progress, the service will automatically resume from where it left off.
     */
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

    /**
     * Clear saved analysis results and start a fresh analysis.
     */
    fun restartAnalysis() {
        if (_state.value.isAnalyzing || AnalysisService.isRunning()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) { resultStore.clear() }
            AnalysisService.reset()

            _state.value = _state.value.copy(
                allPhotos = _state.value.allPhotos.map { it.copy(labels = emptyList(), isAnalyzed = false) },
                filteredPhotos = _state.value.allPhotos.map { it.copy(labels = emptyList(), isAnalyzed = false) },
                hasAnalyzed = false,
                canResume = false,
                savedProgress = 0,
                savedTotal = 0
            )

            startAnalysis()
        }
    }

    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)

        if (query.isBlank()) {
            _state.value = _state.value.copy(filteredPhotos = _state.value.allPhotos)
            return
        }

        val results = SearchConditionMapper.matchPhotos(query, _state.value.allPhotos)
        _state.value = _state.value.copy(filteredPhotos = results)
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

    fun getSuggestions(): List<String> = SearchConditionMapper.getSuggestions()

    fun getRuleDescription(): String? =
        SearchConditionMapper.getRuleDescription(_state.value.searchQuery)
}
