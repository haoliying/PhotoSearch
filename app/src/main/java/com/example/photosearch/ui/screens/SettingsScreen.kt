package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
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
import com.example.photosearch.data.AppConfig
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenHelp: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(AppConfig.getApiKey(context)) }
    var apiProvider by remember { mutableStateOf(AppConfig.getApiProvider(context)) }
    var vlmEnabled by remember { mutableStateOf(AppConfig.isVlmEnabled(context)) }
    var segmentSize by remember { mutableStateOf(AppConfig.getSegmentSize(context).toString()) }
    var autoResume by remember { mutableStateOf(AppConfig.isAutoResumeEnabled(context)) }
    var saved by remember { mutableStateOf(false) }

    val providers = VisionLanguageAnalyzer.PROVIDERS
    val selectedProvider = providers.find { it.id == apiProvider } ?: providers[0]
    val versionText = remember { appVersion(context) }

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
            // AI Analysis Settings
            Text(
                text = "AI 分析设置",
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
                    // VLM Enable/Disable
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用云端 AI 分析",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "使用大语言模型分析照片内容，大幅提升搜索准确度",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = vlmEnabled,
                            onCheckedChange = { vlmEnabled = it }
                        )
                    }

                    if (vlmEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(16.dp))

                        // Provider selection
                        Text(
                            text = "选择 AI 服务",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Provider chips in a column for readability
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            providers.forEach { provider ->
                                FilterChip(
                                    selected = apiProvider == provider.id,
                                    onClick = {
                                        apiProvider = provider.id
                                        saved = false
                                    },
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
                        // Show selected provider info
                        Text(
                            text = selectedProvider.freeInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // API Key input
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                saved = false
                            },
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

            // Storage settings
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
                    Text(
                        text = "进度标记刷新间隔",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
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

            // v3.9.0 断点续分析设置
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
                            Text(
                                text = "自动继续未完成的照片分析",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "回到应用时从上次剩余的照片接着分析，不会从头开始；新增照片也只分析新增的。关闭后可在首页点「继续」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = autoResume, onCheckedChange = {
                            autoResume = it
                            saved = false
                        })
                    }
                }
            }

            // Save button
            Button(
                onClick = {
                    AppConfig.setVlmEnabled(context, vlmEnabled)
                    AppConfig.setApiProvider(context, apiProvider)
                    AppConfig.setApiKey(context, apiKey)
                    AppConfig.setAutoResumeEnabled(context, autoResume)
                    val size = segmentSize.toIntOrNull()
                    if (size != null && size > 0) {
                        AppConfig.setSegmentSize(context, size)
                    }
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (saved) "已保存 ✓" else "保存设置")
            }

            // Info section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "智能相册搜索 $versionText",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "使用 ML Kit 本地图像识别 + 云端大语言模型生成中文照片描述，实现高精度自然语言搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "推荐：智谱AI 的 glm-4v-flash 模型完全免费，适合日常使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
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
        "v3.9.0"
    }
}
