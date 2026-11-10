# 后台自动分析与检索隔离（v3.10.0）

> 需求：把智能分析做成后台运行；监控相册变化，有新图片加入时自动分析并保存结果；
> **后台分析时不影响前端搜索照片**。

## 1. 旧行为与问题

v3.9.0 及以前：

| 环节 | 旧实现 | 问题 |
| --- | --- | --- |
| 服务生命周期 | `AnalysisService` 是一次性前台服务：`runAnalysis()` 跑完就 `stopSelf()`，`START_NOT_STICKY` | 跑完即销毁，不常驻；必须回到 App 前台才能续跑 |
| 相册变化 | 无 | 没有任何监听，新拍/新下载的照片只能靠手动「重新加载相册」或下次打开 App 才发现 |
| 结果传递 | 进程内静态 `analyzedMetadata` + `analysisState`；服务完成/停止触发 `syncServiceResults()` | 全量 `metadataCache.putAll` + 全量 `map` 合并 + **`docCache.clear()` 清空几万张索引** + `scheduleFilters(0)` 全库重跑检索 |
| 线程 | 分析在 `Dispatchers.Default`，与检索同池 | 几万张图片解码 / JSON 落盘会与检索抢 CPU |

如果直接把旧服务改成常驻、每来一批新照片就"完成一次"，`syncServiceResults()` 会被反复触发：
每批都清空整个 LRU 文档缓存并全库重算，前端输入、滚动、搜索都会周期性卡顿。
**"后台分析不影响前端搜索"必须靠架构解耦，而不是简单加个开关。**

## 2. 总体架构

```
┌──────────────────────── 后台守护进程（同一 APK 进程，常驻） ────────────────────────┐
│                                                                                   │
│  MediaStoreObserver ──(1.5s 防抖)──> AnalysisService.dispatchAnalysis()           │
│   ContentObserver(EXTERNAL_CONTENT_URI, notifyForDescendants=true)                │
│        ▲                                   │                                      │
│        │ 系统相册变化                        ▼                                      │
│        │                        runUntilSettled():                               │
│  BootReceiver(开机/升级)                  1. PhotoRepository.loadAllPhotos()       │
│  MainActivity.onResume                    2. AnalysisPlanner.diff(上次快照, 当前)   │
│        └─(尽力拉起/兜底)                  3. AnalysisResultStore.load()            │
│                                            4. AnalysisPlanner.plan() 只挑 pending  │
│                                            5. runPlan(): 逐张分析                  │
│                                                                                   │
│   低优先级单线程 ps-analysis (THREAD_PRIORITY_BACKGROUND)                          │
│        │ 每张: analyzeOne() → appendResult() 单张落盘                              │
│        │       → IncrementBuffer 累积 → 达到 20 张 / 300ms 发一批                  │
│        ▼                                                                          │
│   AnalysisResultStore (JSONL)  ───── 唯一权威 ─────┐                               │
│   AnalysisEvents (进程内 SharedFlow / StateFlow)    │                               │
│        ├─ ResultBatch(id -> metadata)               │                               │
│        ├─ AlbumChanged(added, removed)              │                               │
│        └─ WatchState(active, autoAnalyzed)          │                               │
└────────────────────────────────────────────────────┼──────────────────────────────┘
                                                     │ 事件只是运行期加速，丢了不影响正确性
┌──────────────────────────── 前端（App 进程） ───────┼──────────────────────────────┐
│  PhotoViewModel                                    │                               │
│   ├─ 冷启动 loadPhotos() ───────────────────────────┘ 从磁盘读全量补齐               │
│   ├─ absorbIncrement(batch): putAll(k) + docCache.invalidate(仅这 k 个 id)         │
│   │     O(N) 列表更新放 Dispatchers.Default；无查询时完全不跑检索                     │
│   ├─ onAlbumChanged(): 增量刷新列表 + 新照片增量提取 EXIF                            │
│   └─ SearchEngine: Dispatchers.Default + LRU 文档缓存（独立于分析线程）             │
└───────────────────────────────────────────────────────────────────────────────────┘
```

## 3. 相册变化监听

- `MediaStoreObserver` 用 `ContentObserver(Handler(Looper.getMainLooper()))` 监听
  `MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL)`（API < 29 用 `EXTERNAL_CONTENT_URI`），
  `notifyForDescendants = true`。回调只做防抖，不占用主线程。
- **1.5 秒防抖**：拍照、批量下载、截图会在短时间内连续触发多次 `onChange`，合并为一次对账，
  避免刚插入一张就反复全量查询 MediaStore / 反复唤醒分析循环。
- ContentObserver 只告诉"某处变了"，不携带增删明细。对账时重新查 MediaStore 拿到当前 id 列表，
  用 `AnalysisPlanner.diff(previousIds, currentIds)` 求差，得到真正新增/删除的 id。
  纯逻辑可离线评测（新增、删除、无变化、乱序、重复）。

**触发源（三重保险）**

| 触发 | 作用 |
| --- | --- |
| ContentObserver（主） | 服务存活期间实时、可靠，新照片自动进入分析队列 |
| `BootReceiver`（`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`） | 开机/升级后尽力恢复守护 |
| `MainActivity.onResume → ensureBackgroundWatch()` | 服务未运行则拉起；处理开机启动受限留下的 pending 标记 |

## 4. 自动增量分析

`runUntilSettled()` 是唯一的分析循环：

1. 查相册 → `diff` 检出运行期变化 → 发 `AlbumChanged` 让前端增量刷新列表；
2. 读磁盘结果 → `AnalysisPlanner.plan()`：`pending = 相册 ID − 已落盘 ID`，
   开启 AI 时额外把"只有本地标签、描述为空"的占位结果纳入补齐队列；
3. `plan.complete` 时更新"守护中"状态并**返回，但服务不退出**，继续靠 observer 监听；
4. 否则 `runPlan()` 逐张分析，每张立即 `appendResult()` 落盘；
5. 一轮跑完再次循环对账 —— 分析期间又新增的照片会被下一轮检出，不会漏；
6. 守护开关关闭时退化为一次性分析：跑完 `stopAfterOneShot()` 退出，回到 v3.9.0 行为。

`START_STICKY`：服务被系统回收后由系统重启，intent 为 null 时正好触发一次全量对账续跑，
磁盘结果是权威，重启即自动接着跑。

## 5. 后台分析不影响前端搜索（三层隔离）

### 5.1 线程隔离

- 分析循环跑在 **`ps-analysis` 低优先级单线程**（`Executors.newSingleThreadExecutor` +
  `Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)` → `asCoroutineDispatcher()`）。
- 检索在 `Dispatchers.Default`，主线程只做 O(1) 状态赋值。
- 三者互不抢占，图片解码、EXIF 读取、JSON 落盘不会挤占检索与 UI 的 CPU 时间片。

### 5.2 数据隔离

- 服务只写自己的 `AnalysisResultStore` 实例（磁盘 JSONL）与进程内镜像 `analyzedMetadata`，
  **不碰 ViewModel 的 `metadataCache`**。
- 前端检索读自己的 `ConcurrentHashMap` 快照，两边没有共享锁，不存在写阻塞读。
- **磁盘是唯一权威**：运行期事件只是加速，丢失（App 被划掉、进程回收、缓冲溢出）不会出错，
  下次打开 App `loadPhotos()` 从磁盘读全量自然补齐。

### 5.3 缓存隔离（最关键）

旧版在服务完成/停止时 `syncServiceResults()` 会 `docCache.clear()`（清空最多 6000 张 LRU 文档）
+ `scheduleFilters(0)`（全库重算检索）。常驻服务不断有小批次完成时，这会反复发生。

v3.10.0 改为**增量吸收**：

| 步骤 | 操作 | 复杂度 |
| --- | --- | --- |
| 1 | `metadataCache.putAll(batch)` | O(k)，k = 本批张数（≤20） |
| 2 | `docCache.invalidate(IncrementBuffer.idsToInvalidate(batch))` | O(k)，**绝不全量 clear** |
| 3 | `allPhotos.map { 更新这 k 张 }` | O(N) 但放 `Dispatchers.Default`，不涉及检索计算 |
| 4 | 无活动查询：`filteredPhotos = merged`，**完全不跑检索** | O(1) |
| 5 | 有活动查询：`scheduleFilters(280ms)` 后台防抖重排，可取消、带代次校验 | 不阻塞输入 |

`AnalysisEvents` 用 `SharedFlow`（replay=0、DROP_OLDEST）刻意不做可靠队列：
宁可丢事件，也不让后台分析把几万条结果堆进内存或逼前端做全量重算。
`IncrementBuffer` 负责去重（同 id 后写覆盖）、分批（20 张 / 300ms）与节流判定。

**离线评测结论**（`tools/BackgroundEval.kt`，D 段）：预热 1000 张文档缓存后，
增量失效 5 张 → 缓存仍保留 995 张；对照 `clear()` 会清空为 0；
检索语义在增量前后完全一致（333 → 338 张命中）；连续 20 批增量期间缓存最低仍有 1000 张。

## 6. 配置与用户可控性

| 配置 | 默认 | 位置 |
| --- | --- | --- |
| 后台自动分析（守护常驻） | 开 | 设置 → 后台运行；首页更多菜单；通知栏「停止守护」 |
| 自动继续未完成的分析 | 开 | 设置 → 分析进度（守护关闭时作为降级生效） |
| 云端 AI 描述 | 开（需 API Key） | 设置 → AI 分析设置 |

- 常驻通知为 `IMPORTANCE_LOW` / `PRIORITY_LOW`，`setOngoing(true)`，带「停止守护」action
  与打开 App 的 PendingIntent；分析时显示进度，空闲时显示"本次已自动分析 N 张"。
- 首页状态条在守护空闲时只占极轻一行："后台守护中 · 新增照片会自动分析（不影响搜索）"，
  带「停止」入口；分析进行中显示细进度条；完成且守护关闭时不占空间。
- `android:stopWithTask="false"`：划掉任务卡片不终止守护。

## 7. 系统限制与降级（如实说明）

| 限制 | 表现 | 降级 |
| --- | --- | --- |
| Android 12+ 后台启动前台服务受限 | 开机广播里 `startForegroundService` 可能抛 `ForegroundServiceStartNotAllowedException` | `BootReceiver` catch 后写 `watch_pending`；用户下次打开 App 时 `ensureBackgroundWatch()` 兜底启动 |
| Android 14+ `dataSync` 类型前台服务有时长配额 | 极端情况下系统可能停止服务 | `START_STICKY` 重启 + 磁盘断点续跑，不会丢已分析结果 |
| 厂商省电策略 / 电池优化 | 可能冻结后台服务与 ContentObserver | 打开一次 App 即恢复；文档建议将应用加入电池优化白名单 |
| 云端 AI 消耗流量 | 后台自动分析新照片会调用模型 | 可关闭「后台自动分析」或关闭云端 AI 只用本地识别 |
| 通知权限被拒 | 通知不显示 | 功能不受影响；前台服务仍可运行 |

首次安装、照片权限未授予、全部分析完成且开关关闭时，都不会擅自启动云端 AI。
`wasWatchEverStarted` 标记保证只有用户确实启用过守护，开机广播才尝试恢复。

## 8. 兼容旧版

- 从 v3.9.0 及更早版本覆盖安装：磁盘 JSONL 结果、EXIF 缓存、手动标注地点、设置全部保留，
  **无需重新分析**；守护启动后按 photoId 差集自动续跑未完成的部分。
- 关闭「后台自动分析」后，服务返回 `START_NOT_STICKY`、跑完一轮即退出，
  行为与 v3.9.0 的一次性分析 + 回到前台续跑完全一致。
- 三参数 `startForeground(int, Notification, int)` 是 API 29+ 方法，本版按 `SDK_INT` 分支，
  API 26~28 走两参数重载，避免 `NoSuchMethodError`（顺带修复）。

## 9. 验证

本地 Kotlin 1.9.22 编译 + 6 套离线评测全绿：

| 评测 | 结果 |
| --- | --- |
| `BackgroundEval`（新增） | **46/46 PASS**：相册差集、增量计划、合并去重/分批/节流、**索引隔离**、事件丢失补齐、3 万张性能（计划 11ms / 合并 4ms / 差集 15ms） |
| `V37Eval` | 41/41 |
| `ExifSearchEval` | 25/25，P=R=F1=1.000 |
| `RecallEval` | P=R=F1=0.933 |
| `PerfEval` | 26/26 |
| `ResumeEval` | 35/35 |

`AnalysisEvents` 额外用项目实际依赖的 `kotlinx-coroutines-core-jvm:1.7.3` 单独编译验证通过；
UI / Service / Manifest 层由 GitHub Actions 真实 Gradle 编译 + `apksigner verify` 验证。

## 10. 相关文件

| 文件 | 职责 |
| --- | --- |
| `service/AnalysisService.kt` | 常驻守护、对账循环、增量落盘与事件、通知 |
| `service/MediaStoreObserver.kt` | 相册 ContentObserver + 1.5s 防抖 |
| `service/AnalysisEvents.kt` | 进程内增量事件总线（SharedFlow / StateFlow） |
| `service/IncrementBuffer.kt` | 纯逻辑：结果去重、分批、节流（可离线评测） |
| `service/AnalysisPlanner.kt` | 纯逻辑：`plan()` 差集续跑 + `diff()` 相册变化检测 |
| `receiver/BootReceiver.kt` | 开机 / 升级恢复守护（含降级） |
| `viewmodel/PhotoViewModel.kt` | 增量吸收、索引隔离、守护调度 |
| `ui/screens/HomeScreen.kt` / `SettingsScreen.kt` | 守护状态展示与开关 |
| `data/AppConfig.kt` / `AndroidManifest.xml` | 开关持久化、广播接收器与权限 |
| `tools/BackgroundEval.kt` | 离线评测 |
