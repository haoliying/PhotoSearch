package com.example.photosearch.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoRepository
import com.example.photosearch.ml.ImageAnalyzer
import com.example.photosearch.ml.SearchConditionMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
    private val analyzer = ImageAnalyzer(application)
    private val analyzedLabels = mutableMapOf<Long, List<com.example.photosearch.data.PhotoLabel>>()

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    init {
        loadPhotos()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val photos = repository.loadAllPhotos()
                _state.value = _state.value.copy(
                    allPhotos = photos,
                    filteredPhotos = photos,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "加载照片失败: ${e.message}"
                )
            }
        }
    }

    fun startAnalysis() {
        if (_state.value.isAnalyzing) return

        viewModelScope.launch {
            val photos = _state.value.allPhotos
            _state.value = _state.value.copy(
                isAnalyzing = true,
                analysisProgress = 0,
                analysisTotal = photos.size
            )

            val processedCount = AtomicInteger(0)
            val batchSize = 3
            val totalBatches = (photos.size + batchSize - 1) / batchSize

            for (batchIndex in 0 until totalBatches) {
                val start = batchIndex * batchSize
                val end = minOf(start + batchSize, photos.size)
                val batch = photos.subList(start, end)

                val results = batch.map { photo ->
                    val labels = analyzer.analyzePhoto(photo.uri, getApplication())
                    photo.id to labels
                }

                results.forEach { (id, labels) ->
                    analyzedLabels[id] = labels
                }

                val current = processedCount.addAndGet(batch.size)
                _state.value = _state.value.copy(
                    analysisProgress = current,
                    allPhotos = _state.value.allPhotos.map { p ->
                        if (analyzedLabels.containsKey(p.id)) {
                            p.copy(labels = analyzedLabels[p.id] ?: emptyList(), isAnalyzed = true)
                        } else p
                    }
                )
            }

            _state.value = _state.value.copy(
                isAnalyzing = false,
                hasAnalyzed = true
            )

            // Re-apply search if there's a query
            if (_state.value.searchQuery.isNotBlank()) {
                search(_state.value.searchQuery)
            }
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

    override fun onCleared() {
        super.onCleared()
        analyzer.close()
    }
}
