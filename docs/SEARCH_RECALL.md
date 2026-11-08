# PhotoSearch 搜索成功率（召回率）诊断与修复 · v3.5.2

> v3.5.0 修的是**精确率**（当时搜「汽车」返回整个相册，宏平均 P=0.10 → 0.90）。
> v3.5.2 修的是**召回率**：严格化之后，很多本该命中的照片搜不出来。

## 根因清单

| # | 问题 | 位置 | 后果 |
| --- | --- | --- | --- |
| 1 | 易混淆组合词被**全局**剔除 | `ConceptRegistry.normalize` | 「自行车 / 摩托车」无法映射到 BICYCLE，只能靠 VLM 原文兜底；VLM 失败或输出英文标签时搜不到 |
| 2 | VLM 输出的 10~20 个关键词，只有能落进 26 个概念的那几个被使用 | `fromMetadata()` | 「咖啡」「笔记本电脑」「气球」「红烧肉」等词表外内容全部丢弃 |
| 3 | 本地分析信号大量闲置 | `fromMetadata()` | `detectedObjects`、`LabelTranslator` 中文翻译、`faces`、`hasText`、`isOutdoor` 均未进检索 |
| 4 | 中文查询不切词 | `IntentParser.tokenize()` | 「一杯咖啡」被当成一个关键词做整串 `contains`，必然 0 结果；「山和海」中词表外的部分被静默丢弃 |
| 5 | 概念条件不带查询词元 | `IntentParser.parse()` | 概念通道未命中时没有兜底，而 `strictMatch` 的 keywords 为空，等于该条件必然失败 |
| 6 | 没有分析结果的照片直接 `return null` | `SearchEngine.search` | 分析失败/尚未分析的照片永久搜不到 |
| 7 | ML Kit 标签置信度门槛 0.45 | `fromMetadata()` | 0.20~0.45 的有效标签被丢弃 |

分析层：VLM 失败仍写入「分析完成」（通知栏仅一行小字）、提示词诱导模型猜地名、
`deepseek-chat` 是纯文本模型必然全失败、EXIF 旋转未处理（竖拍照片躺着送进模型）。

## 修复方案：双通道检索

**概念通道**（精确率）保持不变：中英文同义词归一、否定/不确定表述抑制。
修复了「只在判定 CAR 时屏蔽易混淆词」，不再全局抹掉「自行车」等真实词。

**全文通道**（召回率）新增：把 VLM 描述/关键词、ML Kit 标签（英 + 中）、
对象检测、场景、人脸、文字信号全部编入 `SearchIndex`，用「完整词」匹配覆盖
任意词表外查询词。

防噪声约束（与旧版 `fuzzyMatch` 的本质区别，避免退回「全库返回」）：

- 不做 2 字符子串模糊、不做双向 contains、不做字符集合重叠
- 中文词元长度 ≥ 2、英文词元长度 ≥ 3 且用词边界
- 只做「查询词出现在照片文本」单一方向
- 查询侧去停用词 + 2~4 gram；索引侧剔除描述套话、否定句、不确定表述、噪声组合词
- 长词优先、命中即停，抑制短 n-gram 乱命中
- 弱信号（场景 0.50 / hasText 0.50）低于 `TEXT_MIN_EVIDENCE=0.55`，只能排序不能单独命中

## 量化评测

`tools/RecallEval.kt`：17 张标注照片（含 3 张模拟 VLM 失败、1 张无分析结果）、
14 条查询，用**仓库真实数据类** + Kotlin 1.9.22 编译后跑 JVM 断言。

| 指标 | v3.5.1（现行） | v3.5.2 | 变化 |
| --- | --- | --- | --- |
| 宏平均精确率 P | 0.800 | **0.933** | +0.133 |
| 宏平均召回率 R | 0.717 | **0.933** | +0.216 |
| 宏平均 F1 | 0.749 | **0.887** | +0.138 |

典型差异：

| 查询 | v3.5.1 | v3.5.2 |
| --- | --- | --- |
| 一杯咖啡 | 0 结果 | 命中咖啡照片 |
| 笔记本电脑 | 0 结果 | 命中（VLM 关键词只有「笔记本」） |
| 汽车 | 3/4（漏掉无分析结果的照片） | 4/4 |
| 书本 | 1/2（漏掉 VLM 失败、只有 Book 标签的照片） | 2/2 |
| landscape（无相关照片） | 0 结果 | 0 结果（未复发 fuzzyMatch 全库 bug） |

复现方法：把 `tools/RecallEval.kt`、`app/.../data/PhotoMetadata.kt`、
`app/.../data/PhotoItem.kt`、一个 `android.net.Uri` 数据类桩、以及 4 个 `search/*.kt`
放到同一目录，用 `kotlinc` 编译后运行 `RecallEvalKt`。

## v3.5.2 改动文件

| 文件 | 改动 |
| --- | --- |
| `search/ConceptRegistry.kt` | 概念词表扩充；CAR 屏蔽改为局部；新增 `tokenizeQuery` / `buildSearchIndex` / `SearchIndex`；纳入全部本地信号 |
| `search/SearchEngine.kt` | 双通道；不再丢弃无 metadata 的照片；排除条件只走严格匹配 |
| `search/IntentParser.kt` | 中文切词；每个条件都带词元；词表外词不再丢失；更新快捷搜索示例 |
| `data/PhotoMetadata.kt` | `LabelTranslator` 补 20+ 高频物体中文映射 |
| `ml/VisionLanguageAnalyzer.kt` | 提示词改结构化 JSON（具体名词、禁止猜地名）；解析 JSON；EXIF 旋转；DeepSeek 明确拦截 |
| `ml/EnhancedImageAnalyzer.kt` | EXIF 旋转 |
| `service/AnalysisService.kt` | 分析失败写占位结果；完成通知明确提示「N 张缺少 AI 描述」 |
| `ui/screens/HomeScreen.kt` | 「分析完成」状态增加「重新分析全部」入口 |

## 用户侧操作

**检索层的改进对已有分析数据立即生效**（本地标签、对象、场景信号都在旧缓存里），
装上新版本即可，不必重新分析。

但要达到最好效果，建议：

1. 设置里选**智谱AI / 通义千问 / 硅基流动**，填有效 Key；**不要选 DeepSeek**（纯文本模型）。
2. 点首页新增的「**重新分析全部**」，让新的结构化提示词与 EXIF 旋转生效。
3. 留意通知栏：若显示「N 张缺少 AI 描述」，说明 VLM 配置有问题，修好后重新分析。

回滚：`git revert <commit>` 或安装 [v3.5.1](https://github.com/haoliying/PhotoSearch/releases/tag/v3.5.1)（签名相同，可覆盖）。
