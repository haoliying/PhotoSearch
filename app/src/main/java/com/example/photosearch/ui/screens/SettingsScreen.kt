package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.Settings
import com.example.photosearch.data.AppConfig
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.search.VlmMode
import com.example.photosearch.ui.theme.*

/**
 * v3.11.0：把「启用云端 AI 分析」的单个开关升级为完整的大模型使用策略：
 * 可以选择使用 / 不使用，以及哪些照片交给大模型；并新增不依赖大模型的
 * 本地准确度增强开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onBackgroundWatchChanged: (Boolean) -> Unit = {},
    onVlmConfigChanged: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(AppConfig.getApiKey(context)) }
    var apiProvider by remember { mutableStateOf(AppConfig.getApiProvider(context)) }
    var modeId by remember { mutableStateOf(AppConfig.getVlmModeId(context)) }
    var thresholdPct by remember {
        mutableStateOf((AppConfig.getUncertainThreshold(context) * 100).toInt().toString())
    }
    var wifiOnly by remember { mutableStateOf(AppConfig.isVlmWifiOnly(context)) }
    var localEnhance by remember { mutableStateOf(AppConfig.isLocalEnhanceEnabled(context)) }
    var parallelism by remember { mutableStateOf(AppConfig.getAnalysisParallelism(context)) }
    var segmentSize by remember { mutableStateOf(AppConfig.getSegmentSize(context).toString()) }
    var autoResume by remember { mutableStateOf(AppConfig.isAutoResumeEnabled(context)) }
    var backgroundWatch by remember { mutableStateOf(AppConfig.isBackgroundWatchEnabled(context)) }
    var saved by remember { mutableStateOf(false) }

    val providers = VisionLanguageAnalyzer.PROVIDERS
    val selectedProvider = providers.find { it.id == apiProvider } ?: providers[0]
    val versionText = remember { appVersion(context) }
    val mode = VlmMode.fromId(modeId)
    val selectedCount = remember(modeId, saved) { AppConfig.getVlmSelectedIds(context).size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- 大模型使用方式 ----------------
            Text(
                text = "大模型使用方式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "选择是否使用云端大模型，以及哪些照片交给它分析。关闭后完全走端侧识别，不联网、不消耗流量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    VlmMode.values().forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { modeId = m.id; saved = false }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = modeId == m.id,
                                onClick = { modeId = m.id; saved = false }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (modeId == m.id) FontWeight.Bold else FontWeight.Medium
                                )
                                if (modeId == m.id) {
                                    Text(
                                        text = m.hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    // UNCERTAIN 专属：可信度阈值
                    if (mode == VlmMode.UNCERTAIN) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "识别可信度阈值（%）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "端侧识别可信度低于此值的照片才会调用大模型。数值越大，交给大模型的照片越多。",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = thresholdPct,
                            onValueChange = { thresholdPct = it.filter { c -> c.isDigit() }.take(3); saved = false },
                            label = { Text("阈值 0 - 100") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    // SELECTED 专属：手动选择说明
                    if (mode == VlmMode.SELECTED) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "当前待大模型分析的照片：$selectedCount 张",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Primary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "回到相册 → 右上角菜单「多选照片」→ 勾选后点底部「用大模型分析所选」。无论当前是哪种策略，手动勾选的照片都会被强制精修。",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("仅 Wi-Fi 下使用大模型", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后移动数据下自动跳过云端分析，避免偷跑流量；后台守护会在连上 Wi-Fi 后补上。",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; saved = false })
                    }
                }
            }

            // ---------------- 不依赖大模型的准确度增强 ----------------
            Text(
                text = "本地识别准确度（无需大模型）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用本地准确度增强", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                "把端侧已经算出但过去被浪费的信号（主色 / 明暗 / 横竖幅 / 人数 / 文字 / 多场景 / 低置信标签）编成中文标签，并在查询时扩展同义词。完全离线、不联网，升级后旧分析结果无需重跑即可生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = localEnhance, onCheckedChange = { localEnhance = it; saved = false })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "关闭后检索行为与 v3.10.0 完全一致，可用于对比准确度。详细原理见「操作手册」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary
                    )
                }
            }

            // ---------------- 分析速度 / 后台持续分析（v3.11.2） ----------------
            Text(
                text = "分析速度（端侧识别）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "v3.11.2：改为滑动窗口真并发——固定 N 张同时分析，谁完成立刻补下一张，不再等整批最慢的那张；并发上限提高到 8。熄屏后持有 CPU 唤醒锁并做低频心跳对账，退到后台也会持续分析新照片。首页与通知栏都提供「暂停 / 继续分析」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            1 to "1 路（省电）",
                            2 to "2 路",
                            4 to "4 路（默认）",
                            8 to "8 路（极速）"
                        ).forEach { (p, label) ->
                            FilterChip(
                                selected = parallelism == p,
                                onClick = { parallelism = p; saved = false },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "默认 4 路。只影响之后的分析速度，既有分析结果不受影响；是否使用大模型与此无关。若某档导致发热，退回 1 路即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        } catch (_: Exception) { }
                    }) {
                        Text("后台仍被系统限制？打开电池优化设置", fontSize = 12.sp)
                    }
                    Text(
                        "部分手机（尤其国产 ROM）会额外限制后台运行，请在系统设置里把本应用设为「不受电池优化限制 / 允许后台运行」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.55f)
                    )
                }
            }

            // ---------------- 云端服务配置（仅策略非 OFF 时显示） ----------------
            if (mode != VlmMode.OFF) {
                Text(
                    text = "云端服务配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择 AI 服务", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            providers.forEach { provider ->
                                FilterChip(
                                    selected = apiProvider == provider.id,
                                    onClick = { apiProvider = provider.id; saved = false },
                                    label = {
                                        Text(
                                            text = provider.displayName,
                                            fontSize = if (apiProvider == provider.id)
                                                MaterialTheme.typography.bodyMedium.fontSize
                                            else MaterialTheme.typography.bodySmall.fontSize
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = selectedProvider.freeInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; saved = false },
                            label = { Text("API Key") },
                            placeholder = { Text("粘贴你的 API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "获取 API Key: ${selectedProvider.apiKeyUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary
                        )
                    }
                }
            }

            // ---------------- 存储设置 ----------------
            Text(
                text = "存储设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("进度标记刷新间隔", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        text = "v3.9.0 起每张照片分析完立即单独保存，退出或中断都不会丢进度。此项只控制进度数字多久写一次，不影响安全性。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = segmentSize,
                        onValueChange = { segmentSize = it.filter { c -> c.isDigit() } },
                        label = { Text("分段大小") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            // ---------------- 后台运行 ----------------
            Text(
                text = "后台运行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台自动分析（守护常驻）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "开启后分析服务在后台常驻并监听相册：新拍或新下载的照片会自动增量分析并保存结果，无需手动点「开始分析」。后台分析跑在低优先级线程、只增量更新，不会打断前端搜索。关闭后回到「手动开始 / 回到前台续跑」模式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = backgroundWatch, onCheckedChange = {
                            backgroundWatch = it
                            saved = false
                            AppConfig.setBackgroundWatchEnabled(context, it)
                            onBackgroundWatchChanged(it)
                        })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "系统通知栏会显示一条低优先级「后台守护」通知，可点「停止守护」随时关闭；开机后自动恢复（部分系统需打开一次应用）。云端 AI 开启时后台自动分析会消耗流量，请按需使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // ---------------- 分析进度 ----------------
            Text(
                text = "分析进度",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动继续未完成的照片分析", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "守护关闭时生效：回到应用从上次剩余的照片接着分析，不会从头开始。开启上面的「后台自动分析」后由守护自动接管（推荐）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = autoResume, onCheckedChange = { autoResume = it; saved = false })
                    }
                }
            }

            // ---------------- 保存 ----------------
            Button(
                onClick = {
                    AppConfig.setVlmModeId(context, modeId)
                    AppConfig.setApiProvider(context, apiProvider)
                    AppConfig.setApiKey(context, apiKey)
                    thresholdPct.toIntOrNull()?.let {
                        AppConfig.setUncertainThreshold(context, it.coerceIn(0, 100) / 100f)
                    }
                    AppConfig.setVlmWifiOnly(context, wifiOnly)
                    AppConfig.setLocalEnhanceEnabled(context, localEnhance)
                    AppConfig.setAnalysisParallelism(context, parallelism)
                    AppConfig.setAutoResumeEnabled(context, autoResume)
                    AppConfig.setBackgroundWatchEnabled(context, backgroundWatch)
                    val size = segmentSize.toIntOrNull()
                    if (size != null && size > 0) AppConfig.setSegmentSize(context, size)
                    saved = true
                    onVlmConfigChanged()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (saved) "已保存并生效 ✓" else "保存设置")
            }

            // ---------------- 关于 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("智能相册搜索 $versionText", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ML Kit 端侧识别 + 本地视觉/语义增强，可按策略叠加云端大模型精修；两者都能独立工作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenHelp) { Text("操作手册") }
                        TextButton(onClick = onOpenAbout) { Text("版本说明") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 从 PackageManager 读取真实版本号，避免设置页硬编码版本随迭代漂移。 */
@Suppress("DEPRECATION")
private fun appVersion(context: android.content.Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName} (${info.versionCode})"
    } catch (e: Exception) {
        "v3.11.2"
    }
}
