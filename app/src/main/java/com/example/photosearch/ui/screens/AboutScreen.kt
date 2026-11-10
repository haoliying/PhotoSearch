package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photosearch.ui.theme.*

/** v3.9.0：应用内版本说明。版本号从 PackageManager 读取，不硬编码。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { currentVersion(context) }
    var expandedTag by remember { mutableStateOf<String?>(RELEASES.first().tag) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("版本说明", color = Color.White) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前版本
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("智能相册搜索", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = OnSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("当前版本  $version", fontSize = 14.sp, color = Primary,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本地离线图像识别 + 可选云端 AI 描述，支持按内容、时间、地点进行自然语言检索。" +
                                "所有版本使用同一签名，下载新版 APK 可直接覆盖安装，无需卸载。",
                        fontSize = 12.sp, color = OnSurface.copy(alpha = 0.7f), lineHeight = 18.sp
                    )
                }
            }

            Text("版本历史", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = OnSurface)

            RELEASES.forEach { release ->
                ReleaseCard(
                    release = release,
                    expanded = expandedTag == release.tag,
                    onToggle = {
                        expandedTag = if (expandedTag == release.tag) null else release.tag
                    }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ChipBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("升级与降级", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ChipText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "· 覆盖安装：直接安装新版 APK，已分析的结果、EXIF 缓存与手动标注地点都会保留。\n" +
                                "· 回退版本：安装旧版 APK 同样可覆盖；若提示签名不一致，请先卸载再安装。\n" +
                                "· 分析结果保存在应用私有目录，卸载应用会一并清除。",
                        fontSize = 12.sp, color = OnSurface.copy(alpha = 0.8f), lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReleaseCard(
    release: ReleaseInfo,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${release.tag}  ${release.title}", fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = OnSurface)
                    Text(release.date, fontSize = 11.sp, color = OnSurface.copy(alpha = 0.55f))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = Primary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                release.points.forEach { point ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("· ", fontSize = 12.sp, color = Primary)
                        Text(point, fontSize = 12.sp, color = OnSurface.copy(alpha = 0.85f),
                            lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class ReleaseInfo(
    val tag: String,
    val date: String,
    val title: String,
    val points: List<String>
)

private val RELEASES = listOf(
    ReleaseInfo(
        "v3.10.0", "2026-11-10", "后台自动分析 · 相册监听 · 检索隔离",
        listOf(
            "智能分析改为后台常驻守护服务：跑完不退出，被系统回收后自动重启，开机 / 升级自动恢复，通知栏可随时停止",
            "监听系统相册变化：有新照片加入时自动增量分析并保存结果，已分析过的绝不重跑；新照片立即显示在相册里",
            "后台分析结果按批增量通知前端：只合并新增的几张、只失效这几张的索引缓存，绝不全量重算，搜索不受影响",
            "分析跑在低优先级独立线程、检索在独立线程池、主线程只更状态，三者互不抢占、无共享锁",
            "设置页可开关「后台自动分析」，关闭后回到手动开始 / 回到前台续跑模式；EXIF 也按增量提取",
            "磁盘仍是结果唯一权威，运行期事件丢失不影响正确性；新增离线评测验证增量合并与索引隔离"
        )
    ),
    ReleaseInfo(
        "v3.9.0", "2026-11-09", "断点续分析 · 版本说明与操作手册",
        listOf(
            "分析按 photoId 差集续跑：中断、退出、被系统终止后，下次只分析没跑过的照片，不再从头开始",
            "每张照片分析完立即单独落盘，最多丢当前一张；照片增删或排序变化也不会作废已有结果",
            "新增照片只增量分析；云端 AI 描述缺失的照片会在下次开启 AI 时自动补齐",
            "回到应用时可自动继续未完成的分析（设置里可关闭），首页显示「已分析 / 待分析」进度",
            "新增应用内「版本说明」与「操作手册」，设置页版本号改为动态读取",
            "新增离线评测：中断、续跑、新增照片、AI 补齐、重置全场景断言"
        )
    ),
    ReleaseInfo(
        "v3.8.0", "2026-11-09", "修复搜索假死 · 支持几万张照片",
        listOf(
            "搜索框改本地即时回显，检索 280ms 防抖后放后台线程并支持取消，输入不再丢字或 ANR",
            "检索索引跨查询 LRU 缓存复用、去掉逐次正则编译，3 万张照片冷启动检索从 17.5s 降到 2.2s",
            "EXIF 与分析结果改行式增量落盘，修掉每 25/200 张全量复制与全量 JSON 重写",
            "分析进度节流，分析期间只更新进度条；图片加载指定采样尺寸",
            "新增 3 万张照片性能基准评测（26 项断言）"
        )
    ),
    ReleaseInfo(
        "v3.7.0", "2026-11-08", "中文标签 · 界面重构",
        listOf(
            "AI 与本地识别标签全部中文化（约 300 条映射），检索仍兼容英文原词",
            "统一时间区间提示格式，支持 6 种日期写法与仅写年月的整月区间",
            "扩展 EXIF：相机/镜头/光圈/快门/ISO/焦距/闪光灯/白平衡/尺寸/格式等，生成中文可检索标签",
            "新增从左边缘右滑返回上一级，与系统返回键一致",
            "首页重构：查询条件收进折叠面板，照片区占满屏幕，支持缩略图网格与信息列表双视图"
        )
    ),
    ReleaseInfo(
        "v3.6.0", "2026-11-08", "EXIF 时间地点联动检索",
        listOf(
            "读取拍摄时间与 GPS，逆地理编码 + 内置城市表兜底，独立 EXIF 缓存不破坏 AI 结果",
            "支持时间区间、地点选择、地点模糊查询，时间与地点条件联动硬过滤",
            "详情页展示时间地点，无 GPS 照片可手动标注地点",
            "新增中文相对时间解析（最近三个月、去年夏天、国庆等）与约 80 个城市别名"
        )
    ),
    ReleaseInfo(
        "v3.5.2", "2026-11-07", "提升搜索成功率 / 召回率",
        listOf(
            "检索改概念通道 + 全文通道双路召回，中文 n-gram 与长词优先",
            "VLM 提示词改结构化 JSON 输出，修复 EXIF 旋转、失败状态与模型兼容",
            "首页新增「重新分析全部」，评测 F1 从 0.749 提升到 0.887"
        )
    ),
    ReleaseInfo(
        "v3.5.1", "2026-11-07", "修复相册照片不可见",
        listOf(
            "权限授权回调后重新扫描相册，修复换签名后权限清空导致列表为空",
            "增加 onResume 兜底重扫与「重新加载照片」「清除搜索」入口"
        )
    ),
    ReleaseInfo(
        "v3.5.0", "2026-11-07", "搜索准确度修复 · 首个签名发行版",
        listOf(
            "删除 2 字符模糊子串与双向 contains，改规范概念匹配，宏平均精确率 0.10 → 0.90",
            "修复排除条件反向加分、标签捏造等问题",
            "新增 GitHub Actions 自动构建签名 APK 与 Release 发布"
        )
    ),
    ReleaseInfo(
        "v3.1 及更早", "初始版本", "基础功能",
        listOf(
            "MediaStore 相册扫描、ML Kit 本地图像标签、可选云端大语言模型生成照片描述",
            "自然语言检索、时间与地点基础筛选、设置页与照片详情页"
        )
    )
)

/** 与设置页一致的动态版本号读取。 */
@Suppress("DEPRECATION")
private fun currentVersion(context: android.content.Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName} (${info.versionCode})"
    } catch (e: Exception) {
        "v3.10.0 (13)"
    }
}
