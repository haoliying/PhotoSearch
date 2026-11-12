# v3.11.2 更新说明 · 真并发提速 / 后台持续分析 / 进度平滑 / 暂停继续

> 发布日期：2026-11-12 · versionCode 16 · 与 v3.10.0 ~ v3.11.1 同一签名，可直接覆盖安装，既有分析结果全部保留。

## 一、这次解决什么

| 反馈 | 现象 | 结论 |
| --- | --- | --- |
| 速度还是不快 | v3.11.1 调了并发仍感觉慢 | 并发调度是「整块屏障」，被最慢的一张拖住；上限只有 4 / 默认 2，CPU 没吃满 |
| 感觉在后台就不分析了 | 熄屏/切后台后进度长时间不动 | 服务没持有 CPU 唤醒锁，CPU 休眠后前台服务虽在但计算被冻结；且空闲时只靠相册监听回调，没有兜底对账 |
| 分析进度每次会跳动几百个 | 数字忽大忽小、一次跳几百 | 进度由服务与前端两条协程并发写、口径不一致，且 `emitIdle` 直接把进度设成 100% 造成回跳 |
| 新增需求 | — | 增加「暂停分析 / 继续分析」按钮 |

## 二、速度：从「整块屏障」改成「滑动窗口真并发」

### 2.1 为什么 v3.11.1 还是慢

`v3.11.1` 把待分析队列切成每块 N 张，块内 `async` 并行后 `awaitAll()` 收尾。问题是**块耗时 = 块内最慢那张**：手机相册里照片大小与内容差异很大，一张 4000×3000 的夜景可能比一张截图慢 10 倍，其余线程只能空转等它。同时并发上限 4、默认 2，中端机 4~8 核根本没吃满；分析还被强制放在 `Dispatchers.Default` 上，与检索线程挤同一个池。

### 2.2 v3.11.2 的改法

1. **滑动窗口**：固定 N 个 worker 从一个 `Channel` 取任务，任何一张完成立刻取下一张，全程保持 N 张 in-flight，消除块屏障。
2. **并发上限 1..8，默认 4**；`AnalysisScheduling.slidingMakespan()` / `batchedMakespan()` 是纯函数，可离线定量对比。
3. **专用低优先级固定线程池**：分析不再强制切 `Dispatchers.Default`，而是落在 `THREAD_PRIORITY_BACKGROUND` 的独立池，与搜索线程彻底隔离 —— 全速分析时搜索框仍不卡。
4. **解码最大边长 1024 → 768**：ML Kit 内部本就会把输入缩放到 224~320 再推理，标签精度几乎不变，但解码时间与内存明显下降。
5. **批量重分析**（多选 → 重新分析）同步改为滑动窗口，不再按块等待。

### 2.3 离线实测（同一组单张耗时，见 `tools/V313Eval.kt`）

| 调度 | 完工时间 |
| --- | --- |
| 整块屏障 4 路 | 4335 ms |
| 滑动窗口 4 路 | **1705 ms（提速 60%）** |
| 滑动窗口 8 路 | 1500 ms |
| 1 路（逐张串行） | 5865 ms |

> 这是调度模型耗时，不是真机绝对时间；它证明的是「同样并发数下，去掉块屏障能显著减少 CPU 空转」。真机还受 IO、ML Kit 模型加载、系统调度影响。

## 三、后台持续分析：唤醒锁 + 心跳对账

- 新增 `android.permission.WAKE_LOCK`，分析期间持有 `PARTIAL_WAKE_LOCK`（单次最长 30 分钟，跑完 / 暂停 / 空闲即释放），**熄屏后 CPU 不再被冻结**；
- 服务跑完一轮且守护开启时**不再退出循环**，进入 60 秒低频心跳：只查 MediaStore 的 id 差集（不读分析结果 JSONL），发现新照片立刻回到对账续跑。即使部分 ROM 丢掉 `ContentObserver` 回调也不会漏；
- 通知栏实时显示「正在分析 x / y」与暂停 / 继续动作，可直接确认后台是否在跑；
- 设置页新增「打开电池优化设置」入口。国产 ROM（小米 / 华为 / OPPO 等）常有额外后台限制，需手动把应用设为「不受电池优化限制 / 允许后台运行」。

## 四、进度不再跳几百：单一数据源

旧实现里 `AnalysisService.analysisState` 写 `progress = 本轮 done`，而 `PhotoViewModel.absorbIncrement()` 又用 `metadataCache` 全库 `count` 写 `doneCount / pendingCount`，两条协程各自基于旧快照 `copy()`，互相覆盖；`emitIdle()` 还把进度直接设成 `total`（100%），下一轮又回跳。

现在：

- 进度**只由服务侧协调协程逐张 `done++`**，前端 `absorbIncrement()` 只更新列表与缓存，完全不碰进度字段，且基于最新 state 做 `copy`；
- `AnalysisControl.monotonic()` 保证数值只增不减（跨轮对账、回前台从磁盘补齐都不会回跳）；
- `AnalysisControl.shouldEmit()` 200ms 时间节流 + 累计 8 张兜底，收尾 `force` 必发最终值；
- 实测 3000 张逐张上报：**相邻两帧最大跳变 7 张**，最终值必达。

## 五、暂停分析 / 继续分析

| 入口 | 位置 |
| --- | --- |
| 暂停 | 首页分析状态条「暂停分析」、后台守护行「暂停」、通知栏「暂停分析」 |
| 继续 | 首页暂停状态条「继续分析」、通知栏「继续分析」 |

- 暂停状态写进 `SharedPreferences`，**服务被系统回收重建后仍保持暂停**，开机 / 心跳都不会偷偷恢复；
- `AnalysisControl.transition()` 状态机保证 `PAUSED + SETTLE` 仍是 `PAUSED`；
- 正在处理的照片会先落盘再挂起，已分析结果不丢；暂停期间释放唤醒锁、不再取新任务；
- 继续时立刻唤醒心跳重新对账，按 `photoId` 差集只处理剩余照片，**不重复、不从头开始**；若守护已被关掉会自动重新拉起；
- 暂停时守护仍常驻（相册监听、通知都在），只是不处理照片。

## 六、改动文件

| 文件 | 改动 |
| --- | --- |
| `service/AnalysisScheduling.kt` | 并发上限 8 / 默认 4；新增完工时间模拟与滑动窗口时间线；`DECODE_MAX_PX = 768` |
| `service/AnalysisControl.kt` | **新增**：进度节流 / 单调 / 暂停状态机 / 唤醒锁与心跳常量（纯 Kotlin） |
| `service/AnalysisService.kt` | 重写调度：滑动窗口 Channel、专用低优先级池、WakeLock、60s 心跳、逐张进度、暂停门控、通知栏动作、循环代次号防收尾竞态 |
| `service/AnalysisEvents.kt` | `WatchState` 增加 `paused`（带默认值，兼容旧构造） |
| `ml/EnhancedImageAnalyzer.kt` | 去掉强制 `Dispatchers.Default`；解码边长降到 768 |
| `data/AppConfig.kt` | 新增 `isAnalysisPaused / setAnalysisPaused`；并发说明更新 |
| `viewmodel/PhotoViewModel.kt` | 新增暂停 / 继续入口；`absorbIncrement` 不再写进度；批量重分析改滑动窗口 |
| `ui/screens/HomeScreen.kt` | 分析状态条与守护行新增暂停 / 继续按钮 |
| `ui/screens/SettingsScreen.kt` | 并发改 1 / 2 / 4 / 8 四档；增加电池优化设置入口 |
| `MainActivity.kt` | 传递暂停 / 继续回调 |
| `ui/screens/AboutScreen.kt` / `HelpScreen.kt` | 版本说明与操作手册新增 v3.11.2、第十二章 |
| `AndroidManifest.xml` | 新增 `WAKE_LOCK` 权限 |
| `app/build.gradle.kts` | versionCode 16 / versionName 3.11.2 |
| `tools/V313Eval.kt` | **新增** 42 项离线评测 |
| `tools/V312Eval.kt` | 并发常量断言改为引用常量（v3.11.2 上调后仍可复跑） |

## 七、验证结果

| 评测 | 结果 |
| --- | --- |
| V313Eval（新增） | **42 / 42** |
| V312Eval | 21 / 21 |
| V311Eval | 63 / 63 |
| RecallEval | MACRO_F1 = 0.933（无语义回归） |
| V37Eval | 41 / 41 |
| ExifSearchEval | F1 = 1.000 |
| PerfEval | 26 / 26 |
| ResumeEval | 35 / 35 |
| BackgroundEval | 46 / 46 |

10 万张照片调度模拟 22 ms；3 万张照片冷检索 449 ms / 缓存命中 333 ms。

## 八、升级建议

- 直接覆盖安装，**不需要重新分析**，历史结果全部保留；
- 装好后到「设置 → 分析速度」确认是 4 路（默认），发烫或前台有感知就退回 1 路；
- 若后台仍被限制，用设置页的「打开电池优化设置」把应用加入白名单；
- 想临时停手用首页或通知栏的「暂停分析」，回来点「继续分析」即可，不会重跑已分析的照片。
