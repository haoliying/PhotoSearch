package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.photosearch.data.PhotoItem
import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.data.SceneType
import com.example.photosearch.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoDetailScreen(
    photo: PhotoItem,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onReanalyze: () -> Unit = {},
    isReanalyzing: Boolean = false,
    reanalyzeError: String? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("照片详情", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, "分享", tint = Color.White)
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
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = photo.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text(
                        text = dateFormat.format(Date(photo.dateTaken)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface.copy(alpha = 0.6f)
                    )

                    val metadata = photo.metadata

                    if (metadata != null && metadata.isAnalyzed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // VLM description (highest priority info)
                        if (metadata.description.isNotBlank()) {
                            Text(
                                text = "AI 描述",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = metadata.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // VLM keywords
                            if (metadata.vlmKeywords.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    metadata.vlmKeywords.forEach { kw ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Primary.copy(alpha = 0.1f)
                                        ) {
                                            Text(
                                                text = kw,
                                                fontSize = 12.sp,
                                                color = Primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Divider()
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Scene type
                        if (metadata.sceneType != SceneType.UNKNOWN) {
                            InfoRow("场景类型", sceneTypeName(metadata.sceneType))
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Outdoor/Indoor
                        metadata.isOutdoor?.let { isOutdoor ->
                            InfoRow("环境", if (isOutdoor) "户外" else "室内")
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Detected objects
                        if (metadata.detectedObjects.isNotEmpty()) {
                            Text(
                                text = "识别物体",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                metadata.detectedObjects.forEach { obj ->
                                    LabelChip(obj.label, obj.confidence)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Face info
                        if (metadata.faces.isNotEmpty()) {
                            InfoRow("人脸数量", "${metadata.faces.size} 张人脸")
                            metadata.faces.forEachIndexed { index, face ->
                                val details = buildList {
                                    if (face.smilingProbability > 0.5f) add("微笑")
                                    if (face.leftEyeOpenProbability > 0.5f) add("左眼睁开")
                                    if (face.rightEyeOpenProbability > 0.5f) add("右眼睁开")
                                }
                                if (details.isNotEmpty()) {
                                    InfoRow("人脸 ${index + 1}", details.joinToString("、"))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Labels
                        if (metadata.labels.isNotEmpty()) {
                            Text(
                                text = "内容标签",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                metadata.labels.sortedByDescending { it.confidence }.forEach { label ->
                                    LabelChip(label.text, label.confidence)
                                }
                            }
                        }

                        // Dominant colors
                        if (metadata.dominantColors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "主色调",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                metadata.dominantColors.take(5).forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(color))
                                    )
                                }
                            }
                        }
                    } else if (photo.isAnalyzed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "未识别到详细信息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface.copy(alpha = 0.5f)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "照片尚未分析，请先进行智能分析",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface.copy(alpha = 0.5f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onShare,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("分享此照片")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onReanalyze,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isReanalyzing,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isReanalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在重新分析…")
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("重新分析此照片")
                        }
                    }

                    if (reanalyzeError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = reanalyzeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = OnSurface
        )
    }
}

@Composable
private fun LabelChip(text: String, confidence: Float) {
    val percentage = (confidence * 100).toInt()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ChipBackground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = ChipText,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${percentage}%",
                fontSize = 11.sp,
                color = ChipText.copy(alpha = 0.7f)
            )
        }
    }
}

private fun sceneTypeName(scene: SceneType): String = when (scene) {
    SceneType.OUTDOOR_NATURE -> "户外自然"
    SceneType.OUTDOOR_URBAN -> "城市建筑"
    SceneType.INDOOR_HOME -> "室内家居"
    SceneType.INDOOR_PUBLIC -> "室内公共场所"
    SceneType.INDOOR_EVENT -> "室内活动"
    SceneType.BEACH_SEA -> "海滩/海边"
    SceneType.MOUNTAIN_HILL -> "山景"
    SceneType.NIGHT_SCENE -> "夜景"
    SceneType.FOOD_DRINK -> "美食"
    SceneType.SPORT_EXERCISE -> "运动"
    SceneType.VEHICLE_SCENE -> "车辆"
    SceneType.ANIMAL_PET -> "动物/宠物"
    SceneType.PEOPLE_PORTRAIT -> "人物"
    SceneType.LANDSCAPE -> "风景"
    SceneType.UNKNOWN -> "未知"
}
