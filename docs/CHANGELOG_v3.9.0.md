# v3.9.0 更新说明

- 版本名：`3.9.0`，versionCode `12`
- 主题：**断点续分析**、应用内**版本说明**与**操作手册**
- 基线：v3.8.0（`dfee959e`）

## 一、修复：照片多时分析中断后从头重来

### 根因

旧版按「已分析张数」当相册下标续跑，并要求 `saved.totalPhotos == photos.size`：

- 照片数一变就 `resultStore.clear()` 删掉全部结果；
- `isComplete` 后再点分析走 `startIndex = 0` 全部重跑；
- 新增照片插到中间 / 排序变化会让下标错位；
- 每 200 张才落盘一次，进程被强杀时整段回退；`START_NOT_STICKY` 不会自动重启。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `service/AnalysisPlanner.kt`（新增） | 纯 Kotlin 决策核心：按 photoId 差集算待分析队列，支持增删/乱序/去重/AI 补齐 |
| `data/AnalysisResultStore.kt` | 落盘粒度降到单张（`appendResult`）；新增 `appendResults` / `markProgress` / `markComplete`；`load()` 的计数取行文件真实条数 |
| `service/AnalysisService.kt` | 删除下标与 `clear()` 分支；每张立即落盘；VLM 连续失败 20 次本轮降级；`finally` / `onDestroy` 中断兜底；进度改为「已完整分析张数」 |
| `viewmodel/PhotoViewModel.kt` | 用同一 Planner 算 `doneCount/pendingCount/canResume`；新增 `resumeAnalysis()` 与 `autoResumeIfNeeded()`；批量重分析改轻量追加 |
| `MainActivity.kt` | `onResume` 触发自动续跑；新增版本说明 / 操作手册导航 |
| `ui/screens/HomeScreen.kt` | 状态条显示「已分析 / 待分析」；更多菜单加手册与版本说明入口 |
| `ui/screens/SettingsScreen.kt` | 自动续跑开关；分段大小改名为进度标记刷新间隔；版本号动态读取 |
| `data/AppConfig.kt` | 新增 `isAutoResumeEnabled` / `setAutoResumeEnabled`（默认开） |

### 用户可见变化

- 分析中：`已完成 X / Y，还剩 Z 张（可随时退出，下次自动继续）`
- 中断后回到应用：默认自动接着跑，不从头开始
- 新拍的照片：只分析新增的
- 云端 AI 断网 / 限流造成的半结果：下次自动补齐中文描述
- 设置里可关闭自动续跑；关闭后首页出现「继续」按钮

## 二、新增：版本说明

- `ui/screens/AboutScreen.kt`：动态读取 `PackageManager` 版本号，列出 v3.5.0 至 v3.9.0 全部版本变更（可折叠），含升级/降级注意事项。
- 入口：右上角更多菜单 →「版本说明」；设置页底部「版本说明」。
- 仓库文档：`docs/CHANGELOG.md`（总览）、`docs/CHANGELOG_v3.9.0.md`（本文件）。
- 顺带修掉设置页硬编码的「智能相册搜索 v3.1」。

## 三、新增：操作手册

- `ui/screens/HelpScreen.kt`：9 个章节，可折叠、支持关键词过滤，内容与 `docs/USER_MANUAL.md` 一致。
  1. 快速开始（授权、分析、AI 配置）
  2. 照片分析与断点续跑
  3. 搜索照片内容（含排除条件、中英文）
  4. 按时间检索（6 种日期写法、区间、相对时间）
  5. 按地点检索与时间地点联动
  6. 网格 / 列表视图
  7. 照片详情与 EXIF 信息
  8. 常见问题与降级处理
  9. 数据与隐私说明
- 入口：更多菜单 →「操作手册」；设置页底部「操作手册」；首页未分析状态条「怎么用」。
- 仓库文档：`docs/USER_MANUAL.md`。

## 四、验证

| 评测 | 结果 |
| --- | --- |
| `tools/ResumeEval.kt`（新增，35 项） | **35/35 PASS**，3 万张计划耗时 11ms |
| `tools/PerfEval.kt` | 26/26 PASS |
| `tools/V37Eval.kt` | 41/41 PASS |
| `tools/ExifSearchEval.kt` | 25/25 PASS，P=R=F1=1.000 |
| `tools/RecallEval.kt` | MACRO_F1=0.933 保持 |
| 数据/检索层 Kotlin 1.9.22 编译 | 通过 |
| UI 与服务层 | 由 GitHub Actions 真实 Gradle 编译验证 |

## 五、升级与回滚

- 直接覆盖安装即可（同一 keystore），已分析结果、EXIF 缓存、手动标注地点全部保留。
- 从 v3.8.0 升级无需重新分析：按 ID 差集自动续跑。
- 从更早版本升级会自动把旧单文件结果迁移成行式文件。
- 回滚：`git revert <commit>` 或安装 v3.8.0 APK（签名相同，可覆盖安装）。

## 六、提醒

- 签名密钥 `PhotoSearch-release.jks` 不可提交公开仓库，后续版本必须继续用它签名。
- 本轮使用的 GitHub PAT 建议尽快撤销轮换。
