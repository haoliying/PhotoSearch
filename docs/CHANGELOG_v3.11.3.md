# v3.11.3 · 修复大相册（2 万张以上）打开后闪退

> 发布日期：2026-11-14 · versionCode 17 · 可直接覆盖安装（同一签名，分析结果保留）

## 一、用户反馈

> 「现在打开等一会儿就闪退，照片 25000 多」

关键词是**「等一会儿」**：不是冷启动瞬间崩，而是打开后过一段时间才崩。这条时间线正好
对应「默认后台守护自动拉起全量分析 → 分析结果开始持续回流 → 内存累积到上限」。
v3.11.2 把并发提到 4 路并让守护熄屏后也持续分析，使这条链路的累积速度明显加快，
于是在 25000 张这个量级上暴露出来。

## 二、根因（按影响排序）

| # | 根因 | 位置 | 后果 |
| --- | --- | --- | --- |
| 1 | **每批结果都做一次 25000 元素全表复制** | `PhotoViewModel.absorbIncrement` 的 `snapshot.map { p.copy(...) }` | 服务每 20 张 / 300ms 发一批，25000 张约 1250 批 → 1250 次全表复制；`StateFlow` 同时持有新旧两份大列表，Compose 对 `items(key=id)` 做全量 diff |
| 2 | **每轮对账都全量反序列化 25000 行 JSON** | `AnalysisService.runUntilSettled` 的 while 里 `resultStore.load()` | 每行 `JSONObject` + 多个 `JSONArray` + 反序列化出 `PhotoMetadata`，峰值堆与 GC 抖动极大；心跳每次唤醒后还会再来一遍 |
| 3 | **无界通道无背压** | `workChannel` / `resultChannel` 均为 `Channel.UNLIMITED`，并一次性灌入全部 pending id | 结果通道会堆积完整 `PhotoMetadata`，与静态 `analyzedMetadata` + VM `metadataCache` 双份常驻叠加 |
| 4 | **位图泄漏** | `EnhancedImageAnalyzer.analyzePhoto` 只在成功路径 `bitmap.recycle()` | 4 个 ML Kit 检测器任一抛异常时，768×768×4B ≈ 2.25MB 位图不回收；25000 张 × 4 路并发下累积泄漏足以触发 OOM |
| 5 | **`OutOfMemoryError` 未被捕获** | 所有 `catch (e: Exception)`、`serviceScope` 无 `CoroutineExceptionHandler` | `Error` 不是 `Exception`，直接冒泡杀进程 —— 这就是「闪退」的直接机制 |
| 6 | 通知高频构造 | 进度 step=8 / 200ms，每次 `buildNotification` 建 3 个 `PendingIntent` | 25000 张分析期间数千次 Binder 事务，部分 ROM 会限流或抛 `TransactionTooLargeException` |
| 7 | 堆上限偏紧 | Manifest 未声明 `largeHeap` | 2 万张以上元数据 + 索引常驻，普通堆上限容易被顶穿 |

## 三、修复

### 1. 增量累积 + 阈值合并（主因）

- `absorbIncrement` 收到结果后**只做 O(k)**：`metadataCache.putAll` + `docCache.invalidate`
  立即生效，搜索与详情的正确性完全不受影响；结果按 id 累积到 `pendingUiResults`
  （`LinkedHashMap`，后写覆盖）。
- 达到阈值才做一次全表合并。大相册（≥8000 张）阈值 **累计 200 张 / 1.5 秒**，
  小相册维持 **20 张 / 300ms** 不变，不牺牲小相册的流畅度。
- 分析由「运行中」转为「停止」（完成 / 暂停 / 中断）时 `flushPendingUi()` 强制合并
  残余，保证最后一批一定可见。
- 25000 张下全表复制次数 **1250 → 125（降 90%）**。

### 2. 后台服务只读盘一次 + 有界背压

- 磁盘全量反序列化由「每轮」改为**仅 `round == 0` 一次**，之后复用进程内
  `analyzedMetadata` 镜像（每张落盘时同步写入，是完整权威镜像）。
- `workChannel` / `resultChannel` 改为 `Channel(capacity)`，
  `capacity = (parallelism × 4).coerceIn(4, 32)`；新增独立生产者协程用 `send`
  边发边消费，任何时刻在途 id 数 ≤ capacity（旧版是 25000）。
- 生产者 / 关闭协程加兜底 `cancel()` 与异常捕获，避免有界通道在暂停或 worker
  提前退出时互相等待。

### 3. 位图与 OOM 兜底

- `analyzePhoto` 把 `bitmap.recycle()` 移入 `finally`，4 个检测器任一异常或内存
  不足时也一定释放；解码入口单独捕获 `OutOfMemoryError`。
- 为**解码、单张分析、整批分析、服务循环**分别加 `catch (OutOfMemoryError)`：
  就地降级并发（`degradedParallelism`，下限 1）、`System.gc()`、写占位结果或
  `markProgress`，**不杀进程**，已落盘结果按 photoId 差集续跑。
- `serviceScope` 增加 `CoroutineExceptionHandler` 作为最后一道防线。

### 4. 并发自适应与通知降频

- 新增 `MemoryGuard.safeParallelism(configured, photoCount, heapMb)`：
  可用堆 <128MB 强制 1 路；<256MB 时大相册最多 2 路、小相册 3 路；
  正常堆下大相册最多 4 路。**设置页选的是上限，不是强制值**，
  大相册自动降路数属正常保护。
- 通知栏刷新由 200ms 降到 **900ms**（`force` 收尾帧仍必发）；进度 `StateFlow`
  仍按 200ms 更新，UI 进度不受影响。
- Manifest 声明 `android:largeHeap="true"` 作为大相册兜底。

## 四、不受影响的部分

- 磁盘（JSONL 逐张追加）仍是结果唯一权威：升级、暂停、降级、被系统强杀后，
  下次打开都按 photoId 差集补齐，**不会从头重跑**。
- 断点续跑、增量事件、后台分析不影响前端搜索的检索隔离、暂停 / 继续状态机、
  大模型使用策略、本地准确度增强全部保持不变。
- 小相册（<8000 张）的 UI 合并行为与 v3.11.2 一致。

## 五、验证

| 项目 | 结果 |
| --- | --- |
| 新增 `V314Eval` | **51 / 51**（并发自适应、有界通道、合并次数、仅首轮读盘、内存预算、OOM 降级、通知降频、历史常量一致性） |
| 历史 9 套评测（用 v3.11.3 源码复跑） | 全部通过：V311Eval 63/63、RecallEval F1=0.933、V37Eval 41/41、ExifSearchEval F1=1.000、PerfEval 26/26、ResumeEval 35/35、BackgroundEval 46/46、V312Eval 21/21、V313Eval 42/42 |
| 量化 | 全表合并 1250 → 125 次（−90%）；峰值内存估算 118822KB → 55014KB（−54%）；通道在途 25000 → ≤32 |
| 构建 | GitHub Actions `assembleRelease` + `apksigner verify` |

## 六、改动文件

| 文件 | 改动 |
| --- | --- |
| `service/MemoryGuard.kt` | 新增：内存保护纯函数 |
| `service/AnalysisService.kt` | 仅首轮读盘、并发自适应、有界通道、OOM 降级、全局异常兜底、通知降频 |
| `ml/EnhancedImageAnalyzer.kt` | 位图 try/finally 回收、OOM 捕获 |
| `viewmodel/PhotoViewModel.kt` | 增量累积 + 阈值合并 + 收尾 flush |
| `ui/screens/AboutScreen.kt` / `HelpScreen.kt` / `SettingsScreen.kt` | 版本说明、第十三章、并发上限说明 |
| `AndroidManifest.xml` | `largeHeap` |
| `app/build.gradle.kts` | versionCode 17 / 3.11.3 |
| `tools/V314Eval.kt`、`docs/*` | 评测与文档 |

## 七、用户可做的（若仍闪退）

1. 升级到 v3.11.3 及以上（不会重跑已有分析）。
2. 首页点「暂停分析」停止后台任务，或关闭「后台自动分析」改为手动分批跑。
3. 设置页把「分析速度」调到 1 路。
4. 反馈机型、Android 版本与照片数量，便于进一步定位。
