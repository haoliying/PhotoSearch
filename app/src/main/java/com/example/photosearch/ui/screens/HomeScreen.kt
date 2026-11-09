package com.example.photosearch.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.photosearch.data.PhotoExif
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.chineseDisplayTags
import com.example.photosearch.search.LocationQuery
import com.example.photosearch.search.PlaceCatalog
import com.example.photosearch.search.PlaceSuggestion
import com.example.photosearch.search.TimeQueryParser
import com.example.photosearch.search.TimeRange
import com.example.photosearch.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.7.0 主界面重构。
 *
 * 旧版把「搜索卡片 + 筛选卡片 + 分析卡片 + 计数行」四块常驻堆叠，
 * 照片只剩屏幕底部一小块。本版的原则：
 *  1. 照片是主体：所有查询条件默认收起，顶部只保留一行图标；
 *  2. 条件可见但不占地：生效时只显示一条摘要栏，点开才展开完整面板；
 *  3. 两种视图：网格（纯缩略图）/ 列表（缩略图 + 时间、地点、格式等 EXIF 信息）；
 *  4. 分析入口收成一条细状态条，完成即隐藏，不再常驻大卡片。
 */
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
    // v3.9.0 断点续分析
    doneCount: Int = 0,
    pendingCount: Int = 0,
    searchResultCount: Int,
    suggestions: List<String>,
    ruleDescription: String?,
    onSettingsClick: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
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
    onClearFilters: () -> Unit = {},
    // v3.7.0：列表视图展示 EXIF 信息
    exifMap: Map<Long, PhotoExif> = emptyMap(),
    // v3.8.0：后台检索状态 / EXIF 缓存版本（触发列表刷新，避免全量复制）
    isSearching: Boolean = false,
    exifVersion: Int = 0
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    val hasQuery = searchQuery.isNotBlank()
    val hasConditions = hasQuery || filterSummary != null

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选择 ${selectedIds.size} 张", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onExitSelection) {
                            Icon(Icons.Default.Close, "取消多选", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Default.SelectAll, "全选", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("智能相册搜索", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            if (!isLoading) {
                                Text(
                                    when {
                                        hasConditions -> "$searchResultCount 张结果"
                                        else -> "共 ${photos.size} 张照片"
                                    },
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary),
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch; showFilters = false }) {
                            Icon(
                                if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                "搜索", tint = if (hasQuery) Secondary else Color.White
                            )
                        }
                        IconButton(onClick = { showFilters = !showFilters; showSearch = false }) {
                            Icon(
                                Icons.Default.Tune, "时间与地点筛选",
                                tint = if (filterSummary != null) Secondary else Color.White
                            )
                        }
                        IconButton(onClick = { listMode = !listMode }) {
                            Icon(
                                if (listMode) Icons.Default.Apps else Icons.Default.List,
                                if (listMode) "切换为缩略图" else "切换为列表",
                                tint = Color.White
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Default.MoreVert, "更多", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("设置（API / 模型）") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { showOverflow = false; onSettingsClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("重新分析全部") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = { showOverflow = false; onRestartAnalysis() }
                                )
                                if (hasAnalyzed && photos.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("多选照片重新分析") },
                                        leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                        onClick = { showOverflow = false; onEnterSelection() }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("重新加载相册") },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                    onClick = { showOverflow = false; onReloadPhotos() }
                                )
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("操作手册") },
                                    leadingIcon = { Icon(Icons.Default.List, null) },
                                    onClick = { showOverflow = false; onOpenHelp() }
                                )
                                DropdownMenuItem(
                                    text = { Text("版本说明") },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { showOverflow = false; onOpenAbout() }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (selectionMode && selectedIds.isNotEmpty()) {
                Surface(shadowElevation = 8.dp, color = Surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选 ${selectedIds.size} 张", style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f))
                        Button(
                            onClick = onBatchReanalyze,
                            enabled = !batchReanalyzing,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("重新分析") }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Background)
        ) {
            if (batchReanalyzing) {
                ThinProgress("正在重新分析照片…", batchProgress, batchTotal)
            }
            // v3.8.0：检索在后台线程跑，用一条细进度条反馈，输入与滚动不受影响
            if (isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = ProgressTrack
                )
            }

            // ---------- 生效条件摘要：只占一行 ----------
            if (!selectionMode && hasConditions) {
                ActiveConditionBar(
                    query = searchQuery,
                    filterSummary = filterSummary,
                    ruleDescription = ruleDescription,
                    resultCount = searchResultCount,
                    onExpandSearch = { showSearch = true },
                    onExpandFilters = { showFilters = true },
                    onClearAll = { onClearSearch(); onClearFilters(); showSearch = false; showFilters = false },
                    isSearching = isSearching
                )
            }

            // ---------- 搜索面板（点开才出现） ----------
            if (!selectionMode && showSearch) {
                SearchPanel(
                    query = searchQuery,
                    onQueryChange = onSearch,
                    suggestions = suggestions,
                    ruleDescription = ruleDescription,
                    onClose = { showSearch = false }
                )
            }

            // ---------- 时间 / 地点筛选面板（点开才出现） ----------
            if (!selectionMode && showFilters) {
                FilterPanel(
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
                    onClearFilters = onClearFilters,
                    onClose = { showFilters = false }
                )
            }

            // ---------- 分析状态：细条，完成即隐藏 ----------
            if (!selectionMode) {
                AnalysisStatusBar(
                    isAnalyzing = isAnalyzing,
                    hasAnalyzed = hasAnalyzed,
                    progress = analysisProgress,
                    total = analysisTotal,
                    photoCount = photos.size,
                    canResume = canResume,
                    savedProgress = savedProgress,
                    savedTotal = savedTotal,
                    doneCount = doneCount,
                    pendingCount = pendingCount,
                    isExtractingExif = isExtractingExif,
                    exifProgress = exifProgress,
                    exifTotal = exifTotal,
                    onStartAnalysis = onStartAnalysis,
                    onResumeAnalysis = onResumeAnalysis,
                    onRestartAnalysis = onRestartAnalysis,
                    onOpenHelp = onOpenHelp
                )
            }

            // ---------- 照片主体 ----------
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                    photos.isEmpty() && filterSummary != null -> EmptyState(
                        "没有同时满足「$filterSummary」的照片。可以放宽时间区间、换个地点，或清除筛选。",
                        "清除筛选", onClearFilters
                    )
                    photos.isEmpty() && hasQuery -> EmptyState("未找到匹配的照片", "清除搜索", onClearSearch)
                    photos.isEmpty() -> EmptyState(
                        error ?: "没有读取到相册照片。请在系统设置 → 权限中允许「照片和视频」（Android 14 请选择「始终允许所有照片」），再点下方按钮重试",
                        "重新加载照片", onReloadPhotos
                    )
                    else -> key(exifVersion) {
                        PhotoCollection(
                            photos = photos,
                            listMode = listMode,
                            exifMap = exifMap,
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
}

// ---------------------------------------------------------------------
// 顶部条件摘要
// ---------------------------------------------------------------------

@Composable
private fun ActiveConditionBar(
    query: String,
    filterSummary: String?,
    ruleDescription: String?,
    resultCount: Int,
    onExpandSearch: () -> Unit,
    onExpandFilters: () -> Unit,
    onClearAll: () -> Unit,
    isSearching: Boolean = false
) {
    Surface(color = Surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FilterList, null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildString {
                        if (query.isNotBlank()) append("「$query」")
                        if (filterSummary != null) {
                            if (isNotEmpty()) append(" · ")
                            append(filterSummary)
                        }
                    }.ifBlank { "当前筛选" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ruleDescription != null && query.isNotBlank()) {
                    Text(ruleDescription, fontSize = 10.sp, color = Primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                if (isSearching) "搜索中…" else "$resultCount 张",
                fontSize = 11.sp, color = OnSurface.copy(alpha = 0.6f)
            )
            TextButton(onClick = { if (query.isNotBlank()) onExpandSearch() else onExpandFilters() }) {
                Text("调整", fontSize = 12.sp)
            }
            IconButton(onClick = onClearAll, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "清除全部条件", tint = OnSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ThinProgress(label: String, progress: Int, total: Int) {
    Surface(color = Surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, fontSize = 11.sp, color = OnSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = if (total > 0) progress.toFloat() / total else 0f,
                modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = ProgressTrack
            )
            Text("$progress / $total", fontSize = 10.sp, color = OnSurface.copy(alpha = 0.5f))
        }
    }
}

// ---------------------------------------------------------------------
// 搜索面板
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<String>,
    ruleDescription: String?,
    onClose: () -> Unit
) {
    // v3.8.0：输入框文本由 Compose 本地持有，按键立即回显，
    // 不经过 ViewModel StateFlow 往返，也就不会被后台检索或全屏重组阻塞。
    var text by rememberSaveable { mutableStateOf(query) }
    // 外部清除搜索（摘要栏 / 清空筛选）时同步本地文本
    LaunchedEffect(query) {
        if (query.isBlank() && text.isNotBlank()) text = ""
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("搜索照片内容", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ExpandLess, "收起", modifier = Modifier.size(20.dp))
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onQueryChange(it) },
            placeholder = { Text("输入内容、时间或地点，如：2023年 北京 汽车", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (text.isNotBlank()) IconButton(onClick = { text = ""; onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "清空", modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        // v3.7.0：明确、统一的时间写法提示
        Text(
            TimeQueryParser.TIME_QUERY_HINT,
            fontSize = 11.sp,
            color = Primary,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp)
        )

        if (ruleDescription != null && text.isNotBlank()) {
            Text("匹配规则：$ruleDescription", fontSize = 11.sp, color = OnSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }

        Text("快捷搜索", fontSize = 11.sp, color = OnSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(suggestions) { s ->
                SuggestionChip(
                    onClick = { text = s; onQueryChange(s) },
                    label = { Text(s, fontSize = 12.sp) },
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = ChipBackground, labelColor = ChipText
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// 时间 / 地点筛选面板
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPanel(
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
    onClearFilters: () -> Unit,
    onClose: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var locationText by remember { mutableStateOf(locationQuery?.raw ?: "") }

    LaunchedEffect(locationQuery?.raw) { locationText = locationQuery?.raw ?: "" }
    LaunchedEffect(filterSummary) {
        if (filterSummary == null) {
            customStart = ""; customEnd = ""; customError = null; locationText = ""
        }
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("筛选条件", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                customStart = ""; customEnd = ""; customError = null; locationText = ""
                onClearFilters()
            }) { Text("重置", fontSize = 12.sp) }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ExpandLess, "收起", modifier = Modifier.size(20.dp))
            }
        }

        if (filterSummary != null) {
            Surface(shape = RoundedCornerShape(6.dp), color = Primary.copy(alpha = 0.10f)) {
                Text("当前：$filterSummary", fontSize = 11.sp, color = Primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        // -------- 时间 --------
        SectionTitle(Icons.Default.DateRange, "拍摄时间")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { TimePresetChip("全部", timeRange == null) { onTimeRangeChange(null) } }
            item { TimePresetChip("今年", timeRange?.label == "今年") { onTimeRangeChange(TimeQueryParser.thisYear(now)) } }
            item { TimePresetChip("去年", timeRange?.label == "去年") { onTimeRangeChange(TimeQueryParser.lastYear(now)) } }
            item { TimePresetChip("最近3个月", timeRange?.label == "最近3个月") { onTimeRangeChange(TimeQueryParser.recentMonths(3, now)) } }
            item { TimePresetChip("最近1年", timeRange?.label == "最近1年") { onTimeRangeChange(TimeQueryParser.recentMonths(12, now)) } }
            item { TimePresetChip("最近30天", timeRange?.label == "最近30天") { onTimeRangeChange(TimeQueryParser.recentDays(30, now)) } }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = customStart,
                onValueChange = { customStart = it; customError = null },
                label = { Text("开始日期", fontSize = 11.sp) },
                placeholder = { Text("2023-06-01", fontSize = 11.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = customEnd,
                onValueChange = { customEnd = it; customError = null },
                label = { Text("结束日期", fontSize = 11.sp) },
                placeholder = { Text("2023-12-31", fontSize = 11.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    val r = TimeQueryParser.fromDateStrings(customStart.trim(), customEnd.trim())
                    if (r == null) customError = "日期格式不正确：${TimeQueryParser.DATE_FORMAT_HINT}"
                    else { customError = null; onTimeRangeChange(r) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)
            ) { Text("应用", fontSize = 12.sp) }
        }
        // v3.7.0：格式说明常驻，避免用户按错误格式填写
        Text(TimeQueryParser.DATE_FORMAT_HINT, fontSize = 10.sp,
            color = OnSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        if (customError != null) {
            Text(customError.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        } else if (timeRange != null) {
            Text("当前区间：${TimeQueryParser.displayLabel(timeRange)}", fontSize = 11.sp,
                color = Primary, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        // -------- 地点 --------
        SectionTitle(Icons.Default.Place, "拍摄地点（支持模糊）")
        OutlinedTextField(
            value = locationText,
            onValueChange = { v ->
                locationText = v
                onLocationQueryChange(if (v.isBlank()) null else buildLocationQuery(v, availablePlaces))
            },
            placeholder = { Text("输入或选择：北京 / 深圳 / 西湖…", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Place, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (locationText.isNotBlank()) IconButton(onClick = {
                    locationText = ""; onLocationQueryChange(null)
                }) { Icon(Icons.Default.Clear, null, modifier = Modifier.size(18.dp)) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        if (availablePlaces.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("已记录的地点（随时间区间联动）", fontSize = 10.sp,
                color = OnSurface.copy(alpha = 0.55f))
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(availablePlaces) { s ->
                    SuggestionChip(
                        onClick = { locationText = s.label; onSelectPlace(s) },
                        label = { Text("${s.label} · ${s.count}", fontSize = 11.sp) },
                        shape = CircleShape,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = ChipBackground, labelColor = ChipText
                        )
                    )
                }
            }
        } else if (!isExtractingExif) {
            Text("还没有可用的地点信息。照片若不含 GPS，可在照片详情页手动标注地点。",
                fontSize = 10.sp, color = OnSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp))
        }

        if (isExtractingExif) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (exifTotal > 0) exifProgress.toFloat() / exifTotal else 0f,
                modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = ProgressTrack
            )
            Text("正在读取照片的时间与地点 $exifProgress / $exifTotal", fontSize = 10.sp,
                color = OnSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
        }
        if (timeRange != null && noTimeCount > 0) {
            Text("$noTimeCount 张照片缺少拍摄时间，未纳入时间筛选", fontSize = 10.sp,
                color = OnSurface.copy(alpha = 0.5f))
        }
        if (locationQuery != null && noLocationCount > 0) {
            Text("$noLocationCount 张照片没有地点信息，可在详情页手动标注", fontSize = 10.sp,
                color = OnSurface.copy(alpha = 0.5f))
        }
        Text("提示：时间、地点与搜索词按「同时满足」联动", fontSize = 10.sp,
            color = OnSurface.copy(alpha = 0.45f), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Icon(icon, null, tint = Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OnSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        shape = CircleShape,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (selected) Primary.copy(alpha = 0.18f) else ChipBackground,
            labelColor = if (selected) Primary else ChipText
        )
    )
}

private fun buildLocationQuery(text: String, places: List<PlaceSuggestion>): LocationQuery {
    val t = text.trim()
    val place = PlaceCatalog.findByQuery(t)
    if (place != null) return PlaceCatalog.queryFor(place, t)
    val n = PlaceCatalog.normalize(t)
    val known = places.firstOrNull { s ->
        val sn = PlaceCatalog.normalize(s.label)
        sn.length >= 2 && n.length >= 2 && (sn.contains(n) || n.contains(sn))
    }
    return LocationQuery(raw = t, normalized = n,
        centerLat = known?.lat, centerLon = known?.lon, radiusKm = 60.0)
}

// ---------------------------------------------------------------------
// 分析状态细条
// ---------------------------------------------------------------------

@Composable
private fun AnalysisStatusBar(
    isAnalyzing: Boolean,
    hasAnalyzed: Boolean,
    progress: Int,
    total: Int,
    photoCount: Int,
    canResume: Boolean,
    savedProgress: Int,
    savedTotal: Int,
    doneCount: Int,
    pendingCount: Int,
    isExtractingExif: Boolean,
    exifProgress: Int,
    exifTotal: Int,
    onStartAnalysis: () -> Unit,
    onResumeAnalysis: () -> Unit,
    onRestartAnalysis: () -> Unit,
    onOpenHelp: () -> Unit
) {
    when {
        // v3.9.0：进度口径是「相册里已完整分析的张数」，不是本轮处理序号
        isAnalyzing -> {
            val remain = (total - progress).coerceAtLeast(0)
            ThinProgress(
                if (remain > 0) "正在分析：已完成 $progress / $total，还剩 $remain 张（可随时退出，下次自动继续）"
                else "正在收尾…",
                progress, total
            )
        }
        canResume -> Surface(color = Surface) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("已分析 $doneCount 张，还有 $pendingCount 张待分析", fontSize = 11.sp,
                        color = OnSurface.copy(alpha = 0.8f))
                    Text("下次进入应用会自动继续，不会从头开始", fontSize = 10.sp,
                        color = OnSurface.copy(alpha = 0.55f))
                }
                TextButton(onClick = onRestartAnalysis,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("全部重来", fontSize = 11.sp)
                }
                Button(onClick = onResumeAnalysis,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("继续", fontSize = 11.sp) }
            }
        }
        !hasAnalyzed && photoCount > 0 -> Surface(color = Surface) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("尚未分析照片内容，无法按内容搜索", fontSize = 11.sp,
                    color = OnSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenHelp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("怎么用", fontSize = 11.sp)
                }
                Button(onClick = onStartAnalysis,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("开始分析", fontSize = 11.sp) }
            }
        }
        isExtractingExif -> Surface(color = Surface) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("正在读取照片信息 $exifProgress / $exifTotal", fontSize = 10.sp,
                    color = OnSurface.copy(alpha = 0.55f))
            }
        }
        // 分析完成且无待分析照片：不占任何空间，把屏幕留给照片
        else -> Unit
    }
}

// ---------------------------------------------------------------------
// 照片集合：网格 / 列表
// ---------------------------------------------------------------------

@Composable
private fun PhotoCollection(
    photos: List<PhotoItem>,
    listMode: Boolean,
    exifMap: Map<Long, PhotoExif>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onPhotoClick: (PhotoItem) -> Unit,
    onPhotoLongClick: (PhotoItem) -> Unit,
    onPhotoSelect: (Long) -> Unit
) {
    if (listMode) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(photos, key = { it.id }) { photo ->
                PhotoListItem(
                    photo = photo,
                    exif = exifMap[photo.id],
                    selectionMode = selectionMode,
                    isSelected = selectedIds.contains(photo.id),
                    onClick = {
                        if (selectionMode) onPhotoSelect(photo.id) else onPhotoClick(photo)
                    },
                    onLongClick = { onPhotoLongClick(photo) }
                )
            }
        }
    } else {
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
                        if (selectionMode) onPhotoSelect(photo.id) else onPhotoClick(photo)
                    },
                    onLongClick = { onPhotoLongClick(photo) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoListItem(
    photo: PhotoItem,
    exif: PhotoExif?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    // v3.8.0：显式指定采样尺寸，避免几万张照片滚动时解码原图造成内存抖动
    val context = LocalContext.current
    val imageRequest = remember(photo.uri) {
        ImageRequest.Builder(context).data(photo.uri).size(176).crossfade(false).build()
    }
    val fallbackTime = if (photo.dateTaken > 0L && photo.dateTaken < 100_000_000_000L)
        photo.dateTaken * 1000L else photo.dateTaken
    val timeText = when {
        exif != null && exif.hasTime -> exif.dateTimeLabel()
        fallbackTime > 0L -> dateFormat.format(Date(fallbackTime))
        else -> "拍摄时间未知"
    }
    val placeText = exif?.placeLabel()?.takeIf { it.isNotBlank() } ?: "地点未知"
    val infoLine = buildList {
        exif?.formatLabel()?.takeIf { it.isNotBlank() }?.let { add(it) }
        exif?.dimensionLabel()?.takeIf { it.isNotBlank() }?.let { add(it) }
        exif?.fileSizeLabel()?.takeIf { it.isNotBlank() }?.let { add(it) }
        exif?.cameraLabel()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ").ifBlank { "格式信息未知" }

    val cnTags = photo.metadata?.chineseDisplayTags(4).orEmpty().ifEmpty {
        exif?.displayTags()?.take(4).orEmpty()
    }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.10f) else Surface
        )
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Box {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
                )
                if (selectionMode) {
                    Box(
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Primary else Color.White.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(photo.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                InfoLine(Icons.Default.DateRange, timeText)
                InfoLine(Icons.Default.Place, placeText)
                InfoLine(Icons.Default.PhotoLibrary, infoLine)
                if (cnTags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(cnTags) { tag ->
                            Surface(shape = RoundedCornerShape(10.dp), color = ChipBackground) {
                                Text(tag, fontSize = 10.sp, color = ChipText,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Icon(icon, null, tint = OnSurface.copy(alpha = 0.45f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = OnSurface.copy(alpha = 0.75f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.15f) else Color.White
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            val imageRequest = remember(photo.uri) {
                ImageRequest.Builder(context).data(photo.uri).size(256).crossfade(false).build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (selectionMode) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Primary else Color.White.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White,
                        modifier = Modifier.size(16.dp))
                }
            } else if (photo.isAnalyzed) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp)
                        .clip(CircleShape).background(Secondary)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, null, modifier = Modifier.size(56.dp),
                tint = OnSurface.copy(alpha = 0.25f))
            Spacer(Modifier.height(14.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = OnSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 32.dp))
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onAction?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}
