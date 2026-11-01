package com.example.photosearch.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoLabel
import com.example.photosearch.data.PhotoRepository
import com.example.photosearch.ml.SearchConditionMapper
import com.example.photosearch.service.AnalysisService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val selectedPhoto: PhotoItem? = null
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PhotoRepository(application)

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
                // If service already has analysis results, merge them
                val merged = if (AnalysisService.allPhotos.isNotEmpty()) {
                    photos.map { photo ->
                        val labels = AnalysisService.analyzedLabels[photo.id]
                        if (labels != null) {
                            photo.copy(labels = labels, isAnalyzed = true)
                        } else photo
                    }
                } else photos

                val hasAnalyzed = AnalysisService.analysisState.value.isComplete ||
                        AnalysisService.analyzedLabels.isNotEmpty()

                _state.value = _state.value.copy(
                    allPhotos = merged,
                    filteredPhotos = merged,
                    isLoading = false,
                    hasAnalyzed = hasAnalyzed
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
     * This keeps working even when the user leaves the Activity — the service
     * continues running and updates are picked up when the Activity resumes.
     */
    private fun observeServiceState() {
        viewModelScope.launch {
            AnalysisService.analysisState.collect { serviceProgress ->
                _state.value = _state.value.copy(
                    isAnalyzing = serviceProgress.isRunning,
                    analysisProgress = serviceProgress.progress,
                    analysisTotal = serviceProgress.total,
                    hasAnalyzed = serviceProgress.isComplete || _state.value.hasAnalyzed
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
            }
        )
        // Re-apply search filter with updated labels
        if (_state.value.searchQuery.isNotBlank()) {
            search(_state.value.searchQuery)
        } else {
            _state.value = _state.value.copy(filteredPhotos = _state.value.allPhotos)
        }
    }

    /**
     * Start the foreground service for background analysis.
     * The service will continue running even if the user navigates away.
     */
    fun startAnalysis() {
        if (_state.value.isAnalyzing || AnalysisService.isRunning()) return

        val context = getApplication<Application>()

        // For Android 12+ we need to use startForegroundService properly
        val serviceIntent = Intent(context, AnalysisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        _state.value = _state.value.copy(isAnalyzing = true)
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
