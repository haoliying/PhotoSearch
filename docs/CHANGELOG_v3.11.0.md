# v3.11.0 更新说明

> 发布日期：2026-11-11 · versionCode 14 · 包名 `com.example.photosearch`
> 主题：**大模型可以用 / 可以不用、可以挑照片用 + 不依赖大模型的准确度增强**
> 详细原理：[ACCURACY_WITHOUT_VLM.md](ACCURACY_WITHOUT_VLM.md)

---

## 一、本次解决的三个问题

1. **原来只有一个「启用云端 AI 分析」开关**，且一旦开启，所有「本地已分析但没有 AI 描述」的存量照片都会被排进补描述队列 —— 几万张照片全部走一遍云端 API，用户既不能完全关掉，也不能挑选哪些照片交给大模型。
2. **关掉大模型后识别不准**：端侧 ML Kit 算出的主色、亮度、构图、人数、文字、多场景、低置信标签大部分没进检索索引，查询侧也没有同义词，导致「有汽车的照片」这类搜索漏召。
3. **后台对账存在死循环隐患**：云端 AI 持续失败时，同一批照片会被反复排队重试，持续消耗 API。

---

## 二、新增：大模型使用策略（设置页）

设置页「大模型使用方式」由单个开关改为 5 种策略：

| 策略 | 行为 |
| --- | --- |
| **不使用大模型** | 完全端侧识别，不联网、不消耗流量 |
| **全部照片** | 每张都调用云端大模型（旧行为） |
| **仅新增 / 未分析的照片** | 存量结果不动，只处理新拍 / 新下载 / 未分析的照片 |
| **仅识别不确定的照片（推荐）** | 先跑端侧识别，只有标签少、置信度低、场景不明确的照片才交给大模型精修，可信度阈值可调（默认 55%） |
| **仅手动选择的照片** | 只有你在相册里明确勾选的照片才联网分析 |

配套配置：

- **识别可信度阈值**（UNCERTAIN 策略下出现）：0~100%，数值越大交给大模型的照片越多。
- **仅 Wi-Fi 下使用大模型**：移动数据下自动跳过云端分析，连上 Wi-Fi 后由后台守护补上（新增 `ACCESS_NETWORK_STATE` 普通权限）。
- **本地准确度增强开关**：关闭后检索行为与 v3.10.0 完全一致，方便对比。

旧版 `use_vlm` 布尔值自动迁移：原来关闭过 AI 的用户继续是「不使用」，其余默认「全部」，行为平滑。

---

## 三、新增：选择让大模型分析哪些照片

在相册里：

1. 右上角更多菜单 →「多选照片（重新分析 / 交给大模型）」；
2. 勾选需要的照片，可点右上角「全选」；
3. 底部出现两个按钮：「重新分析」（端侧，不联网）与「**用大模型分析**」；
4. 点「用大模型分析」后，这些照片写入持久化待办集合，任何非 OFF 策略下都会被**强制**纳入分析队列，分析完自动消费，不会反复重跑。

单张照片的详情页「重新分析」与批量「重新分析」也都改为遵守当前策略。

实现要点：

- `AppConfig` 持久化策略 id、阈值、Wi-Fi 开关、手动选择集合；
- `AnalysisPlanner.plan()` 新增策略重载，按 `VlmPolicy.decide()` 逐张决定哪些已分析照片需要补 AI 描述，布尔旧接口保留兼容；
- `AnalysisService` 每轮对账重新读取策略（改设置 / 勾选照片下一轮即生效），逐张决策；已有本地结果的照片**只补云端描述、不重复跑 ML Kit**；
- 手动选择在尝试过（无论成败）后即从待办集合消费，避免无限重试。

---

## 四、新增：不依赖大模型的准确度增强

详见 [ACCURACY_WITHOUT_VLM.md](ACCURACY_WITHOUT_VLM.md)。核心是**把已经算出但被浪费的端侧信号全部编入索引**，不改模型、不联网、不重跑分析：

| 改进 | 说明 |
| --- | --- |
| 本地视觉标签 | 主色 / 亮度 / 饱和度 / 横竖幅 / 全景 / 单双人 / 合影 / 人群 / 文字 / 黑白 / 夜景 → 中文可检索标签（`LocalVisualTags.kt`） |
| 多场景标签 | `scoreScenes()` 保留所有达标场景，一张「夜晚+城市+车」同时有三个场景标签 |
| 低置信标签 | 0.15~0.30 的标签不再丢弃，以弱权重入索引（远景小车漏召的主因） |
| 查询同义扩展 | 「汽车 → 车辆 / 轿车 / vehicle / car」，按概念组隔离、中英交错、命中分打折（`QueryExpander.kt`） |
| 多弱证据叠加 | 单条弱证据不命中，2~5 条相关弱证据累加后可命中 |
| 升级即生效 | 纯函数从已落盘字段推导，**历史分析结果无需重跑** |

`PhotoMetadata` 新增 `ocrText / brightness / saturation / faceCount / sceneTags / localTags` 字段，全部带默认值；`AnalysisResultStore` 用 `opt*` 反序列化，旧缓存自动兼容。同时预留了端侧 OCR 的接入位（本版未引入 OCR 模型依赖，避免 APK 体积与构建风险）。

---

## 五、修复

- **后台对账死循环**：同一批照片连续两轮待处理且期间无新增照片时退出本轮循环，避免云端 AI 持续失败时原地无限重试、反复消耗 API。
- **「截图」精确率回归**：早期实现把 `hasText` 直接提为「截图」强证据，导致有文字的书页照片被误召回；现改为「文字/文档」强证据、「截图/招牌/票据」弱证据，并把「截图」与「文字」拆到不同查询扩展组。召回评测 `截图` 精确率回到 1.00。
- **标签大小写统一**：`LocalVisualTags` 输出统一 lowercase，避免英文标签大小写不一致。
- `AboutScreen` / `HelpScreen` / 设置页文案同步 v3.11.0。

---

## 六、改动文件

**新增**

- `search/VlmPolicy.kt`（策略枚举、可信度评估、逐张决策）
- `search/LocalVisualTags.kt`（本地视觉信号 → 中文标签）
- `search/QueryExpander.kt`（中文同义 / 上下位词扩展）
- `tools/V311Eval.kt`（63 项离线评测）
- `docs/ACCURACY_WITHOUT_VLM.md`、`docs/CHANGELOG_v3.11.0.md`

**修改**

- `data/AppConfig.kt`、`data/PhotoMetadata.kt`、`data/AnalysisResultStore.kt`
- `ml/EnhancedImageAnalyzer.kt`（亮度/饱和度采样、多场景、人脸数）
- `search/ConceptRegistry.kt`、`search/IntentParser.kt`、`search/SearchEngine.kt`、`search/SearchIntent.kt`
- `service/AnalysisPlanner.kt`、`service/AnalysisService.kt`
- `viewmodel/PhotoViewModel.kt`
- `ui/screens/SettingsScreen.kt`、`ui/screens/HomeScreen.kt`、`MainActivity.kt`
- `AndroidManifest.xml`（`ACCESS_NETWORK_STATE`）
- `app/build.gradle.kts`（versionCode 14 / 3.11.0）
- `docs/CHANGELOG.md`、`docs/USER_MANUAL.md`、`ui/screens/AboutScreen.kt`、`ui/screens/HelpScreen.kt`

---

## 七、验证

| 项目 | 结果 |
| --- | --- |
| `V311Eval`（新） | **63 / 63** |
| 历史 6 套 Kotlin 评测 | 全绿（RecallEval / ExifSearchEval / V37Eval 41-41 / ResumeEval / BackgroundEval / PerfEval） |
| 召回评测 MACRO_F1 | 0.911 → **0.933** |
| 3 万张照片冷检索 | 372 ms（缓存命中 267 ms） |
| Android 构建 / 签名 | GitHub Actions 构建成功，`apksigner` v2/v3 校验通过 |

所有历史评测在「关闭本地增强」时逐字节保持 v3.10.0 语义，可放心回退。
