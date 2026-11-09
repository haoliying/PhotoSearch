# 大数据量检索性能设计与排查笔记（v3.8.0）

本文记录「几万张照片下搜索框假死」的定位过程、架构约束与容量建议，供后续开发避免回归。

## 1. 三条铁律

1. **输入路径上不允许有任何 O(N) 计算**。文本框 `onValueChange` 到回显之间只能是本地状态赋值；任何全库遍历必须防抖 + 后台 + 可取消。
2. **与查询无关的计算只做一次**。照片的概念证据、全文索引只依赖元数据与 EXIF，不依赖查询词，必须跨查询缓存并按 id 失效。
3. **状态更新不能携带全量数据副本**。几万条 Map 每批 `toMap()` 是 O(N²)；用同一并发 Map 引用 + 版本号触发刷新。

## 2. 原始调用链（v3.7.0）

```
OutlinedTextField.onValueChange
  → viewModel.search(q)                    [Main]
  → applyFilters()                         [Main]
      → IntentParser.parse
      → SearchEngine.search(allPhotos)      [Main, 同步]
          for each photo:                  ← N = 30000
              fromMetadata()               ← 30 概念 × 10 词表 × N
              buildSearchIndex()           ← 15 次 add × 46 次 replace × N
              for each condition:
                  index.score(term)        ← 每次编译 Regex × N × 条件数
          sortedByDescending()             ← O(N log N)
      → filteredPhotos = results.map{}     [Main，全量新列表]
      → StateFlow 全屏重组
```

按每个字符触发一次，N=30000 时主线程占用可达十几秒，必然 ANR。

## 3. 新调用链（v3.8.0）

```
OutlinedTextField.onValueChange
  → 本地 text state（立即回显，O(1)）
  → viewModel.search(q)                    [Main, O(1)]
      → state.copy(searchQuery = q)
      → scheduleFilters(280ms)
          → cancel 旧 Job / generation++
          → withContext(Default):
              SearchEngine.search(..., cache, isActive)
                  for each photo:
                      docCache.get(id)     ← 命中直接复用，未命中现算一次
                      score()              ← 无正则，分桶命中即返回
              count(noTime / noLoc)
          → 主线程一次性写回结果
```

取消信号每 256 张检查一次，旧任务抛 `SearchCancelledException`，由 `computeFilters` 捕获转成 `null` 丢弃；`generation` 再做第二层兜底，避免竞态下旧结果覆盖新结果。

## 4. 索引结构

旧版 `SearchIndex` 保存 `List<Pair<String, Float>>`（每张照片 50~100 条），查询时遍历全部条目取命中的最大权重。

新版按权重分桶：

```
buckets: List<Pair<Float, String>>   // 权重降序
每个桶 = 同权重的所有 scrub 后文本，用 \u0001 拼接
score(term): 从高权重桶往下找，首个命中即返回该桶权重
```

等价性：旧版返回所有命中条目中的最大权重；降序桶第一个命中即为该值。同权重合并进一个桶，不影响结果。分隔符是控制字符，查询词经切词不含该字符，不会跨条目误命中。

配套优化：
- `containsAsciiWord()`：`indexOf` + `[a-z]` 边界判断，等价于 `(?<![a-z])word(?![a-z])`，零正则编译。
- `SCRUB_REGEX`：把 46 次 `String.replace` 合成一次扫描（否定/不确定句式优先，噪声词按长度降序）。
- `normalize()` 结果 LRU 缓存（4096）：标签与关键词在几万张照片间高度重复。
- `CAR_CONFUSABLE_REGEX`：24 次 replace 合成一次。

以上均通过 `PerfEval.kt` 的「新旧逐项等价」断言。

## 5. 缓存与失效

| 缓存 | 上限 | 失效时机 |
| --- | --- | --- |
| `SearchDocCache` | 6000 条（LRU，可配） | 单张重分析 / 手动标注地点 → `invalidate(ids)`；相册重载、EXIF 扫描结束、分析完成、清空分析结果 → `clear()` |
| `normalize()` 结果 | 4096 条（LRU） | 纯函数，无需失效 |
| `exifMap` | 全量 ConcurrentHashMap | 同一引用；`exifVersion++` 触发 UI 刷新 |

**为什么不全量缓存几万张？** 每张 PreparedDoc 约 10~25 个字符串，3 万张约 60~100 MB，叠加原图缓存容易 OOM。LRU 6000 条约 15~25 MB；未命中现算在后台线程仅秒级，用户无感。

## 6. 存储：从 O(N²) 到 O(N)

| 阶段 | 旧实现 | 新实现 |
| --- | --- | --- |
| EXIF 落盘 | 每 25 张序列化整个 Map 成一个 JSON | `photo_exif_v3.jsonl` 每 200 张追加新增行 |
| 分析结果落盘 | 每 segment 序列化全部已分析条目 | `analysis_results_v3.jsonl` 只追加新增 id；`analysis_meta_v3.json` 存计数 |
| 手动标注 | 读全量 → 改 → 写全量 | 追加一行，读取时后写覆盖 |
| 旧数据 | — | 首次写入时一次性迁移；读取兼容旧单文件 |

行式文件的崩溃恢复：读取按行解析，跳过损坏行；缺失部分下次扫描自动补齐。

## 7. 分析期间的隔离

- Service 端：进度与通知合并节流（20 张 / 400ms）。
- ViewModel 端：分析期间 `collect` 只更新 `isAnalyzing / progress / total / hasAnalyzed`，**不 map 照片列表、不跑检索**；`isComplete` 时做一次 `syncServiceResults()` 全量合并并清空索引缓存。
- `AnalysisService.analyzedMetadata` 改为 `ConcurrentHashMap`，消除 Service(默认线程) 与 ViewModel(主线程) 并发读写普通 HashMap 的风险。

## 8. 容量建议与已知边界

- 已验证规模：30,000 张照片，冷启动全库检索约 2.2 s（后台线程），缓存命中约 26 ms。
- 10 万张量级：MediaStore 首次扫描与内存中的 `PhotoItem`/`PhotoExif` 列表会线性增长，但仍不阻塞输入；若需进一步优化，下一步应引入**分页加载**（按 `DATE_TAKEN` 分批查询）与**倒排索引**（term → photoId 位图），把单次检索从 O(N) 降到 O(命中数)。
- 分析（AI/VLM）仍是逐张的耗时任务，与检索性能无关；分析期间请不要对全库做高频筛选切换。
- `LazyVerticalGrid` / `LazyColumn` 已用 `key = { it.id }`，`AsyncImage` 固定采样尺寸（网格 256 / 列表 176），滚动内存与照片总数无关。

## 9. 复现实测

```bash
# 本地 Kotlin 1.9.22 + Android 桩
kotlinc -d out Stub_*.kt <data/search 源码> tools/PerfEval.kt
java -Xmx1g -cp out:kotlin-stdlib.jar PerfEvalKt
```

输出包含冷启动、缓存预热、缓存命中、连续输入、联动查询、取消六组计时与 26 项断言。
