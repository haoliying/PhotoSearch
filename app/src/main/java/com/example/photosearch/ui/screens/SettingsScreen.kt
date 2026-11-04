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
import com.example.photosearch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: android.content.Context,
    onBack: () -> Unit
) {
    var apiKey by remember { mutableStateOf(AppConfig.getApiKey(context)) }
    var apiProvider by remember { mutableStateOf(AppConfig.getApiProvider(context)) }
    var vlmEnabled by remember { mutableStateOf(AppConfig.isVlmEnabled(context)) }
    var segmentSize by remember { mutableStateOf(AppConfig.getSegmentSize(context).toString()) }
    var saved by remember { mutableStateOf(false) }

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
                                text = "使用大语言模型（如 Gemini/GPT-4o）分析照片内容，大幅提升搜索准确度",
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
                            text = "AI 服务提供商",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = apiProvider == "gemini",
                                onClick = { apiProvider = "gemini" },
                                label = { Text("Google Gemini") }
                            )
                            FilterChip(
                                selected = apiProvider == "openai",
                                onClick = { apiProvider = "openai" },
                                label = { Text("OpenAI GPT-4o") }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // API Key input
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            placeholder = {
                                Text(
                                    if (apiProvider == "gemini")
                                        "输入 Google Gemini API Key"
                                    else
                                        "输入 OpenAI API Key"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (apiProvider) {
                                "gemini" -> "获取免费 Key: https://aistudio.google.com/apikey"
                                "openai" -> "获取 Key: https://platform.openai.com/api-keys"
                                else -> ""
                            },
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
                        text = "分析分段大小",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "每分析多少张照片保存一次进度（防止崩溃丢失）",
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

            // Save button
            Button(
                onClick = {
                    AppConfig.setVlmEnabled(context, vlmEnabled)
                    AppConfig.setApiProvider(context, apiProvider)
                    AppConfig.setApiKey(context, apiKey)
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
                Text(if (saved) "已保存" else "保存设置")
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
                        text = "智能相册搜索 v3.0",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "使用 Google ML Kit 进行本地图像识别，结合云端大语言模型（Gemini/GPT-4o）生成丰富的中文照片描述，实现高精度自然语言搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
