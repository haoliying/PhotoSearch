# PhotoSearch v3.8.0 更新说明

> 主题：**修复搜索框无法输入 / 输入后假死，支持几万张以上照片**。
> versionCode 11 / versionName 3.8.0，沿用固定 keystore，可直接覆盖安装。

## 一、用户反馈与根因

| 现象 | 根因 |
| --- | --- |
| 搜索框打字没反应、丢字 | `OutlinedTextField` 的 value 来自 ViewModel 的 `StateFlow`，`onValueChange` → `viewModel.search()` → **主线程同步** `applyFilters()` → 全库检索。主线程被占满，输入法无法回显 |
| 偶尔输入后整App假死 | 每敲一个字符都对**全部照片**重新执行 `fromMetadata()` + `buildSearchIndex()`；英文词每次编译 `Regex`；3 万张照片下是数百万次正则编译 + 上千万次字符串重建，直接 ANR |
| 照片多时后台扫描期间卡顿 | EXIF 每 25 张就 `exifCache.toMap()` 全量复制 + 全量 JSON 重写 + 全量 `recomputePlaces()`，并从 IO 线程直接写 StateFlow 触发全屏重组 |
| 分析照片时越来越卡 | 分析服务每 1~3 张 emit 一次进度，ViewModel 每次都全量 `map` 照片列表 + 全库检索；`AnalysisResultStore` 每段全量 JSON 落盘（O(N²) 磁盘写） |

一句话：**输入路径上挂着一条 O(N × 索引构建) 的同步计算**，且所有数据变更都走"全量复制 + 全量重写"。

## 二、逐项修复

### 1. 输入与检索彻底解耦
- `PhotoViewModel.search()` 只做 O(1) 状态赋值；检索经 **280ms 防抖** 后放进 `Dispatchers.Default`。
- 新输入 **取消旧 Job**，并用 `filterGeneration` 代次号丢弃过期结果；`SearchEngine` 每 256 张检查一次取消信号，旧查询立即中止，不会排队堆积。
- 主线程只负责写入 `filteredPhotos / searchResultCount / isSearching` 等结果；`noTimeCount`、`noLocationCount`、地点候选重算等 O(N) 统计全部移到后台。
- `HomeScreen.SearchPanel` 的文本框改为 **Compose 本地 `rememberSaveable` 状态**，按键立即回显，不依赖 StateFlow 往返；外部「清除搜索」通过 `LaunchedEffect` 同步。
- 新增 `isSearching` 状态与顶部细进度条，检索期间界面仍可滚动、可继续输入。

### 2. 检索内核：索引复用 + 去掉正则编译
- 新增 `ConceptRegistry.SearchDocCache`（LRU，默认 6000 张，可配上限）：缓存每张照片的 `PreparedDoc`（概念证据 + 全文索引），跨查询复用；元数据/EXIF 变化时按 id 精确失效或整体清空。
- `SearchIndex` 由「50~100 条 (文本, 权重)」改为**按权重分桶合并**（降序），`score()` 命中首个桶即返回最大权重。语义与旧版逐条取最大权重完全等价，内存降到约 1/5。
- `containsAsciiWord()` 用 `indexOf` + 边界判断替代每次 `Regex("(?<![a-z])…(?![a-z])")` 编译，`normalize()` 与 `strictMatch()` 共用。
- `scrub()` 把 46 次 `String.replace` 合成一次正则扫描；`normalize()` 结果加 4096 条 LRU 缓存；切分正则全部预编译。
- 预编译的常量与分桶索引均经过离线等价性断言验证。

### 3. 大数据量下的复制与落盘
- `PhotoExifStore` / `AnalysisResultStore` 改为**行式增量存储**（`*_v3.jsonl`）：每批只追加新增行，O(批次)；旧单文件在首次写入时一次性迁移，读取时自动兼容；手动标注等更新以「后写覆盖」追加。
- `exifMap` 保持为同一个 `ConcurrentHashMap` 引用，靠 `exifVersion` 触发 UI 刷新，不再每批 `toMap()` 全量复制；EXIF 提取每 200 张刷新一次进度，中途不重算地点候选。
- 分析服务进度合并为每 **20 张 / 400ms** 一次发射；ViewModel 分析期间**只更新进度条**，完成时才做一次全量合并与检索，中途零全库计算。
- `analyzedMetadata`、`metadataCache`、`exifCache` 全部改为 `ConcurrentHashMap`，消除跨线程读写普通 HashMap 的隐患。
- 批量重新分析不再每张复制整个照片列表，改为收集 `updatedById` 后合并一次。

### 4. UI 与图片加载
- `AsyncImage` 显式指定采样尺寸（列表 176px / 网格 256px）并关闭 crossfade，避免滚动时解码原始大图造成内存抖动。
- 新增 `exifVersion` 作为 `key()` 触发列表刷新，避免全量 Map 复制导致的重组。
- `MainActivity` 传递 `isSearching` / `exifVersion`。

## 三、性能实测（tools/PerfEval.kt，30,000 张照片）

本地 JVM 基准，数据集含 3 万份分析结果与 3 万份 EXIF：

| 场景 | 优化前 | v3.8.0 |
| --- | --- | --- |
| 单次全库检索（冷，重建全部索引） | 17,501 ms | **2,169 ms** |
| 缓存命中（复用索引） | 无缓存，每次 17.5 s | **26 ms** |
| 连续 6 次不同查询 | 每次全量重建 | **244 ms 总计** |
| 时间×地点×内容联动查询 | — | 10 ms / 命中 125 张 |
| 取消旧查询 | 不可取消 | **0 ms 立即中止** |

冷/热缓存返回的结果 ID 顺序完全一致（3750 张命中，逐项比对通过）。

## 四、回归验证

| 评测 | 结果 |
| --- | --- |
| `tools/PerfEval.kt` | **26/26 PASS**（含词边界等价、分桶索引等价、取消、大库基准） |
| `tools/V37Eval.kt` | **41/41 PASS** |
| `tools/ExifSearchEval.kt` | **25/25 PASS，宏平均 P=R=F1=1.000** |
| `tools/RecallEval.kt` | 宏平均 **0.933**，与 v3.5.2 持平 |
| Kotlin 1.9.22 本地编译 | 数据层 + 检索层 + Android 桩编译通过 |
| Android 真实编译 | 由 GitHub Actions `assembleRelease` 验证 |

## 五、容量与降级

- 文档缓存默认 6000 张：即使相册有几万张，内存占用有上限；未命中的照片现算，且已无正则编译，单次全库检索在后台线程为秒级，**不会阻塞输入**。
- 搜索结果仍为完整列表，但 `LazyVerticalGrid` / `LazyColumn` 只组合可见项；配合固定采样尺寸，滚动内存可控。
- 若 EXIF 缓存损坏：行式读取会跳过损坏行，下次扫描自动重新提取；旧 v2 单文件仍在时会自动迁移。
- 回滚：`git revert <commit>` 或安装 v3.7.0（签名相同，可覆盖安装）。回滚后 `*_v3.jsonl` 不影响旧版读取（旧版只读 `photo_exif.json` / `analysis_results.json`）。

## 六、改动文件

```
app/build.gradle.kts
data/PhotoExifStore.kt          data/AnalysisResultStore.kt
search/ConceptRegistry.kt       search/SearchEngine.kt
service/AnalysisService.kt      viewmodel/PhotoViewModel.kt
ui/screens/HomeScreen.kt        MainActivity.kt
docs/CHANGELOG_v3.8.0.md        docs/PERFORMANCE_v3.8.0.md
tools/PerfEval.kt
```
