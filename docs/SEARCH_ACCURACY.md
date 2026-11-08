# PhotoSearch 搜索准确度诊断与优化方案

> 仓库：`haoliying/PhotoSearch`（main，v3.4，Kotlin / Jetpack Compose）
> 诊断时间：2026-11-08
> 结论基于对全部 20 个 Kotlin 源文件及 Gradle/XML 配置的通读，以及一次按源码 1:1 复刻的离线仿真评测。

---

## 一、结论速览

**问题不在"AI 不够强"，而在检索层把任何照片都判成命中。** v3.0 已经接入云端 VLM 生成中文描述，但检索仍建立在"自由文本子串 + 双向包含 + 字符集合模糊"之上，这些规则宽松到几乎不产生排除力，VLM 带来的信息优势被完全淹没。

按源码逻辑复刻的仿真结果（24 张带人工标注照片 / 5 个典型查询）：

| 方案 | 宏平均精确率 | 宏平均召回率 | 典型表现 |
| --- | --- | --- | --- |
| A 现行方案 | **0.10** | 1.00 | 搜「有汽车的照片」返回 24/24 张，精确率 0.17 |
| B 仅重写检索层 | **0.90** | 1.00 | 仅剩 1 处 VLM 幻觉误召回 |
| C 检索层 + 结构化分析 | **1.00** | 1.00 | 全部命中正确 |

也就是说：**当前"搜什么都是整个相册"是确定性 bug，不是模型能力问题。** 只改 4 个文件（3 个替换 + 1 个新增）即可把精确率从 0.10 拉到 0.90；再改分析层的提示词与输出格式可到 1.00（仿真值）。**检索层补丁已于 2026-11-08 提交到 `main`，拉取最新代码即可生效。**

### 立刻能做的三件事

1. 进「设置」确认 **VLM 已开启且 API Key 有效**（智谱 `glm-4v-flash` 免费），并**不要用 DeepSeek**——`deepseek-chat` 是纯文本模型，不支持图片输入，选它必然全部失败。
2. 点一次 **「重新分析」**：如果首次分析时没配 Key 或 VLM 报错，程序会把"空描述"当作已分析并持久化，之后断点续传不会补跑。旧缓存不清除，配好 Key 也没用。
3. 换用检索层补丁（`ConceptRegistry.kt` / `SearchIntent.kt` / `IntentParser.kt` / `SearchEngine.kt`），这是收益最大的一步——**本次已把这 4 个文件提交到 `main`**。

> 安全提醒：你在对话里直接贴出了 GitHub Personal Access Token。本次已用该 token 提交修复到仓库，**请尽快到 GitHub Settings → Developer settings 撤销并轮换**，避免凭据长期暴露。

---

## 二、根因清单

### P0：直接导致"搜什么都是整个相册"（必修）

#### 1. `fuzzyMatch` 把 2 字符子串匹配用在了英文上 —— 最致命

`SearchEngine.kt:267-285`

```kotlin
if (a.length >= 2 && b.length >= 2) {
    for (i in 0..a.length - 2) {
        val sub = a.substring(i, i + 2)
        if (b.contains(sub)) return true   // 任意 2 字符子串命中即判定「匹配」
    }
}
```

这段本意是给中文做"2 字子串"容错，但**没有区分中英文**，于是任意两个英文词只要共享 2 个连续字母就算匹配。实测命中路径：

| 查询关键词 | 命中的无关标签 | 原因 |
| --- | --- | --- |
| `car` | `landscape` | `ca` 在 `landscape` 中 |
| `vehicle` | `cloud` | `cl` 在 `cloud` 中 |
| `automobile` | `outdoor` | `ou` 在 `outdoor` 中 |
| `van` | `landscape` | `an` 在 `landscape` 中 |
| `motorcycle` / `bicycle` | `cloud` | `cl` 在 `cloud` 中 |

叠加第 279-284 行的英文字符集合重叠判定（`overlap / maxLen > 0.6`，`car` 与 `cat`、`card` 之类互相命中），结果就是**一张"纯蓝天白云"的照片也能通过"汽车"查询**。

#### 2. 双向 `contains` + 单字关键词 + 整段描述进 tags

- `IntentParser.kt:15` 车辆条件里混入单字 **`车`**，以及 `wheel`、`tire`、`bicycle`、`motorcycle` 等语义不同的词。
- `SearchEngine.kt:82` `kw.contains(v)`、`:89` `tag.contains(kw) || kw.contains(tag)`：只要标签是短词，就几乎必然被某个关键词"包含"。
- `PhotoMetadata.kt:85` 把**整段中文描述**塞进 `tags`，于是描述里出现「停车场 / 车库 / 火车站 / 自行车 / 汽车模型 / 没有汽车」中的任意一个「车」字，都会命中「汽车」。
- `SearchEngine.kt:109-131` `matchAgainstText` 又对描述做 2 字子串命中，进一步放大。

后果：搜「汽车」时，自行车、车库、火车站、汽车模型、明确写着"没有汽车"的照片全部被召回。

#### 3. 否定条件反向加分 + RANK 阈值 0.05

`SearchEngine.kt:213-217` 对 `negated` 条件用 `1 - conditionScore` 计分，`SearchEngine.kt:56` 的 RANK 阈值只有 `0.05f`：

```kotlin
val finalScore = if (condition.negated) (1.0 - conditionScore).toFloat() else ...
if (score > 0.05f) { /* 收进结果 */ }
```

「光膀子」模式有 4 个条件，其中"排除衬衫/外套"权重 1.5。一张完全无关的照片，仅靠"不含衬衫"就能拿到 `(1-0)×1.5 / 3.8 ≈ 0.39` 分，远超阈值。**仿真中「光膀子」返回 24/24 张，精确率 0.04。** 排除项应该用于否决，绝不能用来加分。

#### 4. `or` / `and` 被当作裸子串切分，英文查询被切碎

`IntentParser.kt:237-238, 325-333`

```kotlin
val andParts = splitByOperators(query, listOf("和", "与", "and", "&", "，", ","))
val orParts  = splitByOperators(query, listOf("或", "or", "|"))
part.split(Regex("\\s*${Regex.escape(op)}\\s*"))   // 无词边界
```

`portrait` → `["p", "trait"]`，`forest` → `["f", "est"]`，查询直接进入 OR 分支并退化成两个单字母条件，几乎全库命中。中文「温和的阳光」也会被「和」切开。另外 `getDescription()` 用 `firstOrNull()`、`parse()` 用 `maxByOrNull()`，界面上显示的意图描述可能与实际使用的规则不是同一条。

#### 5. `toSearchableTags` 用规则凭空捏造标签

`PhotoMetadata.kt:141-156`

```kotlin
if (hasPersonLabel && !hasShirtLabel && (hasBareSkinLabel ||
        sceneType == BEACH_SEA || sceneType == SPORT_EXERCISE)) {
    tags.addAll(listOf("shirtless", "光膀子", "赤裸上身", "没穿上衣"))
}
```

**"海滩上有人 + 没检测到衬衫标签"就被判定为"光膀子"**。ML Kit 本来就不稳定输出 shirt 标签，这条规则会持续制造假阳性。索引里存在凭空生成的标签，检索再准也没用。

---

### P1：分析数据质量（决定召回上限）

#### 6. EXIF 旋转完全没处理 —— 影响面最大、最容易被忽略

`EnhancedImageAnalyzer.kt:123-148` 与 `VisionLanguageAnalyzer.kt:282-304` 都直接 `BitmapFactory.decodeStream`，**没有读取 `ExifInterface.TAG_ORIENTATION`**；`InputImage.fromBitmap(bitmap, 0)` 的旋转角也写死 0。

手机竖拍照片普遍带 `ORIENTATION_ROTATE_90`，解码出的 bitmap 是"躺着"的。这意味着：

- ML Kit 的物体检测、人脸检测、图像标注都在横躺的图上跑，置信度显著下降；
- 发给 VLM 的也是横躺的图，模型容易给出错误描述（把竖立的人看成躺着的、把建筑方向看错）。

这一条对"汽车"这类方向敏感物体的识别率影响尤其大，且**改起来只有十几行代码**，性价比最高。修复代码见第四节。

#### 7. VLM 提示词在诱导幻觉

`VisionLanguageAnalyzer.kt:320-332`

> "如果能识别出具体地点请说明，比如泰山、长城、某个海滩等"
> "如果照片中有人光着上身/没穿上衣，请在描述和关键词中明确说明"

模型看到山就写「疑似泰山」，看到人物就倾向报告"光着上身"。仿真中「泰山」查询因此把所有山景召回。提示词应该反过来：**明确禁止猜测地名、禁止推断属性、不确定就不写**。

#### 8. VLM 输出解析过于脆弱

`VisionLanguageAnalyzer.kt:202-240` 用正则硬匹配「描述：」「关键词：」，且 `kwMatch` 只取第一行。模型加个 markdown 粗体、输出 JSON、或关键词换行，解析就失败，退化到 `extractKeywordsFromDescription()`（`:242-280`）用固定词表做子串匹配——这个兜底反而把整段文本（含"关键词：…"字样）当描述，造成自我命中。

#### 9. VLM 失败静默、且不可恢复

`AnalysisService.kt:146-173`：VLM 报错时 `description = ""`、`vlmKeywords = emptyList()`，但**仍然写入 `analyzedMetadata` 并持久化**，且 `isAnalyzed = true`。恢复逻辑（`:108-123`）只按 `analyzedCount` 跳过。

结果是：首次分析时网络抖动 / Key 无效 / 触发限流的那批照片，**之后配好 Key 也不会被补跑**，只能靠单张或批量"重新分析"手动救。用户很难意识到这一点，只会觉得"有些照片死活搜不到"。此外 `:158-162` 的"错误 >10 退避"只 `delay(5000)`，没有重试、没有熔断，继续逐张失败。

#### 10. 其余数据层问题

| 位置 | 问题 | 建议 |
| --- | --- | --- |
| `EnhancedImageAnalyzer.kt:44` | 标签置信度阈值 `0.2f`，大量噪声标签进索引 | 提到 `0.5f~0.6f`，检索侧再按置信度加权 |
| `EnhancedImageAnalyzer.kt:49-53, 180-203` | `ObjectDetector.enableClassification()` 只能输出 Place / Plant / Food / Fashion good / Home good 等 5~6 个粗类，**永远不会输出 "car"**；`classifyScene` 里 `objects.any { label in listOf("car",...) }`（`:285`）恒为 false | 去掉 classification，或换自定义 TFLite 检测模型拿真实类别 + bbox |
| `EnhancedImageAnalyzer.kt:272-330` | `allTexts.any { it in listOf("car","vehicle",...) }` 是**精确相等**，ML Kit 常返回 "Sports car" / "Motor vehicle" 复合词，一律不匹配 → `sceneType = UNKNOWN` → 中文标签也加不上 | 改为规范化包含匹配 |
| `PhotoMetadata.kt:291-293` | `LabelTranslator` 仅当英文标签**完全等于** key 才翻译，复合标签无法映射中文 | 用词表 + 词边界匹配 |
| `VisionLanguageAnalyzer.kt:31-32, 314-318` | 图片压到 1024px / JPEG 80，远景小物体（远处的车）容易漏 | 长边提到 1280~1536；必要时对检测框裁剪二次提问 |
| `EnhancedImageAnalyzer.kt:101, 112-113` | `bitmap.recycle()` 之后才读 `width/height` | 先存宽高再 recycle |
| `EnhancedImageAnalyzer.kt:118` | 异常时返回 `PhotoMetadata(isAnalyzed = true)` 空元数据，该照片永久不可搜索 | 失败应标记 `isAnalyzed = false` 以便重试 |
| `AppConfig.kt` / `SettingsScreen.kt` | VLM 默认开启但需用户填 Key；未填时 `useVlm = false`，全靠 ML Kit 英文标签，中文语义能力极弱 | 首页显著提示"未配置 AI 描述，准确度受限" |

---

### P2：架构与工程

- **没有真正的语义检索**：纯词面匹配无法处理「SUV 算不算汽车」「红色轿车」「画面里没有车」这类语义。这是当前方案的能力天花板。
- **没有评估体系**：仓库里没有标注集、没有 precision/recall 指标，无法判断一次改动是变好还是变坏。
- **死代码**：`SearchConditionMapper.kt`（10 KB 旧版规则）和 `ImageAnalyzer.kt`（3 KB 旧版分析器）已不被调用（`PhotoViewModel.kt:11` 只 import 未使用），容易误改。
- **缓存没有版本**：换提示词 / 换模型后旧 `analysis_results.json` 不会失效，同一相册里索引质量参差不齐。
- **`AnalysisResultStore.kt:36-68`** 每个 segment 都把全量 metadata 序列化成一个 JSON，照片多时 I/O 与内存压力大。
- **`SearchEngine.kt:33`** 对 `metadataMap` 里没有的照片直接跳过，未分析照片永远不会出现在结果中，UI 也没有"还有 N 张未分析"的提示。

---

## 三、改进方案

### 阶段 1：重写检索层（收益最大，已提供可直接替换的代码）

核心思路：**把"自由文本互相模糊匹配"改成"规范概念 + 证据强度"的精确比对**。

```
用户输入 ──> IntentParser（规范化为概念 ID + 排除项）
                │
照片元数据 ──> ConceptRegistry（VLM关键词 1.0 > VLM描述 0.9 > 人脸 0.8
                              > ML Kit标签 0.45~0.8 > sceneType 0.40 弱信号）
                │
                └──> SearchEngine：概念 ID 精确比对 + 证据分档 + 阈值 0.5
```

关键改动：

| 改动 | 解决 |
| --- | --- |
| 删除 `fuzzyMatch` / `matchAgainstText` / 双向 `contains` / 2 字子串 | P0-1、P0-2 |
| 概念词表统一中英文同义词，英文用词边界 `(?<![a-z])car(?![a-z])` | 中英文语义鸿沟 |
| 负向词表先剔除「自行车 / 车库 / 火车站 / 停车场 / 汽车模型 / 轮胎」再允许单字「车」 | 「车库」误判成汽车 |
| 否定句与不确定表述抑制（「没有汽车」「看起来像泰山」） | 描述里的否定与幻觉 |
| 不再采信 `toSearchableTags()` 的启发式推断标签 | P0-5 |
| 排除条件只做否决，RANK 阈值 0.05 → 0.45 且要求每个正向条件都有最低分 | P0-3 |
| `or` / `and` 改 `\bor\b` / `\band\b` 词边界 | P0-4 |
| `getDescription()` 与 `parse()` 用同一套概念 | 展示与逻辑不一致 |

### 阶段 2：修复分析层（决定召回上限）

1. **EXIF 旋转**（必做，十几行）。
2. **VLM 输出改为受控结构化 JSON**，禁止猜测地名与属性；解析失败重试一次。
3. **VLM 失败不落"已完成"记录**：给 `PhotoMetadata` 加 `analysisVersion`，VLM 成功写 2、失败写 1；恢复分析时对 `version < 2 && useVlm` 的照片只补跑 VLM，不重跑本地分析。
4. **标签阈值 0.2 → 0.5**，`classifyScene` 改包含匹配，移除无用的对象检测 classification，或换自定义 TFLite 检测模型。
5. **去掉 DeepSeek provider**（非视觉模型），或换成真正支持图片的模型。
6. **分析结果加版本号**，提示词/模型变更时触发重分析。

### 阶段 3：治本 —— 引入语义检索（可选，按需求决定）

| 路线 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| **A. 两阶段重排**（推荐先试） | 关键词召回 Top-50 → 对每张图调 VLM 问「这张照片里有汽车吗？只回答 是/否」 | 改动小、中文语义强、能处理否定与上下位词 | 每次搜索有 API 调用与延迟，可缓存 |
| **B. 端侧向量检索** | ONNX Runtime 跑 MobileCLIP / Chinese-CLIP，离线给每张图算 embedding，查询算文本 embedding 后余弦相似度 | 完全离线、无限次查询、天然语义泛化 | 需集成模型（几十 MB）、中文需中文文本编码器、首次建索引较慢 |
| **C. 混合** | 概念精确匹配（高 precision）+ 向量召回（高 recall）+ 交叉打分 | 效果最好 | 工程复杂度最高 |

对"我输入有汽车的照片，就搜出所有包含汽车的照片"这个目标，**阶段 1 + 阶段 2 已经足够**；阶段 3 是应对"红色轿车""停在树下的车""不是汽车的照片"这类复杂查询时才需要。

---

## 四、可直接使用的关键代码

### 4.1 补丁文件映射（Kotlin 1.9.22 编译 + 仓库真实数据类复验 + JVM 运行时断言）

| 交付文件 | 放到仓库的位置 | 说明 |
| --- | --- | --- |
| `kotlin/ConceptRegistry.kt` | `app/src/main/java/com/example/photosearch/search/ConceptRegistry.kt` | **新增**：概念词表 + 规范化 + 证据强度提取 |
| `kotlin/SearchIntent.kt` | 同名替换 | `SearchCondition` 增加 `concept: Concept?` |
| `kotlin/IntentParser.kt` | 同名替换 | 删除硬编码 pattern，改概念解析 |
| `kotlin/SearchEngine.kt` | 同名替换 | 删除模糊匹配，改概念精确匹配 |
| `evaluate.py` | 仓库根目录 `tools/`（可选） | 离线仿真评测，回归用 |

`PhotoViewModel.kt` / 所有 UI / 数据层 **无需改动**：`SearchEngine.quickSearch()` 与 `IntentParser.getDescription()/getSuggestions()` 签名保持兼容。

验证结果（Kotlin 1.9.22 真实编译 + JVM 运行）：

```
PASS  「有汽车的照片」 -> [1, 2]  期望 [1, 2]
PASS  「汽车」         -> [1, 2]  期望 [1, 2]
PASS  「海滩」         -> [11, 16] 期望 [11, 16]
PASS  「光膀子」       -> [16]     期望 [16]
INFO  「portrait」     -> [7, 9, 11, 16, 18]  （不再被 or 拆成 "p"+"trait"）
ALL ASSERTIONS PASSED
```

### 4.2 EXIF 旋转修复

`app/build.gradle.kts` 增加依赖：

```kotlin
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

新增 `BitmapUtils.kt`，把 `EnhancedImageAnalyzer.loadBitmap()` 与 `VisionLanguageAnalyzer.loadAndResizeBitmap()` 都替换成它：

```kotlin
package com.example.photosearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

object BitmapUtils {
    fun decodeUpright(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }

        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright != bitmap) bitmap.recycle()
        return upright
    }
}
```

VLM 侧建议把 `MAX_IMAGE_SIZE` 从 1024 提到 1536（远景小汽车更不容易漏）。

### 4.3 结构化 VLM 提示词（替换 `ANALYSIS_PROMPT`）

```
你是照片索引器。只描述画面中确实可见的内容，不要做任何猜测。
严格输出 JSON，不要 markdown 代码块，不要任何解释文字：

{
  "caption": "一句中文客观描述，不超过 40 字",
  "objects": ["汽车", "行人", "高楼"],
  "scene": "街道",
  "attributes": ["白天", "晴天"],
  "place": null
}

规则：
1. objects 只列画面中真实可见的物体名称，用中文通用词；不确定的不要列。
2. 禁止根据画面猜测地名。看到山不要写「泰山」，看到海不要写「某个海滩」。
   只有画面中出现可读文字或明确地标时，才把地名填入 place，否则为 null。
3. 不要把自行车、火车、车库、停车场、汽车模型、轮胎、车轮写成「汽车」。
4. 不要写「没有XX」「不含XX」这类否定句；画面里没有的物体，objects 里不列即可。
5. 「光膀子 / 赤裸上身」这类属性只在确凿可见时放入 attributes，
   不要因为「在海边」「在运动」就推断。
6. caption 保持客观，不要用「疑似」「仿佛」「看起来像」等推测措辞。
```

同时把请求里的 `max_tokens` 降到 300 左右，并在支持的 provider 上加 `response_format: {"type":"json_object"}`（不支持的靠提示词约束 + 解析时剥离 ```json 围栏）。

对应解析：

```kotlin
private fun parseStructured(text: String): VlmResult {
    val cleaned = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    return try {
        val json = JSONObject(cleaned)
        val caption = json.optString("caption", "")
        val objects = json.optJSONArray("objects")
        val attrs = json.optJSONArray("attributes")
        val keywords = mutableListOf<String>()
        listOfNotNull(objects, attrs).forEach { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let(keywords::add)
            }
        }
        json.optString("place", "").takeIf { it.isNotBlank() && it != "null" }
            ?.let(keywords::add)
        // objects 直接作为高置信度关键词，检索侧的证据权重给 1.0
        VlmResult(caption, keywords.distinct(), true)
    } catch (e: Exception) {
        VlmResult("", emptyList(), false, "VLM 输出不是合法 JSON: ${e.message}")
    }
}
```

配合 `ConceptRegistry`，`objects` 里的「汽车」会被规范化成 `Concept.CAR`，检索不再依赖自由文本。这条同时解决了 P0-2、P1-7、P1-8 和"泰山幻觉"。

### 4.4 VLM 失败可恢复（改 `AnalysisService.runAnalysis`）

```kotlin
// PhotoMetadata 增加字段
val analysisVersion: Int = 1   // 1 = 仅本地分析，2 = 含 VLM 结构化描述

// AnalysisService 里
val vlmOk = vlmResult.success
val metadata = onDeviceMetadata.copy(
    photoId = photo.id,
    description = if (vlmOk) vlmResult.description else "",
    vlmKeywords = if (vlmOk) vlmResult.keywords else emptyList(),
    analysisVersion = if (vlmOk) 2 else 1
)
analyzedMetadata[photo.id] = metadata
if (!vlmOk) pendingVlmIds.add(photo.id)   // 持久化到 AnalysisResultStore
```

恢复分析时，对 `analysisVersion < 2 && useVlm` 的照片**只重跑 VLM**；并在设置页提供「补跑 AI 描述（N 张）」按钮，把 `pendingVlmIds` 显示出来。序列化时记得在 `AnalysisResultStore.serializeMetadata/deserializeMetadata` 里加上该字段并把 `CURRENT_VERSION` 从 2 提到 3。

### 4.5 其它一行级修正

```kotlin
// EnhancedImageAnalyzer.kt:44
.setConfidenceThreshold(0.5f)          // 原 0.2f，噪声标签太多

// EnhancedImageAnalyzer.kt:118  异常时不要标记已完成
PhotoMetadata(photoId = 0, isAnalyzed = false)

// EnhancedImageAnalyzer.kt:101  先取宽高再 recycle
val w = bitmap.width; val h = bitmap.height
if (!bitmap.isRecycled) bitmap.recycle()
PhotoMetadata(..., width = w, height = h, ...)

// classifyScene: 把精确相等改成规范化包含
fun matchesAny(texts: Set<String>, words: Set<String>) =
    texts.any { t -> words.any { w -> t == w || t.contains(" $w") || t.endsWith(" $w") } }

// VisionLanguageAnalyzer.PROVIDERS 删除 deepseek（非视觉模型）
```

---

## 五、落地顺序与验收

| 优先级 | 动作 | 预期收益 | 工作量 |
| --- | --- | --- | --- |
| P0 | 换入 4 个 `kotlin/` 补丁文件 | 精确率 0.10 → 0.90 | 复制 4 个文件，编译一次 |
| P0 | 设置里确认 VLM Key，然后「重新分析」清旧缓存 | 让 VLM 描述真正生效 | 用户操作 |
| P1 | EXIF 旋转 + 图片尺寸 1536 | 竖拍照片识别率明显提升 | ~20 行 |
| P1 | 结构化 JSON 提示词 + 解析 | 精确率 → 1.00，消除地名/属性幻觉 | ~80 行 |
| P1 | VLM 失败可恢复 + 分析版本 | 消除"部分照片永远搜不到" | ~40 行 |
| P1 | 标签阈值 0.5、classifyScene 包含匹配、去掉 DeepSeek | 降噪、补召回 | ~30 行 |
| P2 | 建立标注集与回归评测 | 保证后续改动不劣化 | 50~100 张照片 + 20 条查询 |
| P3 | 两阶段 VLM 重排 / 端侧 CLIP | 支持复杂语义查询 | 按需求评估 |

**验收方式**：把 `evaluate.py` 的思路搬到真机——选 50~100 张照片人工标注「汽车 / 海滩 / 人物 / 光膀子 / 夜景…」，写 20 条查询及期望结果，每次改动后跑一遍 precision / recall / F1，并保留一张"混淆矩阵"看是哪类查询在拖后腿。没有这个基线，"准确度提高了"就只是感觉。

---

## 附：本次分析的范围说明

- 仓库 `git clone` 因沙箱网络只放行 API 域名而失败，改通过 GitHub Contents API 拉取了全部 20 个 Kotlin 源文件及 Gradle/XML 配置，源码完整，不影响结论。
- 仿真评测按 `IntentParser → SearchEngine → toSearchableTags` 逻辑 1:1 复刻，用于**说明问题机理与改进趋势**；指标是仿真值，不是真机实测值。
- 补丁代码用 Kotlin 1.9.22 配合数据类桩完成**真实编译与 JVM 运行断言**，但不包含 Android SDK 依赖（EXIF 等代码片段需在你的工程中编译）。
- **落地状态（2026-11-08）**：检索层 4 个补丁文件、本文档（`docs/SEARCH_ACCURACY.md`）与 `tools/evaluate.py` 已提交到 `main`。分析层改进（EXIF 旋转、结构化 JSON 提示词、VLM 失败可恢复等）因缺少 Android SDK 编译环境、需真机验证，未纳入本次提交；完整代码见第四节，可按第五节顺序逐步落地。
