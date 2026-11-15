# v3.11.4 · 搜索准确度可调：标签与关键字匹配度门槛

> 发布日期：2026-11-15 · versionCode 18 · 可直接覆盖安装（签名与历史版本一致）

## 一、需求

用户原话：**「增加准确度调整，标签和搜索关键字的匹配度要大于等于准确度才算匹配」**。

即：用户可以设置一个"准确度"，检索时计算照片标签 / 描述与搜索关键字之间的匹配度，只有**匹配度 ≥ 准确度**才算这条证据命中、照片才进入结果。数值越高越严格，结果越少越精确。

## 二、改造前的状态

判定「某条证据算不算命中」的阈值全部写死在 `SearchEngine` 里，用户无法干预：

| 通道 | 常量 | 阈值 | 说明 |
| --- | --- | --- | --- |
| 规范概念 | `MIN_EVIDENCE` | 0.50 | 车 / 人 / 海滩等归一概念的证据强度 |
| 全文检索 | `TEXT_MIN_EVIDENCE` | 0.55 | 描述、关键词、中文标签、EXIF 地名等 |
| 多弱证据叠加 | 复用 `TEXT_MIN_EVIDENCE` | 0.55 | 多条 0.44~0.50 弱证据合起来 |
| 同义词扩展 | `EXPAND_MIN_EVIDENCE` | 0.62 | 「汽车」→「车辆 / vehicle」兜底 |

问题有三：

1. 想更精确只能改代码，用户没有控制权；
2. 四组阈值彼此独立，即使手动改一处，其他通道仍会"放水"；
3. 之前版本提供的「本地准确度增强」是**提高召回**（把弱信号编成标签、扩展同义词），并没有提供**收紧结果**的手段。

## 三、方案：统一门槛 = max(通道基线, 用户准确度)

新增纯 Kotlin 对象 `search/MatchAccuracy.kt`，把用户设定的百分比映射到上述判定：

```kotlin
fun coerce(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
fun asFraction(percent: Int): Float = coerce(percent) / 100f
fun threshold(base: Float, percent: Int): Float = maxOf(base, asFraction(percent))
fun meets(score: Float, percent: Int): Boolean = score + EPS >= asFraction(percent)
```

`SearchEngine.search()` 一次查询内算出并复用三个阈值，传给 `evaluateCondition()`：

```kotlin
val thresholds = MatchThresholds(
    concept = MatchAccuracy.threshold(MIN_EVIDENCE, accuracyPercent),
    text    = MatchAccuracy.threshold(TEXT_MIN_EVIDENCE, accuracyPercent),
    expand  = MatchAccuracy.threshold(EXPAND_MIN_EVIDENCE, accuracyPercent)
)
```

四条命中路径全部改用 `thresholds.*`，不再使用裸常量：

```kotlin
if (w >= thresholds.concept) return Triple(true, w, "concept:${concept.name}")
if (s >= thresholds.text)    return Triple(true, s, "text:$term")
if (agg >= thresholds.text)  return Triple(true, agg.coerceAtMost(1f), "text:multi(...)")
if (s >= thresholds.expand)  return Triple(true, s * EXPAND_SCORE_FACTOR, "expand:$term")
```

### 为什么是 `max` 而不是直接替换阈值

| 考虑 | 结论 |
| --- | --- |
| **升级兼容** | 默认 50% 恰好不高于任何通道基线（0.50 / 0.55 / 0.62），因此 `max` 后阈值与 v3.11.3 **逐位相同**，所有既有搜索结果和离线评测都不变。若直接替换，全文门槛会从 0.55 降到 0.50、扩展从 0.62 降到 0.50，反而召回一堆弱证据，精确率倒退。 |
| **只收紧不放宽** | 调低准确度不会把 0.3 的噪声标签放进结果。准确度负责「更准」，不负责「更全」——想提升召回应开启本地准确度增强或让大模型补描述。 |
| **单调可感知** | 数值越大，四条通道门槛同步抬高，结果单调减少，用户拖动滑杆能立刻看到变化。 |
| **无漏洞** | 四条路径同时收紧，不会出现「概念通道严了、全文通道还在放水」。 |

### 豁免范围

不属于「标签与关键字匹配度」的条件不受准确度影响，否则会产生严重误伤：

- **时间 / 地点**：结构化硬过滤，本身是精确匹配。若也套用准确度，把值调到 90% 后按时间筛选会直接 0 结果（纯筛选结果固定分 0.6）。现在 `filtersOnly` 分支固定返回，`matchPercent = 100`。
- **排除条件**（「没有汽车」）：只做否决，不参与召回打分，保持原 `strictMatch` 逻辑。

## 四、档位与取值

| 档位 | 准确度 | 效果 |
| --- | --- | --- |
| 标准（默认） | 50% | 与 v3.11.3 完全一致，保留所有可信匹配 |
| 较准 | 65% | 剔除把握不大的照片 |
| 严格 | 80% | 只保留高置信度命中 |
| 极严格 | 90% | 近乎确定的命中，结果最少 |

滑杆范围 `50..95`，步长 5%。`MIN_PERCENT = 50` 即引擎质量基线，`MAX_PERCENT = 95`。极严格档可能返回 0 张：纯端侧标签的证据强度上限约 0.8~0.9（ML Kit 标签 `0.45 + 0.35×confidence`、对象检测 `0.50 + 0.30×confidence`），只有 VLM 结构化关键词（1.0）能达到更高，属预期行为，退回 65% 或 50% 即可。

## 五、改动文件清单

| 文件 | 改动 |
| --- | --- |
| `search/MatchAccuracy.kt` | **新增**：范围收敛、百分比换算、`threshold()` / `meets()` / `percentOf()`、4 个档位、说明文案。纯 Kotlin，无 Android 依赖，可离线评测。 |
| `search/SearchEngine.kt` | `search()` 新增 `accuracyPercent` 参数（默认值 → 旧调用与评测零改动）；新增 `MatchThresholds` 一次计算复用；四条命中路径改用动态阈值；`SearchResult` 新增 `matchPercent`；`filtersOnly` 豁免并置 100。 |
| `data/AppConfig.kt` | 新增 `KEY_MATCH_ACCURACY` 与 `getMatchAccuracy()` / `setMatchAccuracy()`，读写时统一 `MatchAccuracy.coerce()`。 |
| `viewmodel/PhotoViewModel.kt` | `PhotoSearchState` 新增 `matchAccuracy` / `matchPercents`；`computeFilters()` 传入准确度并收集结果匹配度；新增 `setMatchAccuracy()`（只重跑检索、不清 `docCache`）；`loadPhotos()` 与 `onVlmConfigChanged()` 读取配置。 |
| `ui/screens/SettingsScreen.kt` | 新增「搜索准确度（标签匹配门槛）」卡片：滑杆 + 4 档 FilterChip + 当前值/说明；保存时写入配置。 |
| `ui/screens/HomeScreen.kt` | 顶部结果栏显示「N 张结果 · 准确度 XX%」；列表视图每项新增「综合匹配度 XX%」（网格保持纯缩略图）。 |
| `MainActivity.kt` | 向 HomeScreen 传递 `matchAccuracy` / `matchPercents`。 |
| `ui/screens/AboutScreen.kt` / `HelpScreen.kt` | 版本历史新增 v3.11.4；操作手册新增第十四章与 FAQ 条目。 |
| `app/build.gradle.kts` | `versionCode 18` / `versionName 3.11.4`。 |
| `docs/*`、`tools/V315Eval.kt` | 版本说明、总览、操作手册与离线评测。 |

## 六、性能与数据影响

- **查询期参数**：准确度只影响 `evaluateCondition()` 的比较值，不改变 `PreparedDoc`（概念证据 + 全文索引）的内容，因此调整后 `docCache` 无需失效，不会重建几万张照片的索引。
- **只重跑一次检索**：`setMatchAccuracy()` 触发 `scheduleFilters(0)`，仍是 280ms 防抖 + `Dispatchers.Default` 后台执行 + 代次校验，输入与滚动不卡顿。
- **零重分析**：不改动已落盘的分析结果，升级后历史分析结果完全复用。
- **默认无差异**：50% 时四条通道阈值与旧版相同，排序分数计算未改。

## 七、验证

`tools/V315Eval.kt` 覆盖：

1. 范围收敛、档位常量与百分比换算；
2. `threshold(base, pct)` 的 `max` 语义与单调性；
3. `meets()` 的 `>=` 边界与浮点容差；
4. **默认 50% 兼容性**：概念 / 全文 / 叠加 / 扩展四处阈值与 v3.11.3 常量逐一相等；
5. 调高准确度后模拟检索结果单调减少；
6. `filtersOnly` 与排除条件豁免、纯筛选 `matchPercent = 100`；
7. `percentOf()` 与结果匹配度展示；
8. 与 `SearchEngine` 的联合断言（各通道在不同准确度下的命中/落选）。

历史 9 套离线评测（RecallEval、V311Eval、V312Eval、V313Eval、V314Eval、ExifSearchEval、PerfEval、ResumeEval、BackgroundEval）用新源码全部复跑通过；APK 通过 SHA256、ZIP CRC、dex 数量与 v1/v2/v3 签名校验。

## 八、升级与回退

- 直接覆盖安装，**无需卸载、无需重新分析照片**；所有历史版本使用同一签名证书。
- 升级后默认停在 50%（标准档），行为与 v3.11.3 一致；想更精确再去设置页调高。
- 回退：安装旧版 APK 同样可覆盖；配置键 `match_accuracy_percent` 对旧版无影响。
