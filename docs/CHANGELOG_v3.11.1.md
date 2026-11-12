# v3.11.1 · 不用大模型时的分析速度大幅提升

> 2026-11-12 · versionCode 15
> 解决的问题：**关闭大模型、纯端侧分析时速度非常慢。**

---

## 一、根因：慢不在模型，在调度

v3.11.0 的端侧识别单张并不慢（ML Kit 三个检测器内部并行、颜色统计只采样 500 像素），
慢的是**批量分析的调度方式**：

| 根因 | 影响 |
| --- | --- |
| 全局单线程串行：`analysisExecutor` 是 `newSingleThreadExecutor`，`runPlan` 逐张 `for` 循环 | ML Kit 的 Labeler / ObjectDetector / FaceDetector 本身线程安全、可多张并行，却只能一张一张排队。哪怕每张 1 秒，1 万张也要近 3 小时 |
| 每张照片打开文件 3 次：量尺寸（inJustDecodeBounds）+ 解码 + EXIF 方向各开一次流 | 相册照片普遍 3~10 MB，几万张的全量 IO 相当可观 |
| 分析线程整体为后台优先级（`THREAD_PRIORITY_BACKGROUND`） | CPU 紧张时被系统进一步压制（这是「不卡前端」的既有设计，保留） |

「后台不影响前端搜索」的隔离设计（增量事件、文档缓存增量失效）与提速不冲突：
并发只发生在分析块内部，进度 / 通知 / 增量事件仍在块间串行批处理。

## 二、改动

### 1. 块内并发分析（核心）

- 新增 `service/AnalysisScheduling.kt`（纯函数，无 Android 依赖，可离线评测）：
  - `coerceParallelism()`：并发数收敛 1..4，默认 2；
  - `chunksOf()`：把待分析队列切成「块内最多 N 张、块间串行收尾」的批次；
  - `orientationToDegrees()`：EXIF 方向常量 → 旋转角。
- `AnalysisService.runPlan`：`for (id in pendingIds)` 逐张串行 → 按 `chunksOf` 分块，
  块内 `async(Dispatchers.Default)` 并行分析，每张完成即在 worker 内落盘
  （`appendResult` 内部有锁，线程安全；取消时已完成的照片不丢，磁盘仍是唯一权威）；
  块间统一做进度、通知、增量事件，前端仍只看到批处理节奏。
- 并发数 = 1 时每张一块，行为与 v3.11.0 逐张串行完全一致（可回退）。
- `PhotoViewModel.reanalyzeSelectedPhotos`（多选 → 重新分析）同样按并发数分块并行。

### 2. 每张照片的文件打开从 3 次降到 1 次

- `EnhancedImageAnalyzer` 新增 `loadBitmapViaFd()`：`openAssetFileDescriptor` 只打开一次，
  EXIF 方向（`ExifInterface(FileDescriptor)` 随机访问只读头部）、量尺寸、解码三个阶段
  共用同一文件描述符，阶段间用 `Os.lseek` 归零。
- 任何一步失败自动回退 `loadBitmapViaStreams()`（v3.11.0 原路径），可用性不受影响。

### 3. 并发安全

- `AnalysisResultStore.appendResult` 本就有 `synchronized`，并发落盘安全；
- `analyzedMetadata` 为 `ConcurrentHashMap`；
- 「手动选择交给大模型」的消费记录（SharedPreferences 读改写）在 `AnalysisService`
  与 `PhotoViewModel` 两侧各加互斥锁，防止并发下丢失消费记录导致无限重试。

### 4. 设置页

- 新增「分析速度（端侧识别）」：1 路（省电）/ 2 路（均衡，默认）/ 3 路（快速）/ 4 路（极速）。
- 只影响之后的分析速度，既有分析结果不受影响；与是否使用大模型无关，两条通道都提速。

## 三、验证

| 项目 | 结果 |
| --- | --- |
| `V312Eval`（新增，21 项断言） | **21 / 21 通过**：并发数收敛、分块不丢不重、并发 1 等价串行、乱序完成不丢结果、块间按序收尾、真并发（barrier 汇合）、取消语义、10 万张分块 O(n)（17ms） |
| V311Eval（63 项） | 63 / 63（v3.11.1 源码复跑） |
| RecallEval | MACRO_F1 = 0.933，与 v3.11.0 持平 |
| V37Eval / ExifSearchEval / PerfEval / ResumeEval / BackgroundEval | 41/41、F1=1.000、26/26、35/35、46/46 全绿 |
| 回归语义 | 断点续跑、增量事件、单张落盘、策略决策、检索行为全部不变 |

## 四、改动文件

| 文件 | 变更 |
| --- | --- |
| `app/src/main/java/com/example/photosearch/service/AnalysisScheduling.kt` | 新增：调度纯函数 |
| `app/src/main/java/com/example/photosearch/service/AnalysisService.kt` | runPlan 块化并发 + 手动选择消费互斥锁 |
| `app/src/main/java/com/example/photosearch/ml/EnhancedImageAnalyzer.kt` | 单 fd 复用（EXIF / 量尺寸 / 解码一次打开） |
| `app/src/main/java/com/example/photosearch/viewmodel/PhotoViewModel.kt` | 批量重分析并发化 + 消费互斥锁 |
| `app/src/main/java/com/example/photosearch/data/AppConfig.kt` | `analysis_parallelism` 配置（1..4，默认 2） |
| `app/src/main/java/com/example/photosearch/ui/screens/SettingsScreen.kt` | 「分析速度」选择卡片 |
| `app/src/main/java/com/example/photosearch/ui/screens/AboutScreen.kt` / `HelpScreen.kt` | v3.11.1 版本说明与 FAQ |
| `app/build.gradle.kts` | versionCode 15 / versionName 3.11.1 |
| `tools/V312Eval.kt` | 新增：离线评测 |
| `docs/CHANGELOG.md` / `docs/USER_MANUAL.md` / 本文档 | 文档同步 |
