package com.example.photosearch.service

import com.example.photosearch.data.PhotoMetadata

/**
 * v3.10.0：后台分析结果在运行期的增量合并策略。
 * 纯 Kotlin、不依赖 Android / 协程，可离线评测。
 *
 * 背景：后台守护服务每分析完一张照片就落盘，并通过事件总线把「新完成的 id」
 * 增量通知给前端。几万张照片时如果一张一条事件、或每批都触发全库检索，
 * 前端搜索就会被刷爆 / 卡住。这里做两件事：
 *  1. 合并（coalesce）：同一 id 多次结果只保留最后一次（后写覆盖），去重；
 *  2. 节流（shouldFlush / batches）：累积到上限或超过间隔才发一批。
 *
 * 关键性质：前端收到一批后只需 invalidate 这批 id 的文档缓存，
 * 绝不调用 docCache.clear()（那会让下一条查询重建几万张索引）。
 * 缓冲丢失也不影响正确性 —— 磁盘（AnalysisResultStore）才是唯一权威，
 * 下次打开 App 会 load() 全量补齐。
 */
object IncrementBuffer {

    /** 同一 id 后写覆盖，返回去重后的「id -> metadata」；保持首次出现顺序。 */
    fun coalesce(events: List<Pair<Long, PhotoMetadata>>): Map<Long, PhotoMetadata> {
        if (events.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, PhotoMetadata>(events.size * 2)
        for ((id, m) in events) out[id] = m
        return out
    }

    /**
     * 把连续单张事件按 maxSize 切分成批次（每批内部再 coalesce）。
     * 供离线回放与「事件丢失后重放」评测使用。
     */
    fun batches(
        events: List<Pair<Long, PhotoMetadata>>,
        maxSize: Int = 20
    ): List<Map<Long, PhotoMetadata>> {
        if (events.isEmpty()) return emptyList()
        require(maxSize > 0) { "maxSize must be positive" }
        val out = ArrayList<Map<Long, PhotoMetadata>>()
        var buf = ArrayList<Pair<Long, PhotoMetadata>>(maxSize)
        for (e in events) {
            buf.add(e)
            if (buf.size >= maxSize) {
                out += coalesce(buf)
                buf = ArrayList(maxSize)
            }
        }
        if (buf.isNotEmpty()) out += coalesce(buf)
        return out
    }

    /** 是否该把当前缓冲发一批：达到条数上限，或距上次 flush 超过间隔。 */
    fun shouldFlush(
        buffered: Int,
        lastFlushMs: Long,
        nowMs: Long,
        maxSize: Int = 20,
        intervalMs: Long = 300L
    ): Boolean {
        if (buffered <= 0) return false
        if (buffered >= maxSize) return true
        return nowMs - lastFlushMs >= intervalMs
    }

    /** 从一批结果里挑出需要失效文档缓存的 id（就是这批的 key，去重后）。 */
    fun idsToInvalidate(batch: Map<Long, PhotoMetadata>): List<Long> =
        if (batch.isEmpty()) emptyList() else ArrayList(batch.keys)
}
