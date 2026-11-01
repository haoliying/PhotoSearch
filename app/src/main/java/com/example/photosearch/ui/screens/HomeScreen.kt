package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    suggestions: List<String>,
    ruleDescription: String?
) {
    var showSearchBar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Background)
        ) {
            // Search section
            if (showSearchBar || searchQuery.isNotBlank()) {
                SearchSection(
                    query = searchQuery,
                    onQueryChange = onSearch,
                    suggestions = suggestions,
                    ruleDescription = ruleDescription
                )
            }

            // Analysis section
            AnalysisSection(
                isAnalyzing = isAnalyzing,
                hasAnalyzed = hasAnalyzed,
                progress = analysisProgress,
                total = analysisTotal,
                photoCount = photos.size,
                onStartAnalysis = onStartAnalysis
            )

            // Photo count
            if (!isLoading) {
                Text(
                    text = if (searchQuery.isNotBlank()) {
                        "找到 ${photos.size} 张匹配照片"
                    } else {
                        "共 ${photos.size} 张照片"
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface.copy(alpha = 0.7f)
                )
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
                    PhotoGrid(photos = photos, onPhotoClick = onPhotoClick)
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

            // Suggestion chips
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
    onStartAnalysis: () -> Unit
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
                !hasAnalyzed && photoCount > 0 -> {
                    Text(
                        "首次使用需要分析照片内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onStartAnalysis,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
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
                        Icon(
                            Icons.Default.Search,
                            null,
                            tint = Secondary,
                            modifier = Modifier.size(18.dp)
                        )
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

@Composable
private fun PhotoGrid(
    photos: List<PhotoItem>,
    onPhotoClick: (PhotoItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItems(photos, key = { it.id }) { photo ->
            PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: PhotoItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Show analysis indicator
            if (photo.isAnalyzed && photo.labels.isNotEmpty()) {
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
