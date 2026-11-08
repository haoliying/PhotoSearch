package com.example.photosearch.search

import com.example.photosearch.data.PhotoExif

/**
 * 地点模糊匹配 + 从自然语言中提取地点短语（v3.6.0）。
 *
 * 「模糊」包含三层，按精确率从高到低：
 *  1. 半径匹配：从候选地点里「选定」时带中心点，GPS 在半径内即命中；
 *  2. 子串/包含匹配：输入「北京」命中「中国 北京市 朝阳区」，输入「深圳市南山区」
 *     也能命中其中的「深圳市」；
 *  3. 编辑距离容错：只对 >=3 字、且首字相同、距离 <=1 生效，
 *     避免「北京」误命中「南京」（两者距离 1，但长度 2 不启用容错）。
 */
object LocationMatcher {

    private val SEG_SPLIT = Regex("""[\s,，、;；!！?？:：.。()（）\[\]【】]+""")

    private val AT_PLACE = Regex(
        """在\s*([\u4e00-\u9fa5a-zA-Z]{2,12}?)\s*(?:拍的|拍摄的|拍|照的|照|旅游|旅行|游玩|出差|附近|一带)"""
    )
    private val LABEL_PLACE = Regex("""(?:地点|位置|地方|location)\s*[:：]?\s*([\u4e00-\u9fa5a-zA-Z]{2,14})""")
    private val PLACE_PHOTO = Regex("""([\u4e00-\u9fa5]{2,10})(?:拍的照片|拍的|的照片)""")

    /** 该照片是否满足地点查询。没有任何地点信息的照片一律不参与地点筛选。 */
    fun matches(exif: PhotoExif, q: LocationQuery): Boolean {
        val hay = exif.searchableLocation().lowercase()
        if (hay.isBlank() && !exif.hasGps) return false

        val needle = PlaceCatalog.normalize(q.normalized.ifBlank { q.raw })
        if (needle.length < 2) return false

        // 1) 半径匹配（选定地点）
        val clat = q.centerLat
        val clon = q.centerLon
        if (clat != null && clon != null && exif.hasGps) {
            val d = PlaceCatalog.distanceKm(clat, clon, exif.latitude!!, exif.longitude!!)
            if (d <= q.radiusKm) return true
        }

        val parts = hay.split(' ', ',', '，', ';', '；').map { PlaceCatalog.normalize(it) }.filter { it.length >= 2 }

        // 2) 子串 / 包含（双向，但要求候选片段 >= 2 字）
        if (hay.contains(needle)) return true
        for (p in parts) {
            if (p.contains(needle) || needle.contains(p)) return true
        }

        // 3) 编辑距离容错：仅 >=3 字且首字相同，避免短地名串味
        if (q.fuzzy && needle.length >= 3) {
            for (p in parts) {
                if (p.length >= 3 && p[0] == needle[0] && levenshtein(p, needle) <= 1) return true
            }
        }
        return false
    }

    /**
     * 从查询中提取地点。knownPlaces 是「当前时间区间内实际存在的地点」，
     * 由 ViewModel 传入，使自建/手动标注的地点也能被识别。
     * 返回 (地点查询 或 null, 剥离地点短语后的剩余文本)。
     */
    fun extract(text: String, knownPlaces: Set<String> = emptySet()): Pair<LocationQuery?, String> {
        val t = text.trim()
        if (t.isEmpty()) return null to ""

        // a) 整串就是一个地点（如直接搜「北京」「深圳市」）。
        //    这里必须严格匹配，否则「北京烤鸭」会被当成地点、内容词被吃掉。
        if (t.length in 2..14) {
            val place = PlaceCatalog.findExact(t)
            if (place != null) return PlaceCatalog.queryFor(place, t) to ""
            val known = matchKnown(t, knownPlaces)
            if (known != null) return LocationQuery(t, PlaceCatalog.normalize(t)) to ""
        }

        // b) 明确的「地点:xxx」
        LABEL_PLACE.find(t)?.let { m ->
            val raw = m.groupValues[1].trim()
            val q = buildQuery(raw, knownPlaces)
            if (q != null) return q to cut(t, m.range)
        }

        // c) 「在X拍的」——介词明确，剥离整段
        AT_PLACE.find(t)?.let { m ->
            val raw = m.groupValues[1].trim()
            val q = buildQuery(raw, knownPlaces)
            if (q != null) return q to cut(t, m.range)
        }

        // d) 「X拍的照片」——不带介词，只有确认是已知地点才提取
        PLACE_PHOTO.find(t)?.let { m ->
            val raw = m.groupValues[1].trim()
            val q = buildQuery(raw, knownPlaces)
            if (q != null) return q to cut(t, m.range)
        }

        // e) 空格 / 标点分隔的组合查询，如「北京 汽车」「2023年 北京 海边」。
        //    只认严格匹配的地名或已知地点，避免把「北京烤鸭」这类词误拆。
        for (seg in t.split(SEG_SPLIT)) {
            val s = seg.trim()
            if (s.length < 2) continue
            val place = PlaceCatalog.findExact(s)
            if (place != null) {
                return PlaceCatalog.queryFor(place, s) to removeSegment(t, s)
            }
            val known = matchKnown(s, knownPlaces)
            if (known != null) {
                return LocationQuery(s, PlaceCatalog.normalize(s)) to removeSegment(t, s)
            }
        }

        return null to t
    }

    private fun removeSegment(text: String, seg: String): String =
        text.replace(seg, " ").replace(Regex("""\s+"""), " ").trim()

    /** 去掉残留的介词/连接词，剩余文本交给内容检索。 */
    fun cleanup(text: String): String {
        var t = text.replace(Regex("""\s+"""), " ").trim()
        for (p in listOf("在", "于")) {
            if (t.startsWith(p) && t.length > p.length + 1) {
                t = t.removePrefix(p).trim()
                break
            }
        }
        return t
    }

    private fun buildQuery(raw: String, knownPlaces: Set<String>): LocationQuery? {
        if (raw.length < 2) return null
        val place = PlaceCatalog.findByQuery(raw)
        if (place != null) return PlaceCatalog.queryFor(place, raw)
        if (matchKnown(raw, knownPlaces) != null) return LocationQuery(raw, PlaceCatalog.normalize(raw))
        return null
    }

    private fun matchKnown(raw: String, knownPlaces: Set<String>): String? {
        if (knownPlaces.isEmpty()) return null
        val n = PlaceCatalog.normalize(raw)
        if (n.length < 2) return null
        return knownPlaces.firstOrNull { k ->
            val kn = PlaceCatalog.normalize(k)
            // 只允许「完全相同」或「输入是地名的简称」（如 西湖 / 杭州西湖）。
            // 不做「输入包含地名」，否则「北京烤鸭」会被当成地点。
            kn.length >= 2 && (kn == n || (kn.contains(n) && kn.length - n.length <= 2))
        }
    }

    private fun cut(text: String, range: IntRange): String =
        (text.substring(0, range.first) + " " + text.substring(range.last + 1))
            .replace(Regex("""\s+"""), " ").trim()

    /** Levenshtein 编辑距离（按字符，非字节）。 */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
