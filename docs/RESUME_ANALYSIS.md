# 断点续分析技术说明（v3.9.0）

## 1. 问题复现

用户反馈：相册照片较多（几万张）时，分析经常中断；下次打开应用又要从第一张重新开始，导致永远跑不完。

## 2. 根因

### 2.1 v3.8.0 及以前的续跑逻辑

```kotlin
// AnalysisService.runAnalysis() 旧代码
val saved = resultStore.load()
if (saved != null && !saved.isComplete && saved.totalPhotos == photos.size) {
    analyzedMetadata.putAll(saved.metadataMap)
    startIndex = saved.analyzedCount        // 把「张数」当列表下标
    isResumed = true
} else {
    if (saved != null && saved.totalPhotos != photos.size) {
        resultStore.clear()                 // 照片数一变，全部结果删光
    }
    startIndex = 0                          // 从头
}
...
val segment = photos.subList(segmentStart, segmentEnd)
...
resultStore.saveSegment(..., metadataMap = analyzedMetadata)   // 每个 segment 结束才落盘
```

### 2.2 由此产生的四条「从头」路径

| # | 触发条件 | 后果 |
| --- | --- | --- |
| 1 | 相册张数变化（新拍一张、删几张、MediaStore 扫描数波动） | `saved.totalPhotos != photos.size` → `clear()` 删除全部已分析结果 |
| 2 | 上一轮已标记 `isComplete = true`，用户再点一次分析 | `!saved.isComplete` 不成立 → `startIndex = 0`，全部重跑 |
| 3 | 新增照片插到列表中间 / MediaStore 排序变化 | `startIndex = analyzedCount` 按下标错位，重复分析已完成的、漏掉未完成的 |
| 4 | 前台服务被系统限额杀掉、OOM、关机、用户划掉任务 | 每 200 张才落盘一次，整段 200 张回退；强杀时走不到 `catch`，`onDestroy` 也没有保存 |

补充因素：

- `ViewModel.canResume` 同样要求 `saved.totalPhotos == photos.size`，照片数一变连「继续」按钮都不显示。
- `START_NOT_STICKY`：服务被杀后不自动重启，只能靠用户手动再点。
- VLM 单张网络请求很慢，一个 200 张的 segment 可能跑几十分钟，中断概率极高。
- VLM 失败时写入 `isAnalyzed = true` 的占位结果，之后再也不会自动补齐，除非手动「重新分析全部」。

## 3. 设计原则

1. **唯一权威是 photoId**：不用下标、不用计数、不要求照片总数相等。
2. **落盘粒度 = 单张**：任何时刻被终止，最多丢失当前正在处理的那一张。
3. **完成标记不是续跑门槛**：新增照片必须能触发增量分析。
4. **决策逻辑与 Android 生命周期解耦**：纯 Kotlin，可离线评测。
5. **不静默偷跑**：自动续跑只在「已经开始过但没跑完」时触发，且提供开关。

## 4. 实现

### 4.1 `AnalysisPlanner`（纯 Kotlin）

```kotlin
val doneIds = analyzed.filter { it.value.isAnalyzed }.keys
val pending = uniquePhotoIds.filter { id ->
    id !in doneIds || (vlmEnabled && analyzed[id]?.description.isNullOrBlank())
}
```

- 用 `LinkedHashSet` 去重并保持相册顺序；
- `doneCount = total - pending.size`，`resumed = done > 0 && pending.isNotEmpty()`，`complete = pending.isEmpty()`；
- 开启 AI 时把「有本地标签但描述为空」的占位结果纳入补齐队列；关闭 AI 时不重跑它们；
- 不变量：`doneCount + pendingCount == totalCount`。

### 4.2 `AnalysisResultStore`（行式 JSONL）

| 接口 | 作用 |
| --- | --- |
| `appendResult(id, metadata)` | 单张立即追加一行，O(1)；允许同 ID 覆盖写（`load()` 后写覆盖） |
| `appendResults(ids, map)` | 只写指定 ID，供批量/单张重分析使用，避免全表 `filterKeys` |
| `markProgress(total, done)` | 只改 meta 的 `isComplete=false` + 计数 + 时间戳 |
| `markComplete(total, done)` | 只改 meta 的 `isComplete=true` |
| `saveSegment(...)` | 保留的兼容入口，新代码不再全表调用 |
| `load()` | `analyzedCount` 取行文件实际解析出的条数，**忽略 meta 里可能过期的计数** |

meta 文件记录 `version / totalPhotos / analyzedCount / isComplete / lastUpdate`，仅用于界面提示，不参与续跑判定。

### 4.3 `AnalysisService`

```kotlin
val saved = resultStore.load()
analyzedMetadata.clear()                    // 清掉进程级静态残留
analyzedMetadata.putAll(saved?.metadataMap)
val plan = AnalysisPlanner.plan(photos.map { it.id }, analyzed, useVlm)
if (plan.complete) { markComplete(...); return }   // 不会重跑

for (id in plan.pendingIds) {
    currentCoroutineContext().ensureActive()
    val metadata = analyzeOne(byId[id], useVlm, ...)
    analyzedMetadata[id] = metadata
    resultStore.appendResult(id, metadata)  // 每张落盘
    if (++sinceFlush >= metaFlushStep) resultStore.markProgress(photos.size, done)
    emitProgress(done, photos.size, ...)
}
resultStore.markComplete(photos.size, done)
```

- 删除 `startIndex`、`subList`、`clear()` 分支；
- 原「分段大小」设置改名为「进度标记刷新间隔」，只控制 meta 写入频率；
- VLM 连续失败 20 次 → `vlmDegraded = true`，本轮剩余照片只写本地标签，下次由 Planner 自动补齐，避免几万张各退避几秒；
- `finally` 与 `onDestroy()` 在 `!completed` 时写 `markProgress`，保证中断标记；
- `START_NOT_STICKY`：Android 12+ 后台启动前台服务受限，且静默重启会偷跑流量，因此改由应用回前台触发自动续。

### 4.4 自动续跑

```
MainActivity.onResume()
  → viewModel.reloadIfEmpty()
  → viewModel.autoResumeIfNeeded()
      resumeRequested = true
      若 allPhotos 已加载 → performAutoResume()
      否则等 loadPhotos() 完成后再 performAutoResume()
  → performAutoResume():
      开关开启 && 未在分析 && !isRunning && plan.resumed → startAnalysis()
```

`plan.resumed` 要求 `doneCount > 0 && pendingCount > 0`，因此：

- 首次安装（done=0）不会擅自启动分析；
- 全部完成（pending=0）不会重复启动；
- 中断或新增照片才会自动续。

设置项：`AppConfig.isAutoResumeEnabled()`，默认 `true`，可在设置页关闭。

### 4.5 UI 口径统一

`PhotoViewModel` 用同一个 `AnalysisPlanner` 计算 `doneCount / pendingCount / canResume`，与 Service 完全一致。状态条：

| 状态 | 文案 |
| --- | --- |
| 分析中 | `正在分析：已完成 X / Y，还剩 Z 张（可随时退出，下次自动继续）` |
| 可续跑 | `已分析 X 张，还有 Y 张待分析` + `下次进入应用会自动继续，不会从头开始` + 「继续」「全部重来」 |
| 未分析 | `尚未分析照片内容，无法按内容搜索` + 「怎么用」「开始分析」 |
| 全部完成 | 不占空间，把屏幕留给照片 |

## 5. 评测

`tools/ResumeEval.kt`，35 项断言全部通过，覆盖：

- 首次分析、任意中断点（1 / 7 / 49 / 128 / 333 / 499 张）精确停在下一张；
- 新增照片、删除照片、乱序、`isComplete=true` 后新增照片；
- AI 描述补齐与「补齐后不再重试」；
- 重复 ID 去重、坏行（`isAnalyzed=false`）、清空重置；
- 3 万张照片计划耗时 **11ms**，只排 1 万张未分析。

回归：`PerfEval` 26/26、`V37Eval` 41/41、`ExifSearchEval` 25/25（F1=1.000）、`RecallEval` 0.933。

## 6. 兼容与降级

| 场景 | 处理 |
| --- | --- |
| 从 v3.8.0 升级，已有 `analysis_results_v3.jsonl` | 直接读取，按 ID 差集续跑，无需重新分析 |
| 从更早版本升级，只有 `analysis_results.json` | `ensureMigrated()` 首次写入时一次性转成行式文件 |
| 行文件末尾有崩溃留下的半行 | `load()` 逐行 try/catch 跳过 |
| 用户关闭自动续跑 | 首页显示「继续」按钮，手动触发同样是断点续跑 |
| 用户主动「重新分析全部」 | `resultStore.clear()` + `AnalysisService.reset()`，明确从头开始 |
| 云端 AI 不可用 | 降级为本地分析，占位结果下次自动补齐 |
| 应用被卸载 | 私有目录清除，属预期行为 |

## 7. 已知边界

- 自动续跑需要应用回到前台；若希望完全后台自动续，需改 `START_STICKY` 并处理 Android 12+ 前台服务启动限制与流量消耗，当前版本刻意不做。
- 占位结果（AI 描述为空）每次开启 AI 都会尝试补齐；若某张照片始终无法生成描述（如纯黑图），它会在每次启动时进入补齐队列，但数量通常远小于总量。
- 结果文件按 ID 追加，长期反复重分析同一张会产生多行；`load()` 取最后一行，文件只会缓慢增长。
