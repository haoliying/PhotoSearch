package com.example.photosearch.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    photos: List<PhotoItem>,
    isLoading: Boolean,
    isAnalyzing: Boolean,
    analysisProgress: Int,
    analysisTotal: Int,
    hasAnalyzed: Boolean,
    searchQuery: String,
    onSearch: (String) -> Unit,
    onPhotoClick: (PhotoItem) -> Unit,
    onStartAnalysis: () -> Unit,
    onResumeAnalysis: () -> Unit,
    onRestartAnalysis: () -> Unit,
    canResume: Boolean,
    savedProgress: Int,
    savedTotal: Int,
    searchResultCount: Int,
    suggestions: List<String>,
    ruleDescription: String?,
    onSettingsClick: () -> Unit = {},
    // Multi-select
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    batchReanalyzing: Boolean = false,
    batchProgress: Int = 0,
    batchTotal: Int = 0,
    onEnterSelection: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onBatchReanalyze: () -> Unit = {}
) {
    var showSearchBar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (selectionMode) {
                // Selection mode top bar
                TopAppBar(
                    title = {
                        Text(
                            "已选择 ${selectedIds.size} 张",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onExitSelection) {
                            Icon(Icons.Default.Close, "取消", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Default.SelectAll, "全选", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Primary
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            "智能相册搜索",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Primary
                    ),
                    actions = {
                        IconButton(onClick = { showSearchBar = !showSearchBar }) {
                            Icon(Icons.Default.Search, "搜索", tint = Color.White)
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, "设置", tint = Color.White)
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (selectionMode && selectedIds.isNotEmpty()) {
                // Bottom action bar for selection mode
                Surface(
                    shadowElevation = 8.dp,
                    color = Surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "长按照片可多选",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.5f)
                        )
                        Button(
                            onClick = onBatchReanalyze,
                            enabled = !batchReanalyzing,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新分析 (${selectedIds.size})")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Background)
        ) {
            // Batch re-analyze progress
            if (batchReanalyzing) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "正在重新分析照片…",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = if (batchTotal > 0) batchProgress.toFloat() / batchTotal else 0f,
                            modifier = Modifier.fillMaxWidth(),
                            color = Primary,
                            trackColor = ProgressTrack,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$batchProgress / $batchTotal",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Search section
            if (!selectionMode && (showSearchBar || searchQuery.isNotBlank())) {
                SearchSection(
                    query = searchQuery,
                    onQueryChange = onSearch,
                    suggestions = suggestions,
                    ruleDescription = ruleDescription
                )
            }

            // Analysis section
            if (!selectionMode) {
                AnalysisSection(
                    isAnalyzing = isAnalyzing,
                    hasAnalyzed = hasAnalyzed,
                    progress = analysisProgress,
                    total = analysisTotal,
                    photoCount = photos.size,
                    onStartAnalysis = onStartAnalysis,
                    onResumeAnalysis = onResumeAnalysis,
                    onRestartAnalysis = onRestartAnalysis,
                    canResume = canResume,
                    savedProgress = savedProgress,
                    savedTotal = savedTotal
                )
            }

            // Photo count
            if (!isLoading && !selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "找到 ${searchResultCount} 张匹配照片"
                            else -> "共 ${photos.size} 张照片"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                    if (hasAnalyzed && photos.isNotEmpty()) {
                        TextButton(onClick = onEnterSelection) {
                            Icon(
                                Icons.Default.Checklist,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = Primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("选择重分析", color = Primary)
                        }
                    }
                }
            }

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Primary)
                    }
                }
                photos.isEmpty() && searchQuery.isNotBlank() -> {
                    EmptyState(message = "未找到匹配的照片")
                }
                photos.isEmpty() -> {
                    EmptyState(message = "相册中没有照片")
                }
                else -> {
                    PhotoGrid(
                        photos = photos,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onPhotoClick = onPhotoClick,
                        onPhotoLongClick = { onEnterSelection(); onToggleSelection(it.id) },
                        onPhotoSelect = onToggleSelection
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<String>,
    ruleDescription: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索：有车、光膀子、泰山…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            if (ruleDescription != null && query.isNotBlank()) {
                Text(
                    text = "匹配规则: $ruleDescription",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            if (query.isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "快捷搜索:",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(suggestions) { suggestion ->
                        SuggestionChip(
                            onClick = { onQueryChange(suggestion) },
                            label = { Text(text = suggestion, fontSize = 13.sp) },
                            shape = CircleShape,
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = ChipBackground,
                                labelColor = ChipText
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisSection(
    isAnalyzing: Boolean,
    hasAnalyzed: Boolean,
    progress: Int,
    total: Int,
    photoCount: Int,
    onStartAnalysis: () -> Unit,
    onResumeAnalysis: () -> Unit,
    onRestartAnalysis: () -> Unit,
    canResume: Boolean,
    savedProgress: Int,
    savedTotal: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                isAnalyzing -> {
                    Text("正在分析照片…", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = if (total > 0) progress.toFloat() / total else 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary,
                        trackColor = ProgressTrack,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已分析 $progress / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                }
                canResume -> {
                    Text(
                        "上次分析中断，已保存进度",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已完成 $savedProgress / $savedTotal",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRestartAnalysis,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重新分析")
                        }
                        Button(
                            onClick = onResumeAnalysis,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("继续分析")
                        }
                    }
                }
                !hasAnalyzed && photoCount > 0 -> {
                    Text(
                        "首次使用需要分析照片内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onStartAnalysis,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始智能分析")
                    }
                }
                hasAnalyzed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Secondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "分析完成，可以搜索照片内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Search,
                null,
                modifier = Modifier.size(64.dp),
                tint = OnSurface.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    photos: List<PhotoItem>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onPhotoClick: (PhotoItem) -> Unit,
    onPhotoLongClick: (PhotoItem) -> Unit,
    onPhotoSelect: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItems(photos, key = { it.id }) { photo ->
            PhotoThumbnail(
                photo = photo,
                selectionMode = selectionMode,
                isSelected = selectedIds.contains(photo.id),
                onClick = {
                    if (selectionMode) {
                        onPhotoSelect(photo.id)
                    } else {
                        onPhotoClick(photo)
                    }
                },
                onLongClick = { onPhotoLongClick(photo) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoThumbnail(
    photo: PhotoItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.15f) else Color.White
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Selection checkbox overlay
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Primary else Color.White.copy(alpha = 0.8f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else if (photo.isAnalyzed && photo.labels.isNotEmpty()) {
                // Analysis indicator (only in normal mode)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Secondary)
                )
            }
        }
    }
}
