package com.example.photosearch.search

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 中文时间表达解析（v3.6.0）。
 *
 * 支持：绝对年份 / 年月 / 年月日、显式区间、相对时间、今年去年、季节、
 * 中文月份、部分公历节假日；返回 TimeRange 与「剥离时间短语后的剩余文本」，
 * 剩余文本继续交给内容检索，实现「时间 + 地点 + 内容」联动。
 *
 * 纯 JVM 实现（不依赖 Android），便于离线评测与单元测试。
 */
object TimeQueryParser {

    /** v3.7.0：界面上统一使用的日期输入格式说明，避免提示与实际解析能力不一致。 */
    const val DATE_FORMAT_HINT = "yyyy-MM-dd（例：2023-06-01），也支持 2023年6月1日 / 2023/6/1 / 2023.6.1"
    const val TIME_QUERY_HINT = "时间写法：2023年6月 · 2023-06-01 到 2023-12-31 · 最近三个月 · 去年夏天 · 国庆"

    data class ParseResult(val range: TimeRange?, val remainder: String)

    private val DATE_TOKEN = Regex(
        """(?<!\d)(\d{4})(?:[年/\-.](\d{1,2}))?(?:[月/\-.](\d{1,2}))?\s*日?(?!\d)"""
    )
    private val RANGE_CONNECTOR = Regex("""\s*(?:到|至|~|～|—|–|－|\-|直至)\s*""")
    private val RELATIVE = Regex("""(?:最近|近|过去|这)\s*([0-9一二三四五六七八九十半]+)\s*(天|日|周|个?月|年)""")
    private val YEAR_WORD = Regex("""(今年|去年|前年|明年)""")
    private val SEASON = Regex("""(今年|去年|前年)?\s*(春天|春季|夏天|夏季|秋天|秋季|冬天|冬季)""")
    private val CN_MONTH = Regex("""(今年|去年|前年)?\s*([一二三四五六七八九十]{1,3}|\d{1,2})\s*月""")
    private val HOLIDAY = Regex("""(今年|去年|前年)?\s*(国庆|十一|五一|劳动节|元旦)""")

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    fun parse(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): ParseResult {
        if (text.isBlank()) return ParseResult(null, "")

        // 1) 显式区间：两个日期 + 连接词
        val tokens = DATE_TOKEN.findAll(text).toList()
        if (tokens.size >= 2) {
            val a = tokens[0]
            val b = tokens[1]
            val middle = text.substring(a.range.last + 1, b.range.first)
            if (RANGE_CONNECTOR.containsMatchIn(middle)) {
                val s = dateStart(a, zone)
                val e = dateEnd(b, zone)
                if (s != null && e != null && s < e) {
                    val label = "${dateLabel(a)} ~ ${dateLabel(b)}"
                    return ParseResult(
                        TimeRange(s, e, label),
                        cut(text, a.range.first, b.range.last + 1)
                    )
                }
            }
        }

        // 2) 单个绝对日期
        val single = DATE_TOKEN.find(text)
        if (single != null) {
            val y = single.groupValues[1].toIntOrNull()
            if (y != null && y in 1900..2100) {
                val s = dateStart(single, zone)
                val e = dateEnd(single, zone)
                if (s != null && e != null && s < e) {
                    return ParseResult(
                        TimeRange(s, e, dateLabel(single)),
                        cut(text, single.range.first, single.range.last + 1)
                    )
                }
            }
        }

        // 3) 相对时间
        RELATIVE.find(text)?.let { m ->
            val n = number(m.groupValues[1])
            val unit = m.groupValues[2]
            if (n > 0) {
                val r = when {
                    unit == "天" || unit == "日" -> recentDays(n.toInt().coerceAtLeast(1), nowMillis, zone)
                    unit == "周" -> recentDays(n.toInt().coerceAtLeast(1) * 7, nowMillis, zone)
                    unit == "年" -> {
                        val end = endOfToday(nowMillis, zone)
                        val start = shift(nowMillis, Calendar.YEAR, -n.toInt().coerceAtLeast(1), zone)
                        TimeRange(start, end, "最近${m.groupValues[1]}$unit")
                    }
                    else -> { // 月 / 个月
                        val end = endOfToday(nowMillis, zone)
                        val months = if (m.groupValues[1].contains("半")) 0 else n.toInt().coerceAtLeast(1)
                        val start = if (months > 0) shift(nowMillis, Calendar.MONTH, -months, zone)
                        else shift(nowMillis, Calendar.DAY_OF_YEAR, -15, zone)
                        TimeRange(start, end, "最近${m.groupValues[1]}$unit")
                    }
                }
                return ParseResult(r, cut(text, m.range.first, m.range.last + 1))
            }
        }

        // 4) 今年 / 去年 / 前年 / 明年
        YEAR_WORD.find(text)?.let { m ->
            val base = yearOf(nowMillis, zone)
            val y = when (m.value) {
                "去年", "前年" -> if (m.value == "前年") base - 2 else base - 1
                "明年" -> base + 1
                else -> base
            }
            val r = wholeYear(y, zone)
            return ParseResult(r.copy(label = "${m.value}($y)"), cut(text, m.range.first, m.range.last + 1))
        }

        // 5) 季节
        SEASON.find(text)?.let { m ->
            val y = yearWordOffset(m.groupValues[1], nowMillis, zone)
            val (fromMonth, toMonthExclusive) = when (m.groupValues[2]) {
                "春天", "春季" -> 3 to 6
                "夏天", "夏季" -> 6 to 9
                "秋天", "秋季" -> 9 to 12
                else -> 12 to 3   // 冬季跨年
            }
            val s = startOfDay(y, fromMonth, 1, zone)
            val e = if (toMonthExclusive > fromMonth) startOfDay(y, toMonthExclusive, 1, zone)
            else startOfDay(y + 1, toMonthExclusive, 1, zone)
            return ParseResult(
                TimeRange(s, e, "${y}年${m.groupValues[2]}"),
                cut(text, m.range.first, m.range.last + 1)
            )
        }

        // 6) 中文月份（X月）
        CN_MONTH.find(text)?.let { m ->
            val mo = number(m.groupValues[2]).toInt()
            if (mo in 1..12) {
                val y = yearWordOffset(m.groupValues[1], nowMillis, zone)
                val s = startOfDay(y, mo, 1, zone)
                val e = shift(s, Calendar.MONTH, 1, zone)
                return ParseResult(
                    TimeRange(s, e, "${y}年${mo}月"),
                    cut(text, m.range.first, m.range.last + 1)
                )
            }
        }

        // 7) 公历节假日（农历节日不做，避免给出错误区间）
        HOLIDAY.find(text)?.let { m ->
            val y = yearWordOffset(m.groupValues[1], nowMillis, zone)
            val word = m.groupValues[2]
            val r = when (word) {
                "国庆", "十一" -> TimeRange(startOfDay(y, 10, 1, zone), startOfDay(y, 10, 8, zone), "${y}年国庆")
                "五一", "劳动节" -> TimeRange(startOfDay(y, 5, 1, zone), startOfDay(y, 5, 6, zone), "${y}年五一")
                "元旦" -> TimeRange(startOfDay(y, 1, 1, zone), startOfDay(y, 1, 3, zone), "${y}年元旦")
                else -> null
            }
            if (r != null) return ParseResult(r, cut(text, m.range.first, m.range.last + 1))
        }

        return ParseResult(null, text.trim())
    }

    // ------------------------------------------------------------------
    // 供 UI 直接调用的区间构造
    // ------------------------------------------------------------------

    fun thisYear(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): TimeRange =
        wholeYear(yearOf(nowMillis, zone), zone).copy(label = "今年")

    fun lastYear(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): TimeRange =
        wholeYear(yearOf(nowMillis, zone) - 1, zone).copy(label = "去年")

    fun recentMonths(months: Int, nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): TimeRange =
        TimeRange(
            shift(nowMillis, Calendar.MONTH, -months.coerceAtLeast(1), zone),
            endOfToday(nowMillis, zone),
            "最近${months}个月"
        )

    fun recentDays(days: Int, nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): TimeRange {
        val today = startOfDay(nowMillis, zone)
        return TimeRange(
            shift(today, Calendar.DAY_OF_YEAR, -(days.coerceAtLeast(1) - 1), zone),
            shift(today, Calendar.DAY_OF_YEAR, 1, zone),
            "最近${days}天"
        )
    }

    /**
     * 从两个日期字符串构造区间（起止均为闭区间，内部转成左闭右开）。
     * v3.7.0：支持 2023-06-01 / 2023年6月1日 / 2023/6/1 / 2023.6.1 / 20230601，
     * 也支持只写年月（2023-06 / 2023年6月，自动补为该月 1 日到月末）。
     */
    fun fromDateStrings(start: String, end: String, zone: TimeZone = TimeZone.getDefault()): TimeRange? {
        val s = parseYmd(start) ?: return null
        val e = parseYmd(end) ?: return null
        val sm = startOfDay(s[0], s[1], s[2], zone)
        val em = if (e.size >= 4 && e[3] == 1) {
            // 只写了年月：整月
            shift(startOfDay(e[0], e[1], 1, zone), Calendar.MONTH, 1, zone)
        } else {
            shift(startOfDay(e[0], e[1], e[2], zone), Calendar.DAY_OF_YEAR, 1, zone)
        }
        if (sm >= em) return null
        return TimeRange(sm, em, "${chineseDate(sm, zone)} 至 ${chineseDate(shift(em, Calendar.DAY_OF_YEAR, -1, zone), zone)}")
    }

    /** 把左闭右开区间格式化为闭区间展示（yyyy-MM-dd，供内部/日志使用）。 */
    fun format(range: TimeRange, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = zone
        val s = if (range.startMillis <= 0L) "最早" else fmt.format(range.startMillis)
        val e = if (range.endExclusive >= TimeRange.NO_UPPER) "至今" else {
            val endClosed = shift(range.endExclusive, Calendar.DAY_OF_YEAR, -1, zone)
            fmt.format(endClosed)
        }
        return "$s ~ $e"
    }

    /**
     * v3.7.0：界面统一使用的中文时间区间格式，例如
     * 「2023年6月1日 至 2023年12月31日」「2023年1月1日 至 至今」。
     */
    fun formatChinese(range: TimeRange, zone: TimeZone = TimeZone.getDefault()): String {
        val s = if (range.startMillis <= 0L) "最早" else chineseDate(range.startMillis, zone)
        val e = if (range.endExclusive >= TimeRange.NO_UPPER) "至今" else {
            chineseDate(shift(range.endExclusive, Calendar.DAY_OF_YEAR, -1, zone), zone)
        }
        return "$s 至 $e"
    }

    /**
     * 界面展示用的时间条件文本：快捷标签（今年 / 最近30天 / 去年夏天）优先，
     * 其余一律输出统一的中文日期区间，避免混用 `2023-01-01 ~ 2023-12-31`
     * 与 `2023年6月` 两种格式。
     */
    fun displayLabel(range: TimeRange, zone: TimeZone = TimeZone.getDefault()): String {
        val l = range.label
        return if (l.isNotBlank() && !l.contains('~') && !l.contains("至")) l
        else formatChinese(range, zone)
    }

    private fun chineseDate(millis: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun cut(text: String, from: Int, to: Int): String {
        val joined = (text.substring(0, from) + " " + text.substring(to))
            .replace(Regex("""\s+"""), " ").trim()
        // 「2023年在北京拍的汽车」里 DATE_TOKEN 只吃到 2023，残留的「年」
        // 不是内容词，清掉以免污染剩余文本的分词。
        return joined.replace(Regex("""(?:^|\s)年(?=\s|[^\d\s])"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
    }

    private fun dateStart(m: MatchResult, zone: TimeZone): Long? {
        val y = m.groupValues[1].toIntOrNull() ?: return null
        if (y !in 1900..2100) return null
        val mo = m.groupValues.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
        val d = m.groupValues.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 31) ?: 1
        return startOfDay(y, mo, d, zone)
    }

    private fun dateEnd(m: MatchResult, zone: TimeZone): Long? {
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues.getOrNull(2)?.toIntOrNull()
        val d = m.groupValues.getOrNull(3)?.toIntOrNull()
        return when {
            d != null -> shift(startOfDay(y, mo?.coerceIn(1, 12) ?: 1, d.coerceIn(1, 31), zone), Calendar.DAY_OF_YEAR, 1, zone)
            mo != null -> shift(startOfDay(y, mo.coerceIn(1, 12), 1, zone), Calendar.MONTH, 1, zone)
            else -> startOfDay(y + 1, 1, 1, zone)
        }
    }

    private fun dateLabel(m: MatchResult): String {
        val y = m.groupValues[1]
        val mo = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        val d = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
        return when {
            d != null -> "${y}年${mo ?: "1"}月${d}日"
            mo != null -> "${y}年${mo}月"
            else -> "${y}年"
        }
    }

    private fun startOfDay(y: Int, m: Int, d: Int, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(y, m - 1, d, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfDay(millis: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return startOfDay(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            zone
        )
    }

    private fun endOfToday(millis: Long, zone: TimeZone): Long =
        shift(startOfDay(millis, zone), Calendar.DAY_OF_YEAR, 1, zone)

    private fun shift(millis: Long, field: Int, amount: Int, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        c.add(field, amount)
        return c.timeInMillis
    }

    private fun wholeYear(y: Int, zone: TimeZone): TimeRange =
        TimeRange(startOfDay(y, 1, 1, zone), startOfDay(y + 1, 1, 1, zone), "${y}年")

    private fun yearOf(millis: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.YEAR)

    private fun yearWordOffset(word: String, nowMillis: Long, zone: TimeZone): Int {
        val y = yearOf(nowMillis, zone)
        return when (word) { "去年" -> y - 1; "前年" -> y - 2; else -> y }
    }

    private fun number(s: String): Double {
        if (s.isBlank()) return 0.0
        val digits = s.toIntOrNull()
        if (digits != null) return digits.toDouble()
        if (s.contains("半")) return 0.5
        val cn = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        // 支持「十」「十一」「二十」「二十三」等
        var total = 0
        var current = 0
        for (ch in s) {
            when {
                ch == '十' -> { current = if (current == 0) 1 else current; total += current * 10; current = 0 }
                cn.containsKey(ch) -> current = cn[ch] ?: 0
            }
        }
        return (total + current).toDouble()
    }

    /** 返回 [年, 月, 日]，或 [年, 月, 1, 1] 表示「只写了年月、按整月处理」。 */
    private fun parseYmd(text: String): IntArray? {
        val s = text.trim()

        // 完整日期：2023-06-01 / 2023年6月1日 / 2023/6/1 / 2023.6.1
        val full = Regex("""^(\d{4})\s*[年/\-.]\s*(\d{1,2})\s*[月/\-.]\s*(\d{1,2})\s*日?$""").find(s)
        if (full != null) {
            val y = full.groupValues[1].toIntOrNull() ?: return null
            val mo = full.groupValues[2].toIntOrNull() ?: return null
            val d = full.groupValues[3].toIntOrNull() ?: return null
            return if (y in 1900..2100 && mo in 1..12 && d in 1..31) intArrayOf(y, mo, d) else null
        }

        // 紧凑格式：20230601
        val compact = Regex("""^((?:19|20)\d{2})(\d{2})(\d{2})$""")
            .find(s.replace(Regex("""\s"""), ""))
        if (compact != null) {
            val y = compact.groupValues[1].toInt()
            val mo = compact.groupValues[2].toInt()
            val d = compact.groupValues[3].toInt()
            return if (mo in 1..12 && d in 1..31) intArrayOf(y, mo, d) else null
        }

        // 只写年月：2023-06 / 2023年6月 / 2023/6 → 整月
        val ym = Regex("""^(\d{4})\s*[年/\-.]\s*(\d{1,2})\s*月?$""").find(s)
        if (ym != null) {
            val y = ym.groupValues[1].toIntOrNull() ?: return null
            val mo = ym.groupValues[2].toIntOrNull() ?: return null
            return if (y in 1900..2100 && mo in 1..12) intArrayOf(y, mo, 1, 1) else null
        }
        return null
    }
}
