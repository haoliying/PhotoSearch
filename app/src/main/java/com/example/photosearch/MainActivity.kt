package com.example.photosearch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.photosearch.ui.screens.HomeScreen
import com.example.photosearch.ui.screens.PhotoDetailScreen
import com.example.photosearch.ui.screens.SettingsScreen
import com.example.photosearch.ui.theme.PhotoSearchTheme
import com.example.photosearch.viewmodel.PhotoViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PhotoViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: app handles missing permission gracefully */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission is optional, app still works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[PhotoViewModel::class.java]

        requestPermissions()

        setContent {
            PhotoSearchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsState()
                    var showSettings by remember { mutableStateOf(false) }

                    when {
                        showSettings -> {
                            SettingsScreen(
                                context = this@MainActivity,
                                onBack = { showSettings = false }
                            )
                        }
                        state.selectedPhoto != null -> {
                            PhotoDetailScreen(
                                photo = state.selectedPhoto!!,
                                onBack = { viewModel.selectPhoto(null) },
                                onShare = { sharePhoto(state.selectedPhoto!!) },
                                onReanalyze = { viewModel.reanalyzePhoto(state.selectedPhoto!!) },
                                isReanalyzing = state.reanalyzingPhoto,
                                reanalyzeError = state.reanalyzeError
                            )
                        }
                        else -> {
                            HomeScreen(
                                photos = state.filteredPhotos,
                                isLoading = state.isLoading,
                                isAnalyzing = state.isAnalyzing,
                                analysisProgress = state.analysisProgress,
                                analysisTotal = state.analysisTotal,
                                hasAnalyzed = state.hasAnalyzed,
                                searchQuery = state.searchQuery,
                                onSearch = { viewModel.search(it) },
                                onPhotoClick = { viewModel.selectPhoto(it) },
                                onStartAnalysis = { viewModel.startAnalysis() },
                                onResumeAnalysis = { viewModel.startAnalysis() },
                                onRestartAnalysis = { viewModel.restartAnalysis() },
                                canResume = state.canResume,
                                savedProgress = state.savedProgress,
                                savedTotal = state.savedTotal,
                                searchResultCount = state.searchResultCount,
                                suggestions = viewModel.getSuggestions(),
                                ruleDescription = viewModel.getRuleDescription(),
                                onSettingsClick = { showSettings = true },
                                selectionMode = state.selectionMode,
                                selectedIds = state.selectedIds,
                                batchReanalyzing = state.batchReanalyzing,
                                batchProgress = state.batchProgress,
                                batchTotal = state.batchTotal,
                                onEnterSelection = { viewModel.enterSelectionMode() },
                                onExitSelection = { viewModel.exitSelectionMode() },
                                onToggleSelection = { viewModel.togglePhotoSelection(it) },
                                onSelectAll = { viewModel.selectAllFiltered() },
                                onBatchReanalyze = { viewModel.reanalyzeSelectedPhotos() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        // Photo access permission
        val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, photoPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(photoPermission)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun sharePhoto(photo: com.example.photosearch.data.PhotoItem) {
        val shareUri = viewModel.getShareUri(photo)
        if (shareUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享照片"))
        }
    }
}
