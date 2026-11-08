# PhotoSearch v3.7.0 变更说明

对应本轮 5 项需求：标签中文、时间提示格式、EXIF 信息扩展、右滑返回、界面重构。

版本：`versionCode = 10`，`versionName = 3.7.0`。签名沿用 v3.5.0 起的固定 JKS，可直接覆盖安装。

---

## 1. 分析出的照片标签改为中文

**问题**：VLM 关键词虽是中文，但 ML Kit 的「内容标签」「识别物体」直接显示英文
（`Car`、`Laptop`、`Plant`…），界面上中英混排。

**改动**

| 文件 | 内容 |
| --- | --- |
| `data/PhotoMetadata.kt` | `LabelTranslator` 词表从约 130 条扩到约 300 条，覆盖 ML Kit 常见类别；新增 `toChinese()`（复合短语逐词翻译、无法翻译返回 null）、`toChineseLabels()`；新增 `PhotoMetadata.chineseDisplayTags()` 统一输出中文标签 |
| `ml/VisionLanguageAnalyzer.kt` | 提示词强制「所有字段一律简体中文」；JSON 与自由文本两条解析路径的关键词都经 `toChinese()` 清洗，模型夹带英文时自动翻译或丢弃 |
| `ui/screens/PhotoDetailScreen.kt` | 「内容标签」「识别物体」「VLM 关键词」全部显示中文，按置信度排序并去重 |
| `ui/screens/HomeScreen.kt` | 列表视图展示中文标签 chips |

**兼容性**：检索索引里仍保留英文原词（`toSearchableTags`），所以 `car` 之类的英文查询不受影响；
只是界面不再出现英文标签。词表外的英文标签在界面上不展示，但不会丢检索能力。

---

## 2. 时间检索条件提示格式修正

**问题**：自定义区间的 placeholder、错误提示、当前条件、匹配规则各自用不同格式
（`2023-01-01` / `2023-01-01 ~ 2023-12-31` / `2023年6月`），且没有说明支持哪些写法。

**改动**（`search/TimeQueryParser.kt`）

- 新增 `DATE_FORMAT_HINT` 与 `TIME_QUERY_HINT` 两个统一常量，界面直接引用，不再各写一份。
- 新增 `formatChinese()`：输出 `2023年6月1日 至 2023年12月31日`；新增 `displayLabel()`：
  快捷标签（今年 / 最近30天 / 去年夏天）优先，其余统一回退到中文日期区间。
- `fromDateStrings()` 现在支持 `2023-06-01`、`2023年6月1日`、`2023/6/1`、`2023.6.1`、
  `20230601`，以及只写年月的 `2023-06` / `2023年6月`（自动按整月处理）；
  返回值 label 也改为中文格式。
- `IntentParser.getDescription()`、`SearchEngine.filterLabels()`、
  `PhotoViewModel` 的 `filterSummary` 全部改用 `displayLabel()`，格式一致。
- 顺手修掉「2023年在北京拍的汽车」剥离年份后残留一个「年」字的问题。

**界面**（`HomeScreen.kt` 筛选面板）：两个输入框都带「开始日期 / 结束日期」标签，
格式说明常驻显示，错误提示列出全部支持的格式；搜索面板显示时间写法示例。

---

## 3. EXIF 还能提取哪些信息，也放到标签里

详见 `docs/EXIF_SEARCH.md` 的「v3.7.0」章节。

新增：相机/镜头/软件/作者、光圈/快门/ISO/焦距/等效焦距/曝光补偿/测光、
闪光灯/白平衡/方向、像素宽高、GPS 海拔、GPS 日期戳/时区、MIME/文件大小/扩展名。

这些都转成中文可检索标签（佳能、竖幅、广角、大光圈、JPEG、春季、周末…），
以 0.75 权重进入全文索引；详情页展示「拍摄参数与文件信息」和「可检索标签」。

`photo_exif.json` 升到 version 2，旧缓存自动重新提取一次并保留手动标注的地点。
MediaStore 的 `width/height/_size/mime_type` 只在 API 29+ 投影，避免旧系统报「no such column」。

---

## 4. 右滑返回上一级

新增 `ui/SwipeBack.kt` 的 `SwipeBackContainer`，`MainActivity` 用它包裹设置页与照片详情页。

- 只从屏幕左边缘 72dp 内起手才接管，不与列表垂直滚动冲突；
- 起手后比较水平/垂直位移，明确右滑才命中，垂直滑动立即放行；
- 拖动跟手位移，松手超过 110dp 阈值滑出并回调返回，否则回弹；
- 容器内注册 `BackHandler`，系统返回键与右滑行为完全一致；
- 详情页顶栏标注「右滑可返回上一级」。

项目用 `when` 状态切换页面（未接 Navigation Compose），因此不依赖 NavController。

---

## 5. 界面重构：以照片为主体，支持列表 / 缩略图

**旧版问题**：搜索卡片 + 筛选卡片 + 分析卡片 + 计数行四块常驻堆叠，
只要设了筛选条件筛选面板就一直展开，照片被挤到屏幕底部一小块。

**新版**（`ui/screens/HomeScreen.kt` 重写）

- 顶部栏只有 4 个图标：搜索、筛选、视图切换、更多（设置 / 重新分析 / 多选 / 重载相册）；
  标题下方显示照片数量或结果数。
- 所有查询条件**默认收起**；生效时只显示一行摘要栏（条件文本 + 结果数 + 调整 / 清除），
  点图标才展开完整面板。
- 搜索面板：输入框 + 匹配规则 + 时间写法提示 + 快捷搜索 chips。
- 筛选面板：时间快捷 chips、自定义区间（带标签和格式说明）、地点输入与候选 chips、
  进度与提示，分区更清晰。
- 分析入口收成一条细状态条：分析中 / 可续传 / 未分析时显示，
  **分析完成即完全隐藏**，屏幕留给照片；「重新分析全部」移到溢出菜单。
- 视图切换：网格（纯缩略图，`GridCells.Adaptive(110.dp)`）/ 列表。
- **列表视图**每项显示：88dp 缩略图、文件名、拍摄时间、拍摄地点、
  格式 / 分辨率 / 文件大小 / 相机、中文标签 chips。
- 多选重分析保留，入口移到溢出菜单，底栏精简。

`MainActivity` 新增向 `HomeScreen` 传递 `state.exifMap`。

---

## 验证

| 评测 | 结果 |
| --- | --- |
| `tools/V37Eval.kt`（新增，41 项断言） | **41/41 PASS** — 中文标签、EXIF 标签检索、时间格式、联动 |
| `tools/ExifSearchEval.kt`（v3.6.0 回归） | **25/25 PASS**，宏平均 P=R=F1=**1.000** |
| `tools/RecallEval.kt`（v3.5.2 回归） | 宏平均 P=R=F1=**0.933**，与历史基线一致 |
| 纯逻辑层 Kotlin 1.9.22 编译 | 数据层 + 检索层 + Android API 桩全部通过 |
| UI / 签名 APK 构建 | GitHub Actions（JDK 17 + Android SDK 34 + zipalign + apksigner verify） |

---

## 降级与回滚

1. **覆盖安装**：v3.7.0 与 v3.5.0 起所有版本使用同一 keystore，可直接覆盖安装，无需卸载。
2. **回退版本**：安装 v3.6.0 的 Release APK 即可覆盖回退；EXIF 缓存 v2 在 v3.6.0 下
   会因版本不匹配被忽略并重新按 v1 提取，不会崩溃，只是丢失拍摄参数标签。
3. **CI 构建失败**：`.github/workflows/release-apk.yml` 已内置多镜像 Gradle 下载
   （腾讯云 / 华为云 / 阿里云 / 官方）并用解压后的 gradle 二进制直连构建，
   绕开 wrapper 下载超时；可在 Actions 页手动 `workflow_dispatch` 重试。
4. **无网络 / 无 VLM Key**：EXIF 标签、时间、地点、格式信息全部来自本地文件，
   不依赖 AI 分析；升级后即使不重新分析也能按这些条件检索。
5. **旧系统（API 26–28）**：不投影 WIDTH/HEIGHT/_size/mime_type 列，
   宽高从 EXIF 兜底，列表视图对应字段为空，其余功能不受影响。
