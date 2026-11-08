# PhotoSearch v3.6.0：EXIF 时间地点检索与时空联动

## 需求

把照片的时间、地点等 EXIF 信息变成可检索条件；支持设定时间区间、选定地点查询；
地点支持模糊查询；时间与地点要联动。

## 结论

新增一条**独立于 AI 分析**的元数据链路：读取 EXIF 拍摄时间与 GPS → 解析成地名 →
写入独立缓存 → 在检索引擎里作为硬过滤条件，与内容条件按 AND 组合。

关键取舍：不把 EXIF 塞进 `analysis_results.json`。拍摄时间 / GPS 是确定性数据，
不需要 VLM 或 ML Kit，因此**升级后无需重新分析照片**就能用，也不会因为改动
序列化格式让用户已有的分析缓存失效。

## 数据来源与优先级

| 维度 | 优先级 | 实现 |
| --- | --- | --- |
| 拍摄时间 | EXIF `DateTimeOriginal` > `DateTime` > `DateTimeDigitized` > 文件名时间戳 > MediaStore `DATE_TAKEN` > `DATE_ADDED` | `ExifReader` |
| GPS | EXIF GPS（`MediaStore.setRequireOriginal`） > MediaStore `LATITUDE/LONGITUDE` | `ExifReader` / `PhotoRepository` |
| 地名 | Geocoder 逆地理编码 > 内置城市经纬度表 > 经纬度字符串 > 用户手动标注 | `ExifReader` / `PlaceCatalog` |

- **Android 10+ 分区存储**会涂改 MediaStore 的经纬度，必须用
  `MediaStore.setRequireOriginal(uri)` 取原始文件流；这需要 `ACCESS_MEDIA_LOCATION`
  运行时权限。没有权限时自动降级为普通流，拍摄时间仍可读，只是 GPS 为空。
- **Geocoder 在国内设备常不可用**（依赖 Google 服务），所以内置了约 80 个中国主要
  城市 + 常见出境目的地的经纬度与中英文别名，用最近城市（Haversine 距离）兜底。
  Geocoder 同步调用有阻塞风险，因此按 ~1km 网格缓存、最多调用 60 次，失败即回退。
- **大量照片没有 GPS**（截图、微信保存图、相机未开定位）。详情页提供「手动标注
  地点」，标注后同样参与地点检索；手动标注优先于 GPS 解析结果。
- 时间单位归一：部分厂商把 `DATE_TAKEN` 写成秒，`< 1e11` 时按秒乘 1000。
  EXIF 时间不含时区，按设备本地时区解释。
- EXIF 缓存为 `photo_exif.json`，首次扫描在后台分批提取（每 25 张落盘一次），
  界面显示进度；已缓存的照片不会重复打开。

## 时间区间

`TimeQueryParser` 从自然语言解析出 `TimeRange`（毫秒，左闭右开），并把时间短语
从查询里剥离，剩余文本继续做内容检索。

| 类型 | 示例 |
| --- | --- |
| 绝对年份 / 年月 / 年月日 | `2023年`、`2023年6月`、`2023-06-15`、`2023/6/15` |
| 显式区间 | `2023-01-01 到 2023-06-30`、`2023.6~2023.9` |
| 相对时间 | `最近3天`、`最近三个月`、`近半年`、`最近一年` |
| 年份词 | `今年`、`去年`、`前年` |
| 季节 | `今年夏天`、`去年冬季`（冬季跨年） |
| 中文月份 | `六月`、`11月` |
| 公历节假日 | `国庆`、`五一`、`元旦` |
| 筛选面板 | 今年 / 去年 / 最近3个月 / 最近1年 / 最近30天 / 自定义 `yyyy-MM-dd` 起止 |

**农历节日（春节、中秋、端午）不做**，避免给出错误区间。

## 地点模糊查询

`LocationMatcher` 三层匹配，精确率从高到低：

1. **半径匹配**：从候选地点里「选定」时带中心点，GPS 落在半径内即命中；
2. **子串 / 包含**：`北京` 命中 `中国 北京市 朝阳区`；`深圳市南山区` 也能命中
   其中的 `深圳市`；支持 `北京市`、`沪`、`bj`、`beijing` 等别名与后缀归一；
3. **编辑距离容错**：只对 ≥3 字、首字相同、距离 ≤1 生效。
   两字地名不启用容错，否则「北京」会误命中「南京」。

没有任何地点信息的照片一律不参与地点筛选，也不会被当成「任意地点」返回。

## 时空联动

`SearchIntent` 新增 `timeRange` / `locationQuery`，`SearchEngine` 在打分前做硬过滤，
语义是 **时间 AND 地点 AND 内容词**：

```
2023年在北京拍的汽车   →  2023 年 AND 北京 AND 含汽车
北京 汽车              →  北京 AND 含汽车
2023年 北京            →  2023 年 AND 北京（纯筛选，返回全部满足者）
```

- 查询里解析出的条件与筛选面板的设定叠加：面板设定优先，查询解析作为补充。
- **只有时间/地点、没有内容词时**（`filtersOnly`），所有通过硬过滤的照片都返回，
  不会因为「没有内容条件」而给出空列表。
- **候选地点随时间区间联动**：筛选面板里的「已记录的地点」由当前时间区间内的
  实际照片统计得出，改时间后自动重算，可直接点选。
- EXIF 地名与拍摄年/月也编入全文索引，因此即便自然语言没被解析成结构化条件，
  输入 `北京`、`2023` 也能通过全文通道命中。
- 精确率保护：地点提取对整串采用严格别名匹配，「北京烤鸭」不会被当成地点、
  内容词也不会被吃掉；分段扫描只认独立地名片段。

## 改动文件

**新增**

| 文件 | 作用 |
| --- | --- |
| `data/PhotoExif.kt` | 时空元数据数据类 |
| `data/ExifReader.kt` | EXIF 时间/GPS 读取、Geocoder 与城市表地名解析 |
| `data/PhotoExifStore.kt` | 独立 JSON 缓存（分批落盘、断点续传） |
| `search/PlaceCatalog.kt` | 内置城市经纬度表、别名归一、Haversine 距离 |
| `search/TimeQueryParser.kt` | 中文时间区间解析与区间构造（纯 JVM，可离线评测） |
| `search/LocationMatcher.kt` | 地点模糊匹配、半径匹配、自然语言地点提取 |
| `docs/EXIF_SEARCH.md` | 本文档 |
| `tools/ExifSearchEval.kt` | 可复现的离线评测 |

**修改**

| 文件 | 作用 |
| --- | --- |
| `AndroidManifest.xml` | 增加 `ACCESS_MEDIA_LOCATION` |
| `app/build.gradle.kts` | 增加 `androidx.exifinterface:exifinterface:1.3.7`；`versionCode 9 / 3.6.0` |
| `data/PhotoItem.kt` | 增加经纬度兜底字段 |
| `data/PhotoRepository.kt` | 补齐 `DATE_ADDED/DATE_MODIFIED` 时间兜底与旧系统经纬度 |
| `search/SearchIntent.kt` | 新增 `TimeRange` / `LocationQuery`，`SearchIntent` 增加两字段 |
| `search/IntentParser.kt` | 先剥离时间/地点短语，剩余文本做内容检索 |
| `search/SearchEngine.kt` | 硬过滤 + `filtersOnly` + 3 参 `quickSearch` 兼容重载 |
| `search/ConceptRegistry.kt` | 索引纳入 EXIF 地名/年份；屏蔽「单车」误判 CAR；修正否定正则抹掉「无锡」 |
| `viewmodel/PhotoViewModel.kt` | 筛选状态、EXIF 异步提取、地点候选联动、统一筛选入口 |
| `MainActivity.kt` | 申请媒体位置权限，传递筛选参数 |
| `ui/screens/HomeScreen.kt` | 时间/地点筛选面板、进度与提示、空状态 |
| `ui/screens/PhotoDetailScreen.kt` | 展示 EXIF 时间/GPS/地点，手动标注地点 |

## 验证

- Kotlin 1.9.22 真实编译：纯逻辑层 + 仓库真实数据类 + Android API 桩全部通过。
- `tools/ExifSearchEval.kt`：12 张标注照片 / 25 条查询，
  **25/25 PASS，宏平均 P=1.000 R=1.000 F1=1.000**。覆盖时间区间、显式区间、
  相对时间、地点精确/别名/模糊、北京不误命中南京、手动标注地点、
  三段联动、纯筛选、联动过严返回 0 条。
- `tools/RecallEval.kt`（v3.5.2 回归基线）：宏平均 P/R/F1 保持 **0.933**，
  `landscape` 无相关照片时仍返回 0 条，未复发「全库返回」bug。
- 评测暴露并修复了一个遗留精确率问题：CAR 词表含单字「车」，而
  `CAR_CONFUSABLES` 未屏蔽「单车」，导致自行车照片被「汽车」召回。

## 使用与限制

1. 升级到 v3.6.0 后首次打开会后台提取 EXIF，筛选面板显示进度；**不需要重新分析**。
2. 弹权限框时请允许「照片和视频」与「访问媒体位置」。拒绝位置权限不影响时间筛选，
   但照片不会有 GPS 地点。
3. 顶栏漏斗图标打开筛选面板：选时间快捷区间或自定义起止日期，地点可直接输入
   （模糊）或点选候选地点；与搜索框内容同时生效。
4. 没有 GPS 的照片：在照片详情页点「+ 手动标注」填地点即可参与地点检索。
5. 限制：农历节日不支持；Geocoder 不可用时地点精度为城市级（约 60km 半径）；
   无 EXIF、文件名无时间戳、MediaStore 也无日期的照片会标记「缺少拍摄时间」，
   不纳入时间筛选，界面会提示张数。

回滚：`git revert <commit>` 或安装 v3.5.2（签名相同，可覆盖安装）。
