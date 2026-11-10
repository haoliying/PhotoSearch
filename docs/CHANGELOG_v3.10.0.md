# v3.10.0 · 后台自动分析 · 相册监听 · 检索隔离

| 项目 | 值 |
| --- | --- |
| 版本名 | 3.10.0 |
| versionCode | 13 |
| 发布日期 | 2026-11-10 |
| 最低 Android | API 26（Android 8.0） |
| 编译目标 | API 34 |
| 升级 | 与所有旧版同一签名，**可直接覆盖安装，无需重新分析** |

## 需求

> 把智能分析做成后台运行，并监控相册变化，当有新图片加入时自动分析，保存分析结果，
> 后台分析时不影响前端搜索照片。

## 旧行为的问题

1. `AnalysisService` 是**一次性前台服务**：跑完一轮就 `stopSelf()`、`START_NOT_STICKY`，
   不常驻；必须回到 App 前台才能续跑。
2. **没有相册变化监听**：新拍/新下载的照片只能靠手动「重新加载相册」或下次打开 App 才发现。
3. 服务完成/停止时触发 `syncServiceResults()` → **全量合并 + `docCache.clear()` 清空几万张
   索引 + 全库重跑检索**。若改成常驻服务不断有小批次完成，会反复清空缓存、卡住前端搜索。
4. 分析跑在 `Dispatchers.Default`，与检索同线程池抢 CPU。

## 改动

### 1. 常驻后台守护 + 相册变化监听

- `AnalysisService` 改为 `START_STICKY` 常驻：分析循环跑完**不 `stopSelf()`**，继续保持监听；
  被系统回收后由系统重启，重启即按磁盘结果自动续跑。
- 新增 `MediaStoreObserver`：`ContentObserver` 监听 `EXTERNAL_CONTENT_URI`（含所有卷），
  **1.5s 防抖**合并拍照/下载的连续回调，避免反复全量查询。
- 新增 `BootReceiver`（开机 / 锁定开机 / 应用升级）尽力恢复守护；
  `MainActivity.onResume → ensureBackgroundWatch()` 兜底拉起，处理系统后台启动受限的 pending 标记。
- 常驻通知 `IMPORTANCE_LOW`，带「停止守护」action；`android:stopWithTask="false"`。
- `AnalysisPlanner.diff()` 纯逻辑求"新增 / 删除 id"，只把真正的新照片通知前端刷新列表。

### 2. 有新图片自动分析并保存

- 相册变化 → 重新查 MediaStore → `AnalysisPlanner.plan()` 按 photoId 差集只挑新增/未完成的照片。
- 每张分析完立即 `appendResult()` 单张落盘（JSONL），磁盘为唯一权威；被强杀最多丢当前一张。
- 一轮跑完再次对账：分析期间又新增的照片会被下一轮检出，不会漏。
- 新照片在相册里立即显示，EXIF 也按增量提取（不重新加载全量缓存）。

### 3. 后台分析不影响前端搜索（三层隔离）

- **线程**：分析跑在 `ps-analysis` 低优先级单线程（`THREAD_PRIORITY_BACKGROUND`），
  检索在 `Dispatchers.Default`，主线程只做 O(1) 状态赋值。
- **数据**：服务只写自己的磁盘 Store 与进程内镜像，不碰 ViewModel 的 `metadataCache`；
  前端读自己的并发快照，无共享锁。磁盘是唯一权威，事件丢了下次冷启动读盘补齐。
- **缓存**：新增 `AnalysisEvents`（SharedFlow/StateFlow）与 `IncrementBuffer`（去重/分批/节流）。
  前端 `absorbIncrement()` 只 `putAll` 这 k 张、只 `docCache.invalidate(这 k 个 id)`，
  **绝不全量 clear**；O(N) 列表更新放后台线程；无查询时完全不跑检索，有查询才 280ms 防抖后台重排。
  删除旧版 `syncServiceResults()` 的"完成即全量合并"路径。

### 4. 用户可控与降级

- 设置 →「后台运行 → 后台自动分析」开关（默认开）即时启停服务；首页更多菜单、通知栏均可停止。
- 关闭后服务返回 `START_NOT_STICKY`、跑完即退，行为与 v3.9.0 一次性分析 + 前台续跑完全一致。
- 如实处理 Android 12+ 后台启动前台服务限制、Android 14 `dataSync` 时长配额、厂商省电策略：
  `START_STICKY` 重启 + 磁盘断点续跑 + 打开一次 App 即恢复，不丢已分析结果。
- 顺带修复：三参数 `startForeground()` 在 API 26~28 会 `NoSuchMethodError`，改为按版本分支。

### 5. 文档与评测

- 新增 `docs/BACKGROUND_ANALYSIS.md`（架构、隔离机制、系统限制与降级）。
- 应用内「版本说明」加 v3.10.0；「操作手册」新增「三、后台自动分析与相册监听」并顺延后续章节；
  设置页、首页状态条文案同步。
- 新增 `tools/BackgroundEval.kt`：46 项断言。

## 验证

| 评测 | 结果 |
| --- | --- |
| `BackgroundEval`（新增） | **46/46 PASS** |
| `V37Eval` | 41/41 PASS |
| `ExifSearchEval` | 25/25 PASS，P=R=F1=1.000 |
| `RecallEval` | P=R=F1=0.933 |
| `PerfEval` | 26/26 PASS |
| `ResumeEval` | 35/35 PASS |

关键断言：预热 1000 张文档缓存后，增量失效 5 张仍保留 995 张（对照 `clear()` 清空为 0）；
检索语义在增量前后完全一致（333 → 338 张命中）；连续 20 批增量期间缓存最低仍 1000 张；
3 万张照片计划 11ms、合并 4ms、变化检测 15ms。

`AnalysisEvents` 另用项目实际依赖的 `kotlinx-coroutines-core-jvm:1.7.3` 单独编译验证通过；
UI / Service / Manifest 由 GitHub Actions 真实 Gradle 编译与 `apksigner verify` 验证。

## 升级说明

- 从 v3.9.0 或更早版本覆盖安装：分析结果、EXIF 缓存、手动标注地点、设置全部保留。
- 首次打开新版本后，守护会自动启动；已全部分析完成的历史照片不会重跑。
- 云端 AI 开启时，后台自动分析新照片会调用模型并消耗流量；如不希望，可关闭「后台自动分析」
  或关闭云端 AI 只用本地 ML Kit 识别。
- 不新增任何远程依赖，构建链路与 v3.9.0 一致。
