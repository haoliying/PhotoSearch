package com.example.photosearch.service

import com.example.photosearch.data.PhotoMetadata

/**
 * v3.9.0：断点续分析的决策核心。纯 Kotlin、不依赖 Android，可离线评测。
 *
 * 旧实现的问题（v3.8.0 及以前）：
 *  - 用「上次已分析张数」当作相册列表的**下标**来续跑：新增一张照片插到中间、
 *    MediaStore 排序变化、删除照片，都会让下标错位，重复分析已完成的、漏掉未完成的；
 *  - 要求 `saved.totalPhotos == photos.size`，照片总数一变就 clear() 删掉全部结果；
 *  - `isComplete == true` 后再启动会走 `startIndex = 0`，把已完成的全部重跑一遍；
 *  - 每 200 张才落盘一次，进程被强杀时这 200 张全部回退。
 *
 * 新实现只认 **photoId**：
 *   pending = 相册中的 id − 已成功落盘的 id
 * 另外，开启云端 AI 时，把「本地标签已有、但 AI 描述为空」的占位结果也纳入补齐队列，
 * 这样 VLM 限流 / 断网造成的半结果不必「重新分析全部」。
 *
 * 不变量：
 *  1. 已落盘的 id 绝不出现在 pending 里（除非需要补 AI 描述）；
 *  2. 相册新增照片自动进入 pending，已分析照片绝不重跑；
 *  3. 照片从相册删除不影响其他 id 的计划；
 *  4. pending 为空 <=> complete，doneCount + pendingCount == totalCount。
 */
object AnalysisPlanner {

    data class Plan(
        /** 本次需要分析 / 补齐的 photoId，顺序与相册一致。 */
        val pendingIds: List<Long>,
        /** 已经完整分析、本次不再处理的张数。 */
        val doneCount: Int,
        /** 相册总张数（已去重）。 */
        val totalCount: Int,
        /** 是否属于「接着上次继续」，用于通知与 UI 文案。 */
        val resumed: Boolean,
        val complete: Boolean,
        /** pending 中属于「补 AI 描述」而非首次分析的数量。 */
        val retryVlmCount: Int
    ) {
        val pendingCount: Int get() = pendingIds.size
    }

    /**
     * @param photoIds 当前相册的 photoId 列表（可能有重复，内部去重）
     * @param analyzed 持久化缓存中已落盘的结果（key 为 photoId）
     * @param vlmEnabled 本次分析是否真的会调用云端 AI（开关 + 有 Key）
     */
    fun plan(
        photoIds: List<Long>,
        analyzed: Map<Long, PhotoMetadata>,
        vlmEnabled: Boolean
    ): Plan {
        // 去重且保序，避免同一张照片被排进队列两次
        val unique = LinkedHashSet<Long>(photoIds.size * 2)
        photoIds.forEach { unique.add(it) }

        val doneIds = HashSet<Long>(analyzed.size * 2)
        analyzed.forEach { (id, m) -> if (m.isAnalyzed) doneIds.add(id) }

        val pending = ArrayList<Long>()
        var retryVlm = 0
        for (id in unique) {
            if (id !in doneIds) {
                pending.add(id)                         // 从未分析过
            } else if (vlmEnabled && analyzed[id]?.description.isNullOrBlank()) {
                pending.add(id)                         // 只有本地标签，补 AI 描述
                retryVlm++
            }
        }

        val total = unique.size
        val done = total - pending.size
        return Plan(
            pendingIds = pending,
            doneCount = done,
            totalCount = total,
            resumed = done > 0 && pending.isNotEmpty(),
            complete = pending.isEmpty(),
            retryVlmCount = retryVlm
        )
    }
}
