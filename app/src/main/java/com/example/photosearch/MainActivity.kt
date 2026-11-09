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
import com.example.photosearch.ui.SwipeBackContainer
import com.example.photosearch.ui.screens.AboutScreen
import com.example.photosearch.ui.screens.HelpScreen
import com.example.photosearch.ui.screens.HomeScreen
import com.example.photosearch.ui.screens.PhotoDetailScreen
import com.example.photosearch.ui.screens.SettingsScreen
import com.example.photosearch.ui.theme.PhotoSearchTheme
import com.example.photosearch.viewmodel.PhotoViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PhotoViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 授权成功后必须重新扫描：ViewModel 的首次加载发生在弹框之前，拿到的是空列表
        if (granted) {
            requestMediaLocationPermission()
            viewModel.reloadPhotos()
        }
    }

    /**
     * Android 10+ 分区存储下，读取照片里的 GPS 必须额外申请 ACCESS_MEDIA_LOCATION，
     * 否则 MediaStore.setRequireOriginal() 会抛异常，只能拿到被涂改过的坐标。
     * 拒绝也不影响使用：拍摄时间与内容检索仍然可用，只是地点为空。
     */
    private val mediaLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.reloadPhotos()   // 重新扫描以补齐 GPS
    }

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
                    // v3.9.0：版本说明 / 操作手册
                    var showAbout by remember { mutableStateOf(false) }
                    var showHelp by remember { mutableStateOf(false) }

                    when {
                        showAbout -> {
                            SwipeBackContainer(onBack = { showAbout = false }) {
                                AboutScreen(onBack = { showAbout = false })
                            }
                        }
                        showHelp -> {
                            SwipeBackContainer(onBack = { showHelp = false }) {
                                HelpScreen(onBack = { showHelp = false })
                            }
                        }
                        showSettings -> {
                            // v3.7.0：右滑 / 系统返回键都回到相册主页
                            SwipeBackContainer(onBack = { showSettings = false }) {
                                SettingsScreen(
                                    context = this@MainActivity,
                                    onBack = { showSettings = false },
                                    onOpenAbout = { showSettings = false; showAbout = true },
                                    onOpenHelp = { showSettings = false; showHelp = true }
                                )
                            }
                        }
                        state.selectedPhoto != null -> {
                            SwipeBackContainer(onBack = { viewModel.selectPhoto(null) }) {
                                PhotoDetailScreen(
                                    photo = state.selectedPhoto!!,
                                    onBack = { viewModel.selectPhoto(null) },
                                    onShare = { sharePhoto(state.selectedPhoto!!) },
                                    onReanalyze = { viewModel.reanalyzePhoto(state.selectedPhoto!!) },
                                    isReanalyzing = state.reanalyzingPhoto,
                                    reanalyzeError = state.reanalyzeError,
                                    exif = state.selectedPhoto?.let { state.exifMap[it.id] },
                                    onSetPlaceLabel = { id, label -> viewModel.setPlaceLabel(id, label) }
                                )
                            }
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
                                onResumeAnalysis = { viewModel.resumeAnalysis() },
                                onRestartAnalysis = { viewModel.restartAnalysis() },
                                canResume = state.canResume,
                                savedProgress = state.doneCount,
                                savedTotal = state.allPhotos.size,
                                doneCount = state.doneCount,
                                pendingCount = state.pendingCount,
                                searchResultCount = state.searchResultCount,
                                suggestions = viewModel.getSuggestions(),
                                ruleDescription = viewModel.getRuleDescription(),
                                onSettingsClick = { showSettings = true },
                                onOpenAbout = { showAbout = true },
                                onOpenHelp = { showHelp = true },
                                error = state.error,
                                onReloadPhotos = { viewModel.reloadPhotos() },
                                onClearSearch = { viewModel.search("") },
                                selectionMode = state.selectionMode,
                                selectedIds = state.selectedIds,
                                batchReanalyzing = state.batchReanalyzing,
                                batchProgress = state.batchProgress,
                                batchTotal = state.batchTotal,
                                onEnterSelection = { viewModel.enterSelectionMode() },
                                onExitSelection = { viewModel.exitSelectionMode() },
                                onToggleSelection = { viewModel.togglePhotoSelection(it) },
                                onSelectAll = { viewModel.selectAllFiltered() },
                                onBatchReanalyze = { viewModel.reanalyzeSelectedPhotos() },
                                // v3.6.0 时间 / 地点联动筛选
                                timeRange = state.timeRange,
                                locationQuery = state.locationQuery,
                                availablePlaces = state.availablePlaces,
                                filterSummary = state.filterSummary,
                                isExtractingExif = state.isExtractingExif,
                                exifProgress = state.exifProgress,
                                exifTotal = state.exifTotal,
                                noTimeCount = state.noTimeCount,
                                noLocationCount = state.noLocationCount,
                                onTimeRangeChange = { viewModel.setTimeRange(it) },
                                onLocationQueryChange = { viewModel.setLocationQuery(it) },
                                onSelectPlace = { viewModel.selectPlace(it) },
                                onClearFilters = { viewModel.clearFilters() },
                                exifMap = state.exifMap,
                                // v3.8.0
                                isSearching = state.isSearching,
                                exifVersion = state.exifVersion
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能在系统设置里改过照片权限，或首次授权后返回前台；列表为空时重扫一次
        if (ContextCompat.checkSelfPermission(this, photoPermission()) == PackageManager.PERMISSION_GRANTED) {
            viewModel.reloadIfEmpty()
            // v3.9.0：上次分析中断后，回到前台自动从剩余照片继续（可在设置里关闭）
            viewModel.autoResumeIfNeeded()
        }
    }

    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun requestPermissions() {
        // Photo access permission
        val perm = photoPermission()
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(perm)
        } else {
            requestMediaLocationPermission()
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestMediaLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            mediaLocationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
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
