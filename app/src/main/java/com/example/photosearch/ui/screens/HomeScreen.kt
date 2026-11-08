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
import com.example.photosearch.search.LocationQuery
import com.example.photosearch.search.PlaceCatalog
import com.example.photosearch.search.PlaceSuggestion
import com.example.photosearch.search.TimeQueryParser
import com.example.photosearch.search.TimeRange
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
    error: String? = null,
    onReloadPhotos: () -> Unit = {},
    onClearSearch: () -> Unit = {},
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
    onBatchReanalyze: () -> Unit = {},
    // v3.6.0 时间 / 地点联动筛选
    timeRange: TimeRange? = null,
    locationQuery: LocationQuery? = null,
    availablePlaces: List<PlaceSuggestion> = emptyList(),
    filterSummary: String? = null,
    isExtractingExif: Boolean = false,
    exifProgress: Int = 0,
    exifTotal: Int = 0,
    noTimeCount: Int = 0,
    noLocationCount: Int = 0,
    onTimeRangeChange: (TimeRange?) -> Unit = {},
    onLocationQueryChange: (LocationQuery?) -> Unit = {},
    onSelectPlace: (PlaceSuggestion) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    var showSearchBar by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { showFilters = !showFilters }) {
                            Icon(
                                Icons.Default.FilterList,
                                "时间与地点筛选",
                                tint = if (filterSummary != null) Secondary else Color.White
                            )
                        }
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

            // v3.6.0 时间 / 地点联动筛选
            if (!selectionMode && (showFilters || filterSummary != null || isExtractingExif)) {
                FilterSection(
                    timeRange = timeRange,
                    locationQuery = locationQuery,
                    availablePlaces = availablePlaces,
                    filterSummary = filterSummary,
                    isExtractingExif = isExtractingExif,
                    exifProgress = exifProgress,
                    exifTotal = exifTotal,
                    noTimeCount = noTimeCount,
                    noLocationCount = noLocationCount,
                    onTimeRangeChange = onTimeRangeChange,
                    onLocationQueryChange = onLocationQueryChange,
                    onSelectPlace = onSelectPlace,
                    onClearFilters = {
                        onClearFilters()
                    }
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
                            filterSummary != null -> "筛选结果 ${searchResultCount} 张"
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
                photos.isEmpty() && filterSummary != null -> {
                    EmptyState(
                        message = "没有同时满足「$filterSummary」的照片。可以放宽时间区间、换个地点，或清除筛选。",
                        actionLabel = "清除筛选",
                        onAction = onClearFilters
                    )
                }
                photos.isEmpty() && searchQuery.isNotBlank() -> {
                    EmptyState(
                        message = "未找到匹配的照片",
                        actionLabel = "清除搜索",
                        onAction = onClearSearch
                    )
                }
                photos.isEmpty() -> {
                    EmptyState(
                        message = error ?: "没有读取到相册照片。请在系统设置 → 权限中允许「照片和视频」（Android 14 请选择「始终允许所有照片」），再点下方按钮重试",
                        actionLabel = "重新加载照片",
                        onAction = onReloadPhotos
                    )
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

/**
 * v3.6.0 时间 / 地点联动筛选面板。
 * - 时间：快捷区间 + 自定义起止日期（yyyy-MM-dd）
 * - 地点：模糊输入框 + 从当前时间区间内实际照片统计出的候选地点（可点选）
 * - 两者与搜索词按 AND 联动；时间变化时候选地点自动重算
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSection(
    timeRange: TimeRange?,
    locationQuery: LocationQuery?,
    availablePlaces: List<PlaceSuggestion>,
    filterSummary: String?,
    isExtractingExif: Boolean,
    exifProgress: Int,
    exifTotal: Int,
    noTimeCount: Int,
    noLocationCount: Int,
    onTimeRangeChange: (TimeRange?) -> Unit,
    onLocationQueryChange: (LocationQuery?) -> Unit,
    onSelectPlace: (PlaceSuggestion) -> Unit,
    onClearFilters: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var locationText by remember { mutableStateOf(locationQuery?.raw ?: "") }

    // 外部（清空按钮 / 空状态按钮）清除筛选时，同步清空本地输入
    LaunchedEffect(locationQuery?.raw) { locationText = locationQuery?.raw ?: "" }
    LaunchedEffect(filterSummary) {
        if (filterSummary == null) {
            customStart = ""
            customEnd = ""
            customError = null
            locationText = ""
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "拍摄时间与地点",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(onClick = {
                    customStart = ""
                    customEnd = ""
                    customError = null
                    locationText = ""
                    onClearFilters()
                }) { Text("重置", fontSize = 12.sp) }
            }

            if (filterSummary != null) {
                Surface(shape = RoundedCornerShape(6.dp), color = Primary.copy(alpha = 0.10f)) {
                    Text(
                        "当前条件：$filterSummary",
                        fontSize = 12.sp,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ---------- 时间 ----------
            Text("时间区间", style = MaterialTheme.typography.labelMedium, color = OnSurface.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    TimePresetChip("全部", timeRange == null) { onTimeRangeChange(null) }
                }
                item {
                    TimePresetChip("今年", timeRange?.label == "今年") {
                        onTimeRangeChange(TimeQueryParser.thisYear(now))
                    }
                }
                item {
                    TimePresetChip("去年", timeRange?.label == "去年") {
                        onTimeRangeChange(TimeQueryParser.lastYear(now))
                    }
                }
                item {
                    TimePresetChip("最近3个月", timeRange?.label == "最近3个月") {
                        onTimeRangeChange(TimeQueryParser.recentMonths(3, now))
                    }
                }
                item {
                    TimePresetChip("最近1年", timeRange?.label == "最近1年") {
                        onTimeRangeChange(TimeQueryParser.recentMonths(12, now))
                    }
                }
                item {
                    TimePresetChip("最近30天", timeRange?.label == "最近30天") {
                        onTimeRangeChange(TimeQueryParser.recentDays(30, now))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customStart,
                    onValueChange = { customStart = it; customError = null },
                    placeholder = { Text("2023-01-01", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    modifier = Modifier.weight(1f)
                )
                Text(" ~ ", color = OnSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                OutlinedTextField(
                    value = customEnd,
                    onValueChange = { customEnd = it; customError = null },
                    placeholder = { Text("2023-12-31", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        val r = TimeQueryParser.fromDateStrings(customStart.trim(), customEnd.trim())
                        if (r == null) customError = "请按 2023-01-01 格式填写起止日期"
                        else { customError = null; onTimeRangeChange(r) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text("应用", fontSize = 12.sp) }
            }
            if (customError != null) {
                Text(customError.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            } else if (timeRange != null) {
                Text(
                    "区间：${TimeQueryParser.format(timeRange)}",
                    fontSize = 11.sp,
                    color = Primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Divider(modifier = Modifier.padding(vertical = 10.dp))

            // ---------- 地点 ----------
            Text("拍摄地点（支持模糊）", style = MaterialTheme.typography.labelMedium, color = OnSurface.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = locationText,
                onValueChange = { v ->
                    locationText = v
                    onLocationQueryChange(
                        if (v.isBlank()) null else buildLocationQuery(v, availablePlaces)
                    )
                },
                placeholder = { Text("输入或选择：北京 / 深圳 / 西湖…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Place, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (locationText.isNotBlank()) {
                        IconButton(onClick = { locationText = ""; onLocationQueryChange(null) }) {
                            Icon(Icons.Default.Clear, null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (availablePlaces.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "已记录的地点（随上方时间区间联动）",
                    fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(availablePlaces) { s ->
                        SuggestionChip(
                            onClick = { locationText = s.label; onSelectPlace(s) },
                            label = { Text("${s.label} · ${s.count}", fontSize = 11.sp) },
                            shape = CircleShape,
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = ChipBackground,
                                labelColor = ChipText
                            )
                        )
                    }
                }
            } else if (!isExtractingExif) {
                Text(
                    "还没有可用的地点信息。照片若不含 GPS，可在照片详情页手动标注地点。",
                    fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (isExtractingExif) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = if (exifTotal > 0) exifProgress.toFloat() / exifTotal else 0f,
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = ProgressTrack
                )
                Text(
                    "正在读取照片的时间与地点 $exifProgress / $exifTotal",
                    fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (timeRange != null && noTimeCount > 0) {
                Text(
                    "$noTimeCount 张照片缺少拍摄时间，未纳入时间筛选",
                    fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.5f)
                )
            }
            if (locationQuery != null && noLocationCount > 0) {
                Text(
                    "$noLocationCount 张照片没有地点信息，可在详情页手动标注",
                    fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = CircleShape,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (selected) Primary.copy(alpha = 0.18f) else ChipBackground,
            labelColor = if (selected) Primary else ChipText
        )
    )
}

/** 把用户输入构造成地点查询：内置城市带中心点启用半径匹配，其余走文本模糊。 */
private fun buildLocationQuery(text: String, places: List<PlaceSuggestion>): LocationQuery {
    val t = text.trim()
    val place = PlaceCatalog.findByQuery(t)
    if (place != null) return PlaceCatalog.queryFor(place, t)
    val n = PlaceCatalog.normalize(t)
    val known = places.firstOrNull { s ->
        val sn = PlaceCatalog.normalize(s.label)
        sn.length >= 2 && n.length >= 2 && (sn.contains(n) || n.contains(sn))
    }
    return LocationQuery(
        raw = t,
        normalized = n,
        centerLat = known?.lat,
        centerLon = known?.lon,
        radiusKm = 60.0
    )
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = onRestartAnalysis) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重新分析全部（更换模型 / Key 后使用）", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
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
                color = OnSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onAction?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(actionLabel.orEmpty())
                }
            }
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
