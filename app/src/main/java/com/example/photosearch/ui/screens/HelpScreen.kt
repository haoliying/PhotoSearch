package com.example.photosearch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photosearch.ui.theme.*

/**
 * v3.9.0：应用内操作手册。按章节折叠，支持关键词过滤，
 * 内容与仓库 docs/USER_MANUAL.md 保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(SECTIONS.first().id) }
    val sections = remember(query) {
        val q = query.trim()
        if (q.isBlank()) SECTIONS
        else SECTIONS.filter { s ->
            s.title.contains(q, ignoreCase = true) ||
                    s.bullets.any { it.contains(q, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("操作手册", color = Color.White) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索手册内容，如：断点、时间、地点", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, "清空", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Text(
                "共 ${SECTIONS.size} 个章节" + if (query.isNotBlank()) "，匹配 ${sections.size} 个" else "",
                fontSize = 11.sp, color = OnSurface.copy(alpha = 0.55f)
            )

            if (sections.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Text(
                        "没有找到「$query」相关内容，试试「分析」「时间」「地点」「EXIF」。",
                        fontSize = 13.sp, color = OnSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            sections.forEach { section ->
                HelpSectionCard(
                    section = section,
                    expanded = expandedId == section.id,
                    onToggle = { expandedId = if (expandedId == section.id) null else section.id }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HelpSectionCard(
    section: HelpSection,
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
                Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = OnSurface, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起" else "展开", tint = Primary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                section.bullets.forEach { bullet ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("· ", fontSize = 12.sp, color = Primary)
                        Text(bullet, fontSize = 12.sp, color = OnSurface.copy(alpha = 0.85f),
                            lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class HelpSection(val id: String, val title: String, val bullets: List<String>)

private val SECTIONS = listOf(
    HelpSection(
        "start", "一、快速开始（首次使用）",
        listOf(
            "1. 安装 APK 后首次打开，系统会请求「照片和视频」权限，请选择「始终允许所有照片」（Android 14+），否则相册为空。",
            "2. 可选：再允许「照片中的位置信息」（ACCESS_MEDIA_LOCATION），用于读取 GPS 地点；拒绝不影响时间检索与内容搜索。",
            "3. 允许通知权限可看到分析进度；拒绝也不影响功能。",
            "4. 首页顶部出现「尚未分析照片内容」时点「开始分析」；分析完成后才能按内容搜索。",
            "5. v3.11.0 起在「设置 → 大模型使用方式」选策略：不使用 / 全部照片 / 仅新增 / 仅识别不确定的照片（推荐）/ 仅手动选择的照片；非「不使用」时再选服务并填入 API Key（智谱 glm-4v-flash 免费），保存后生效。",
            "6. 不配 API Key 也能用：本地准确度增强会把主色、明暗、横竖幅、人数、文字、多场景与低置信标签变成可检索的中文标签，并自动扩展同义词（搜「汽车」能命中「车辆 / vehicle」）。复杂描述性查询仍建议开启云端 AI，详见「十一、大模型使用策略与本地准确度增强」。"
        )
    ),
    HelpSection(
        "analysis", "二、照片分析与断点续跑",
        listOf(
            "分析在后台服务中运行，通知栏显示「已完成 X / 总 Y，还剩 Z 张」。可以随时退出应用、锁屏或切换任务，不会丢失进度。",
            "v3.9.0 起每张照片分析完立即单独保存。若被系统终止、关机或应用被划掉，下次只分析没跑过的照片，绝不从头开始。",
            "回到应用时默认自动接着上次继续（设置 → 分析进度 → 自动继续未完成的照片分析，可关闭）。关闭后首页会显示「继续」按钮。",
            "首页状态条会显示「已分析 X 张，还有 Y 张待分析」；进度口径是相册里已完整分析的照片，不是本轮序号。",
            "新增照片（新拍的照片、刚下载的图片）只会增量分析新增的，已分析过的不会重跑；删除照片也不会影响其他照片的结果。",
            "云端 AI 限流或断网导致部分照片只有本地标签时，下次开启 AI 会自动补齐这些照片的中文描述，无需「重新分析全部」。",
            "「设置 → 进度标记刷新间隔」只控制进度数字多久写一次磁盘，不影响安全性；结果本身每张都立即保存。",
            "需要彻底重来：右上角更多菜单 →「重新分析全部」（会清空已有结果，请谨慎）。",
            "只想重跑少数照片：更多菜单 →「多选照片重新分析」，长按或点选照片后点「重新分析」；也可在详情页点「重新分析这张」。",
            "v3.10.0 起默认由后台守护自动接管：新照片自动分析、中断自动续跑，详见「三、后台自动分析与相册监听」。"
        )
    ),
    HelpSection(
        "background", "三、后台自动分析与相册监听（v3.10.0）",
        listOf(
            "开启「设置 → 后台运行 → 后台自动分析」后（默认开启），分析服务在后台常驻：退出应用、锁屏、切到别的应用都不会停止。",
            "服务监听系统相册：新拍的照片、刚下载或保存的图片会被自动发现，只分析这些新增照片并把结果保存下来；已经分析过的绝不重跑。",
            "新照片会立刻出现在相册列表（网格或列表视图），随后其 AI 描述与中文标签在后台分析完成后自动补齐，无需手动刷新。",
            "后台分析与前端搜索互不影响：分析跑在低优先级独立线程、检索在独立线程池；结果按批增量更新，只刷新新增照片的索引，不清空缓存、不全库重算，输入与滚动保持流畅。",
            "通知栏有一条低优先级「后台守护」通知，点「停止守护」可随时关闭；也可在设置页关掉开关，或在首页更多菜单切换。关闭后回到「手动开始分析 / 回到前台续跑」模式。",
            "开机或应用升级后会尝试自动恢复守护；少数系统限制后台启动，此时打开一次应用即恢复（首页显示「后台守护中」）。",
            "云端 AI 开启时，后台自动分析新照片也会调用模型、消耗流量；不想自动消耗流量可关闭「后台自动分析」，或关闭云端 AI 只用本地识别。",
            "分析结果逐张单独保存，守护被系统终止或强杀最多丢当前一张，下次自动从没跑过的继续；新增、删除、乱序照片都不会作废已有结果。"
        )
    ),
    HelpSection(
        "search", "四、搜索照片内容",
        listOf(
            "点顶栏放大镜图标展开搜索框，输入后即时显示；检索在后台执行，连续快速输入不会卡顿或丢字。",
            "直接写内容词：「汽车」「有猫的照片」「海边日落」「两个人吃饭」「光膀子的男人」「发票」「自行车」。",
            "支持中英文混输，例如输入 car 也能搜到汽车照片；分析出的标签在界面上统一显示为中文。",
            "排除条件：写「没有汽车的风景」「不含建筑物」，排除词不会被当成命中词反向加分。",
            "多个条件用「和 / 或 / 空格」连接，例如「猫 或 狗」「北京 汽车」。",
            "点搜索框下方的建议词可直接填入；顶部摘要栏会显示当前条件与结果数，点「调整」可修改，点 × 清除全部。"
        )
    ),
    HelpSection(
        "time", "五、按时间检索",
        listOf(
            "在搜索框里直接写时间，或在「时间与地点筛选」面板里设定，两种方式可以叠加。",
            "支持的写法：2023年、2023年6月、2023-06-01、2023/6/1、2023.6.1、20230601。",
            "日期区间：2023-01-01 到 2023-06-30、2023年6月1日 至 2023年12月31日（起止均为闭区间）。",
            "相对时间：最近三个月、最近一年、去年、去年夏天、今年春天、国庆、春节等。",
            "只写年月会自动扩展为整月；筛选面板提供「最近 7 天 / 30 天 / 今年 / 去年」等快捷按钮与自定义开始、结束日期。",
            "提示格式统一为「2023年6月1日 至 2023年12月31日」；若照片没有拍摄时间，会在筛选面板提示「N 张无时间信息」。"
        )
    ),
    HelpSection(
        "place", "六、按地点检索与时间地点联动",
        listOf(
            "搜索框里写城市名即可：「北京」「上海」「杭州」「无锡」，支持别名、拼音与模糊写法（如「帝都」「魔都」）。",
            "筛选面板会列出当前时间区间内出现过的地点候选及照片数量，点一下即可选中；候选会随时间区间变化重新统计，这就是「时间地点联动」。",
            "也可以手动输入地点并点「使用此地点」，支持不精确的名称；系统按城市坐标半径与文本模糊两种方式匹配。",
            "组合查询示例：2023年 北京 汽车、最近三个月 上海 咖啡、去年夏天 海边 日落。时间、地点、内容三者同时生效（AND）。",
            "没有 GPS 的照片：在详情页「地点」处手动标注一个地点，之后即可参与地点检索。",
            "若某张照片缺少时间或地点信息，筛选面板会分别提示数量；放宽条件或清除筛选可让它们重新出现。"
        )
    ),
    HelpSection(
        "view", "七、浏览照片：网格与列表",
        listOf(
            "顶栏第三个图标在「缩略图网格」与「信息列表」之间切换，偏好会被记住。",
            "网格视图为纯缩略图，适合快速浏览；列表视图会在每张照片旁显示拍摄时间、地点、文件格式、分辨率、文件大小、相机与中文内容标签。",
            "列表中的时间地点来自 EXIF / MediaStore，缺失时显示「未知」；手动标注的地点会带「手动」标记。",
            "长按任意照片进入多选模式，可全选后批量重新分析；点顶栏 × 退出多选。"
        )
    ),
    HelpSection(
        "detail", "八、照片详情与 EXIF 信息",
        listOf(
            "点照片进入详情页，可查看 AI 中文描述、内容标签、识别物体、拍摄时间地点、拍摄参数与文件信息。",
            "拍摄参数包括：相机厂商/型号、镜头、光圈、快门、ISO、焦距、等效焦距、曝光补偿、闪光灯、白平衡、方向、像素尺寸、GPS 海拔与时间、MIME / 大小 / 扩展名。",
            "这些参数会生成中文可检索标签（如「佳能」「竖幅」「广角」「大光圈」「JPEG」「春季」），可直接当搜索词用。",
            "详情页底部列出「可检索标签」，点「分享」可把照片发给其他应用，点「重新分析这张」可单独刷新。",
            "从屏幕左边缘向右滑，或按系统返回键，即可回到上一级；从详情页、设置页、版本说明、操作手册都支持。"
        )
    ),
    HelpSection(
        "faq", "九、常见问题与降级处理",
        listOf(
            "看不到相册照片：确认已授予「照片和视频」权限；点更多菜单 →「重新加载相册」；Android 14 需选择「始终允许所有照片」。仍为空时重启一次应用。",
            "搜索不到想要的结果：先确认照片已分析完成；换更具体的名词（「轿车」比「车」更准）；确认设置页「启用本地准确度增强」为开启；也可把「大模型使用方式」改为「仅识别不确定的照片」让 AI 补短板；检查是否叠加了时间/地点筛选，点「清除全部条件」后再试。",
            "不想让照片联网 / 想省流量：设置 →「大模型使用方式」选「不使用大模型」或「仅手动选择的照片」；非 OFF 策略下可再开「仅 Wi-Fi 下使用大模型」。",
            "分析中断 / 每次从头：v3.9.0 已按 photoId 断点续跑。若仍是旧版本请升级；升级后点一次「继续」即可，已分析的照片不会重跑。",
            "云端 AI 报错或很慢：检查设置里的服务与 API Key、网络是否正常；连续失败 20 次会本轮自动降级为本地分析，下次进入应用再自动补齐，不会卡死。",
            "照片很多（几万张）时卡顿：v3.8.0 起检索已后台异步化并带缓存，输入与滚动不受影响；首次分析仍需要时间，建议分批进行或在网络良好时开启 AI。",
            "不用大模型时分析仍慢：v3.11.2 起默认 4 路滑动窗口并发（可在「设置 → 分析速度」调到 8 路），且解码尺寸优化过；若还是慢，确认没有误把并发设成 1 路。速度与是否使用大模型无关，两条通道都提速。",
            "退到后台就不分析了 / 进度很久不动：v3.11.2 起分析时持有 CPU 唤醒锁，熄屏不再被冻结，并有 60 秒低频心跳对账；通知栏会实时显示「正在分析 x / y」。部分国产 ROM 仍会限制后台，请到「设置 → 打开电池优化设置」把本应用设为不受限制。",
            "进度数字一次跳几百：v3.11.2 已把进度改为服务侧逐张累加的单一数据源，不再跳变；若仍看到旧版行为请确认已升级到 v3.11.2。",
            "照片两万多张、打开等一会儿就闪退：这是 v3.11.3 专门修复的问题（见第十三章）。请升级到 v3.11.3 及以上；升级不会重跑已有分析。若仍闪退，先在设置页把「分析速度」调到 1 路、或首页点「暂停分析」，再反馈具体机型与系统版本。",
            "想临时停止分析：首页状态条点「暂停分析」，或下拉通知栏点「暂停分析」；想恢复点「继续分析」，会按已分析结果从剩余照片续跑，不会从头开始。暂停状态会被记住，重启手机后也不会偷偷恢复。",
            "换版本安装：所有发行版使用同一签名，直接覆盖安装即可保留分析结果；若提示签名冲突，先卸载再安装（会清空分析缓存）。",
            "清缓存 / 重置：更多菜单 →「重新分析全部」会清空 AI 结果与进度；卸载应用会清除全部私有数据（EXIF 缓存、手动标注地点、设置）。"
        )
    ),
    HelpSection(
        "privacy", "十、数据与隐私说明",
        listOf(
            "照片本身不会被上传；只有在「大模型使用方式」非「不使用」、且该照片被策略选中时，才会把这张照片发送给设置中指定的模型服务生成描述。",
            "分析结果、EXIF 信息、手动标注地点与设置都保存在应用私有目录（filesDir），不上传、不共享。",
            "选「不使用大模型」后，全部识别都在设备本地完成（ML Kit + 本地增强），可完全离线使用。",
            "分析进度按行式文件逐张追加，意外断电或强杀最多丢失当前正在处理的那一张。"
        )
    ),
    HelpSection(
        "vlm_policy", "十一、大模型使用策略与本地准确度增强（v3.11.0）",
        listOf(
            "设置 →「大模型使用方式」共 5 种策略：不使用大模型（纯端侧、不联网）、全部照片、仅新增 / 未分析的照片、仅识别不确定的照片（推荐）、仅手动选择的照片。旧版「启用云端 AI 分析」开关会自动迁移。",
            "选「仅识别不确定的照片」时可调整「识别可信度阈值」（默认 55%）：端侧识别标签少、置信度低、场景不明确的照片才会调用大模型，数值越大交给大模型的照片越多。",
            "「仅 Wi-Fi 下使用大模型」：移动数据下自动跳过云端分析，连上 Wi-Fi 后由后台守护补上，避免偷跑流量。",
            "只让指定照片用大模型：首页更多菜单 →「多选照片」→ 勾选 → 底部点「用大模型分析」。底部另有「重新分析」按钮，只跑端侧识别、不联网。手动勾选在任何非「不使用」策略下都会被强制精修，分析完自动从待办移除。",
            "不用大模型时准确度靠本地增强：把端侧已算出但过去被浪费的主色、亮度、饱和度、横竖幅、全景、单双人 / 合影 / 人群、文字、多场景、0.15~0.30 低置信标签编成中文可检索标签。",
            "查询侧自动扩展同义词：搜「汽车」能命中标签为「车辆 / 轿车 / vehicle / car」的照片；搜「合影」能命中「合照 / 一群人」。多条互相印证的弱证据还能叠加生效。",
            "本地增强是纯函数、从已落盘字段实时推导，因此升级后历史分析结果**无需重跑**即刻生效；不联网、不消耗额度。想对比效果，在设置页关掉「启用本地准确度增强」并保存，检索行为会回到 v3.10.0。",
            "推荐组合：完全离线选「不使用」+ 本地增强开启；想省流量选「仅识别不确定的照片」+「仅 Wi-Fi」；只精修少数几张选「仅手动选择的照片」。"
        )
    ),
    HelpSection(
        "speed", "十二、分析速度、后台持续分析与暂停 / 继续（v3.11.2）",
        listOf(
            "为什么之前调了并发还是慢：v3.11.1 是「整块并行、整块收尾」，一块里最慢的照片会拖住同块其他线程（木桶效应）。v3.11.2 改成滑动窗口：固定 N 张同时分析，任何一张完成立刻补下一张，全程保持 N 张在跑；并发上限 1~8，默认 4。",
            "分析跑在低优先级专用线程池，与搜索用的线程隔离，所以全速分析时输入搜索框仍不卡；解码最大边长从 1024 降到 768，ML Kit 内部本来就会缩放到 224~320 再推理，标签精度几乎不变。",
            "后台为什么停：Android 熄屏后会让 CPU 休眠，前台服务虽不被杀但计算被冻结，表现就是「一退到后台进度就不动」。v3.11.2 分析期间持有 CPU 唤醒锁（单次最长 30 分钟，跑完/暂停即释放），并在空闲时每 60 秒做一次轻量对账，新照片即使没触发相册监听也会被捡起来分析。",
            "进度条口径：只统计「相册里已完整分析的张数」，由服务逐张 +1 发布，前端不再自己全库统计；数字只增不减，200ms 更新一次，收尾必发最终值，所以不会再一次跳几百。",
            "暂停分析：首页状态条或通知栏点「暂停分析」。守护仍在运行（相册监听、通知都在），正在处理的照片会先落盘再挂起，已分析结果不丢；暂停状态写入设置，服务被系统回收重建后仍保持暂停。",
            "继续分析：点「继续分析」，服务立刻结束心跳等待重新对账，按 photoId 差集只处理剩余照片，不会重跑已完成的。若守护被系统关掉，继续时会自动重新拉起。",
            "设置项：「设置 → 分析速度」有 1 / 2 / 4 / 8 四档，只影响之后的分析；发烫或前台有感知就退回 1 路（等价旧版逐张串行）。旁边有「打开电池优化设置」入口，国产 ROM 需手动允许后台运行。"
        )
    ),
    HelpSection(
        "large_album", "十三、大相册（2 万张以上）稳定性保护（v3.11.3）",
        listOf(
            "现象：照片 25000 张左右，打开应用后过一会儿闪退（不是一打开就崩）。原因是默认后台守护会自动拉起全量分析，分析跑起来后内存持续累积到上限。",
            "根因一（主因）：旧版每收到一批分析结果（20 张 / 300ms）就把 25000 张照片列表整体复制一次，一次全量分析要复制上千次，界面框架同时持有新旧两份大列表。现在改为增量累积、大相册每 1.5 秒或累计 200 张才合并一次，分配量降低约一个数量级；分析收尾会强制合并残余，结果不丢。",
            "根因二：后台服务过去每一轮对账都把 25000 行分析结果 JSON 重新解析一遍。现在只在首轮读一次磁盘，之后复用进程内镜像；任务与结果通道改为有界背压，任何时刻在途的照片数受控。",
            "根因三：4 路并发位图 + 内存不足时旧代码只捕获 Exception，抓不到 OutOfMemoryError，于是错误直接冒泡杀掉进程。现在位图在 finally 里必定回收，并为解码、单张分析、整批分析、服务循环分别加内存不足就地降级：自动降低并发、不崩进程，已落盘结果按差集续跑。",
            "并发自适应：照片超过 8000 张，或设备可用堆偏小时，实际并发会自动降到 2~4 路甚至 1 路（设置页选的是上限而非强制值）。这是正常保护，不是变慢的故障；等全量分析完成后，日常增量分析只剩少量新照片，影响很小。",
            "其他配套：通知栏刷新由 200ms 降到 900ms，减少高频系统事务；声明 largeHeap 作为兜底；服务协程加全局异常兜底，未捕获异常只记录日志并复位分析状态，不再杀进程。",
            "我不会丢失已有分析：所有结果仍是逐张立即落盘，磁盘是唯一权威。升级、暂停、降级、甚至被系统强杀后，下次打开都会按 photoId 差集补齐，不会从头重跑。",
            "如果仍闪退：先在首页点「暂停分析」停止后台任务，把「设置 → 分析速度」设为 1 路，然后反馈机型、Android 版本与照片数量。也可暂时关闭「后台自动分析」，改为手动点「开始分析」分批跑。"
        )
    )
)
