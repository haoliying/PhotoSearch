package com.example.photosearch.search

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 内置城市经纬度表（v3.6.0）。
 *
 * 为什么不只依赖 Geocoder：国内 Android 设备的 Geocoder 依赖 Google 服务，
 * 经常直接返回空；且它是同步阻塞调用。这里提供一份离线的城市级兜底，
 * 保证「北京 / 深圳 / 杭州」这类查询在任何设备上都能命中；Geocoder 可用时
 * 再补充区/街道/POI 级别的精细地名。
 */
data class Place(
    val name: String,
    val fullName: String,
    val province: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val aliases: List<String> = emptyList(),
    val radiusKm: Double = 60.0
)

/** 地点建议项：由当前时间区间内的实际照片统计得到，时间变化时联动刷新。 */
data class PlaceSuggestion(
    val label: String,
    val count: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val query: String = label
)

object PlaceCatalog {

    private val P: List<Place> = listOf(
        Place("北京", "北京市", "北京市", "中国", 39.9042, 116.4074, listOf("北京市", "京", "北京", "beijing", "bj", "peking"), 70.0),
        Place("上海", "上海市", "上海市", "中国", 31.2304, 121.4737, listOf("上海市", "沪", "上海", "shanghai", "sh"), 70.0),
        Place("广州", "广州市", "广东省", "中国", 23.1291, 113.2644, listOf("广州市", "穗", "广州", "guangzhou", "gz"), 60.0),
        Place("深圳", "深圳市", "广东省", "中国", 22.5431, 114.0579, listOf("深圳市", "鹏城", "深圳", "shenzhen", "sz"), 50.0),
        Place("杭州", "杭州市", "浙江省", "中国", 30.2741, 120.1551, listOf("杭州市", "杭州", "hangzhou", "hz"), 55.0),
        Place("成都", "成都市", "四川省", "中国", 30.5728, 104.0668, listOf("成都市", "蓉", "成都", "chengdu", "cd"), 65.0),
        Place("重庆", "重庆市", "重庆市", "中国", 29.5630, 106.5516, listOf("重庆市", "渝", "重庆", "chongqing", "cq"), 80.0),
        Place("武汉", "武汉市", "湖北省", "中国", 30.5928, 114.3055, listOf("武汉市", "汉", "武汉", "wuhan", "wh"), 60.0),
        Place("西安", "西安市", "陕西省", "中国", 34.3416, 108.9398, listOf("西安市", "镐", "西安", "xian", "xa"), 60.0),
        Place("南京", "南京市", "江苏省", "中国", 32.0603, 118.7969, listOf("南京市", "宁", "南京", "nanjing", "nj"), 55.0),
        Place("天津", "天津市", "天津市", "中国", 39.3434, 117.3616, listOf("天津市", "津", "天津", "tianjin", "tj"), 60.0),
        Place("苏州", "苏州市", "江苏省", "中国", 31.2989, 120.5853, listOf("苏州市", "苏州", "suzhou"), 50.0),
        Place("长沙", "长沙市", "湖南省", "中国", 28.2282, 112.9388, listOf("长沙市", "长沙", "changsha"), 55.0),
        Place("郑州", "郑州市", "河南省", "中国", 34.7466, 113.6254, listOf("郑州市", "郑州", "zhengzhou"), 60.0),
        Place("青岛", "青岛市", "山东省", "中国", 36.0671, 120.3826, listOf("青岛市", "青岛", "qingdao", "tsingtao"), 60.0),
        Place("济南", "济南市", "山东省", "中国", 36.6512, 117.1201, listOf("济南市", "济南", "jinan"), 55.0),
        Place("大连", "大连市", "辽宁省", "中国", 38.9140, 121.6147, listOf("大连市", "大连", "dalian"), 60.0),
        Place("沈阳", "沈阳市", "辽宁省", "中国", 41.8057, 123.4315, listOf("沈阳市", "沈阳", "shenyang"), 65.0),
        Place("厦门", "厦门市", "福建省", "中国", 24.4798, 118.0894, listOf("厦门市", "鹭岛", "厦门", "xiamen", "amoy"), 45.0),
        Place("福州", "福州市", "福建省", "中国", 26.0745, 119.2965, listOf("福州市", "福州", "fuzhou"), 55.0),
        Place("昆明", "昆明市", "云南省", "中国", 25.0389, 102.7183, listOf("昆明市", "春城", "昆明", "kunming"), 60.0),
        Place("贵阳", "贵阳市", "贵州省", "中国", 26.6470, 106.6302, listOf("贵阳市", "贵阳", "guiyang"), 55.0),
        Place("南宁", "南宁市", "广西壮族自治区", "中国", 22.8170, 108.3665, listOf("南宁市", "南宁", "nanning"), 55.0),
        Place("海口", "海口市", "海南省", "中国", 20.0440, 110.1999, listOf("海口市", "海口", "haikou"), 45.0),
        Place("三亚", "三亚市", "海南省", "中国", 18.2528, 109.5119, listOf("三亚市", "三亚", "sanya"), 45.0),
        Place("合肥", "合肥市", "安徽省", "中国", 31.8206, 117.2272, listOf("合肥市", "合肥", "hefei"), 55.0),
        Place("南昌", "南昌市", "江西省", "中国", 28.6820, 115.8579, listOf("南昌市", "南昌", "nanchang"), 55.0),
        Place("太原", "太原市", "山西省", "中国", 37.8706, 112.5489, listOf("太原市", "太原", "taiyuan"), 55.0),
        Place("石家庄", "石家庄市", "河北省", "中国", 38.0428, 114.5149, listOf("石家庄市", "石家庄", "shijiazhuang"), 60.0),
        Place("哈尔滨", "哈尔滨市", "黑龙江省", "中国", 45.8038, 126.5349, listOf("哈尔滨市", "哈尔滨", "harbin"), 70.0),
        Place("长春", "长春市", "吉林省", "中国", 43.8171, 125.3235, listOf("长春市", "长春", "changchun"), 60.0),
        Place("兰州", "兰州市", "甘肃省", "中国", 36.0611, 103.8343, listOf("兰州市", "兰州", "lanzhou"), 55.0),
        Place("西宁", "西宁市", "青海省", "中国", 36.6171, 101.7782, listOf("西宁市", "西宁", "xining"), 55.0),
        Place("银川", "银川市", "宁夏回族自治区", "中国", 38.4872, 106.2309, listOf("银川市", "银川", "yinchuan"), 55.0),
        Place("乌鲁木齐", "乌鲁木齐市", "新疆维吾尔自治区", "中国", 43.8256, 87.6168, listOf("乌鲁木齐市", "乌鲁木齐", "urumqi"), 80.0),
        Place("拉萨", "拉萨市", "西藏自治区", "中国", 29.6525, 91.1721, listOf("拉萨市", "拉萨", "lhasa"), 80.0),
        Place("呼和浩特", "呼和浩特市", "内蒙古自治区", "中国", 40.8414, 111.7519, listOf("呼和浩特市", "呼和浩特", "hohhot"), 70.0),
        Place("无锡", "无锡市", "江苏省", "中国", 31.4912, 120.3119, listOf("无锡市", "无锡", "wuxi"), 45.0),
        Place("宁波", "宁波市", "浙江省", "中国", 29.8683, 121.5440, listOf("宁波市", "宁波", "ningbo"), 50.0),
        Place("温州", "温州市", "浙江省", "中国", 27.9938, 120.6993, listOf("温州市", "温州", "wenzhou"), 50.0),
        Place("佛山", "佛山市", "广东省", "中国", 23.0218, 113.1219, listOf("佛山市", "佛山", "foshan"), 40.0),
        Place("东莞", "东莞市", "广东省", "中国", 23.0207, 113.7518, listOf("东莞市", "东莞", "dongguan"), 40.0),
        Place("珠海", "珠海市", "广东省", "中国", 22.2707, 113.5767, listOf("珠海市", "珠海", "zhuhai"), 40.0),
        Place("中山", "中山市", "广东省", "中国", 22.5170, 113.3820, listOf("中山市", "中山", "zhongshan"), 40.0),
        Place("惠州", "惠州市", "广东省", "中国", 23.1115, 114.4155, listOf("惠州市", "惠州", "huizhou"), 50.0),
        Place("泉州", "泉州市", "福建省", "中国", 24.8741, 118.6757, listOf("泉州市", "泉州", "quanzhou"), 50.0),
        Place("洛阳", "洛阳市", "河南省", "中国", 34.6197, 112.4539, listOf("洛阳市", "洛阳", "luoyang"), 55.0),
        Place("桂林", "桂林市", "广西壮族自治区", "中国", 25.2736, 110.2994, listOf("桂林市", "桂林", "guilin"), 60.0),
        Place("丽江", "丽江市", "云南省", "中国", 26.8721, 100.2320, listOf("丽江市", "丽江", "lijiang"), 60.0),
        Place("大理", "大理市", "云南省", "中国", 25.6065, 100.2676, listOf("大理市", "大理", "dali"), 60.0),
        Place("张家界", "张家界市", "湖南省", "中国", 29.1171, 110.4794, listOf("张家界市", "张家界", "zhangjiajie"), 60.0),
        Place("黄山", "黄山市", "安徽省", "中国", 29.7147, 118.3376, listOf("黄山市", "黄山", "huangshan"), 60.0),
        Place("苏州园区", "苏州工业园区", "江苏省", "中国", 31.3208, 120.7300, listOf("苏州工业园区", "园区"), 25.0),
        // 中国香港 / 中国澳门 / 中国台湾：按国家主权表述，绝不可作为独立国家
        Place("香港", "中国香港", "中国香港", "中国", 22.3193, 114.1694, listOf("中国香港", "香港", "hong kong", "hk"), 40.0),
        Place("澳门", "中国澳门", "中国澳门", "中国", 22.1987, 113.5439, listOf("中国澳门", "澳门", "macao", "macau"), 25.0),
        Place("台北", "中国台湾台北", "中国台湾", "中国", 25.0330, 121.5654, listOf("中国台湾台北", "台北", "taipei"), 40.0),
        Place("高雄", "中国台湾高雄", "中国台湾", "中国", 22.6273, 120.3014, listOf("中国台湾高雄", "高雄", "kaohsiung"), 40.0),
        // 常见出境目的地
        Place("东京", "东京", "日本", "日本", 35.6762, 139.6503, listOf("东京", "tokyo"), 60.0),
        Place("大阪", "大阪", "日本", "日本", 34.6937, 135.5023, listOf("大阪", "osaka"), 50.0),
        Place("京都", "京都", "日本", "日本", 35.0116, 135.7681, listOf("京都", "kyoto"), 40.0),
        Place("首尔", "首尔", "韩国", "韩国", 37.5665, 126.9780, listOf("首尔", "seoul", "汉城"), 50.0),
        Place("新加坡", "新加坡", "新加坡", "新加坡", 1.3521, 103.8198, listOf("新加坡", "singapore", "狮城"), 30.0),
        Place("曼谷", "曼谷", "泰国", "泰国", 13.7563, 100.5018, listOf("曼谷", "bangkok"), 50.0),
        Place("普吉", "普吉岛", "泰国", "泰国", 7.8804, 98.3923, listOf("普吉岛", "普吉", "phuket"), 50.0),
        Place("巴厘岛", "巴厘岛", "印度尼西亚", "印度尼西亚", -8.4095, 115.1889, listOf("巴厘岛", "bali"), 60.0),
        Place("巴黎", "巴黎", "法国", "法国", 48.8566, 2.3522, listOf("巴黎", "paris"), 50.0),
        Place("伦敦", "伦敦", "英国", "英国", 51.5074, -0.1278, listOf("伦敦", "london"), 50.0),
        Place("纽约", "纽约", "美国", "美国", 40.7128, -74.0060, listOf("纽约", "new york", "nyc"), 60.0),
        Place("洛杉矶", "洛杉矶", "美国", "美国", 34.0522, -118.2437, listOf("洛杉矶", "los angeles", "la"), 70.0),
        Place("旧金山", "旧金山", "美国", "美国", 37.7749, -122.4194, listOf("旧金山", "san francisco", "sf", "三藩市"), 50.0),
        Place("悉尼", "悉尼", "澳大利亚", "澳大利亚", -33.8688, 151.2093, listOf("悉尼", "sydney"), 60.0),
        Place("墨尔本", "墨尔本", "澳大利亚", "澳大利亚", -37.8136, 144.9631, listOf("墨尔本", "melbourne"), 60.0),
        Place("迪拜", "迪拜", "阿联酋", "阿联酋", 25.2048, 55.2708, listOf("迪拜", "dubai"), 60.0)
    )

    /** 别名索引：小写别名 -> 标准地点。长别名优先注册，避免「中国香港」被「香港」抢先覆盖。 */
    private val ALIAS_INDEX: Map<String, Place> by lazy {
        val m = linkedMapOf<String, Place>()
        for (p in P) {
            val all = (p.aliases + listOf(p.name, p.fullName, p.province))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedByDescending { it.length }
            for (a in all) if (!m.containsKey(a)) m[a] = p
        }
        m
    }

    private val ADMIN_SUFFIXES = listOf(
        "维吾尔自治区", "壮族自治区", "回族自治区", "特别行政区", "自治区", "省", "市", "区", "县",
        "地区", "盟", "自治州", "州", "镇", "乡", "村", "街道", "路", "街", "岛"
    ).sortedByDescending { it.length }

    /** 去行政区划后缀 + 去空白 + 小写。 */
    fun normalize(input: String): String {
        var t = input.trim().lowercase().replace(Regex("""\s+"""), "")
        for (s in ADMIN_SUFFIXES) {
            if (t.length > s.length && t.endsWith(s)) {
                t = t.removeSuffix(s)
                break
            }
        }
        return t
    }

    /** 严格匹配：归一化后必须恰好等于某个别名。用于自然语言里判定「这整个词是不是地点」。 */
    fun findExact(text: String): Place? = ALIAS_INDEX[normalize(text)]

    /** 从任意文本中找出最可能的标准地点（宽松：允许包含）。供地点输入框使用。 */
    fun findByQuery(text: String): Place? {
        val t = normalize(text)
        if (t.length < 2) return null
        ALIAS_INDEX[t]?.let { return it }

        // 未精确命中：找被文本包含 / 包含文本的最长别名（长度 >= 2）
        var best: Place? = null
        var bestLen = 0
        for ((alias, place) in ALIAS_INDEX) {
            if (alias.length < 2) continue
            val hit = t.contains(alias) || alias.contains(t)
            if (hit && alias.length > bestLen) {
                best = place
                bestLen = alias.length
            }
        }
        return best
    }

    /** 由标准地名构造「选定地点」查询（带中心点与半径）。 */
    fun queryFor(place: Place, raw: String = place.name): LocationQuery = LocationQuery(
        raw = raw,
        normalized = place.name,
        centerLat = place.lat,
        centerLon = place.lon,
        radiusKm = place.radiusKm
    )

    /** 最近城市 + 距离（公里）。 */
    fun nearest(lat: Double, lon: Double): Pair<Place, Double>? {
        if (lat.isNaN() || lon.isNaN() || abs(lat) > 90.0 || abs(lon) > 180.0) return null
        var best: Place? = null
        var bestD = Double.MAX_VALUE
        for (p in P) {
            val d = distanceKm(lat, lon, p.lat, p.lon)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best?.let { it to bestD }
    }

    /** Haversine 距离，单位公里。 */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** GPS 兜底地名：最近城市在 120km 内则给出「省 + 市」，否则只给国家/坐标。 */
    fun labelFor(lat: Double, lon: Double): Pair<String, String> {
        val coord = String.format(Locale.US, "%.4f,%.4f", lat, lon)
        val n = nearest(lat, lon) ?: return ("" to coord)
        val (p, d) = n
        if (d > 120.0) return (p.country to coord)
        val label = if (p.province == p.fullName || p.province == p.name) p.fullName
        else "${p.province}${p.fullName}"
        return (p.country to label)
    }
}
