package com.example.photosearch.service

import com.example.photosearch.data.PhotoMetadata
import com.example.photosearch.search.VlmMode
import com.example.photosearch.search.VlmPolicy
import com.example.photosearch.search.VlmPolicyConfig

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
 *
 * v3.11.0：新增「大模型使用策略」。旧版只要开了云端 AI，就会把所有
 * 「本地标签已有、但 AI 描述为空」的存量照片排进补描述队列 —— 用户无法挑选
 * 哪些照片交给大模型。现在由 [VlmPlanPolicy] 决定：
 *   OFF / ALL / NEW_ONLY / UNCERTAIN / SELECTED，且手动勾选的照片强制纳入。
 *
 * 不变量：
 *  1. 已落盘的 id 绝不出现在 pending 里（除非策略要求补 AI 描述）；
 *  2. 相册新增照片自动进入 pending，已分析照片绝不重复做本地分析；
 *  3. 照片从相册删除不影响其他 id 的计划；
 *  4. pending 为空 <=> complete，doneCount + pendingCount == totalCount。
 */
object AnalysisPlanner {

    /** v3.11.0：一次对账使用的大模型策略快照（纯数据，便于评测）。 */
    data class VlmPlanPolicy(
        /** 大模型通道是否可用（策略非 OFF + 有 API Key + 网络条件满足）。 */
        val enabled: Boolean = false,
        val modeId: String = VlmMode.OFF.id,
        val selectedIds: Set<Long> = emptySet(),
        val uncertainThreshold: Float = 0.55f
    ) {
        companion object {
            /** 兼容旧调用：true = 全部照片都补 AI 描述，false = 完全不补。 */
            fun legacy(enabled: Boolean): VlmPlanPolicy = VlmPlanPolicy(
                enabled = enabled,
                modeId = if (enabled) VlmMode.ALL.id else VlmMode.OFF.id
            )
        }
    }

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
     * 兼容 v3.10.0 及以前的调用点与离线评测：布尔开关等价于「全部 / 不使用」。
     */
    fun plan(
        photoIds: List<Long>,
        analyzed: Map<Long, PhotoMetadata>,
        vlmEnabled: Boolean
    ): Plan = plan(photoIds, analyzed, VlmPlanPolicy.legacy(vlmEnabled))

    /**
     * v3.11.0 主入口。
     * @param policy 大模型使用策略；[VlmPlanPolicy.enabled] 为 false 时完全不补描述。
     */
    fun plan(
        photoIds: List<Long>,
        analyzed: Map<Long, PhotoMetadata>,
        policy: VlmPlanPolicy
    ): Plan {
        // 去重且保序，避免同一张照片被排进队列两次
        val unique = LinkedHashSet<Long>(photoIds.size * 2)
        photoIds.forEach { unique.add(it) }

        val doneIds = HashSet<Long>(analyzed.size * 2)
        analyzed.forEach { (id, m) -> if (m.isAnalyzed) doneIds.add(id) }

        val config = VlmPolicyConfig(
            mode = VlmMode.fromId(policy.modeId),
            uncertainThreshold = policy.uncertainThreshold,
            selectedIds = policy.selectedIds,
            // 计划阶段只关心「策略是否允许」，Key / 网络由 enabled 统一表达
            apiKeyPresent = true,
            wifiOnly = false,
            wifiAvailable = true
        )

        val pending = ArrayList<Long>()
        var retryVlm = 0
        for (id in unique) {
            if (id !in doneIds) {
                pending.add(id)                         // 从未分析过（首次或增量）
                continue
            }
            // 已有本地结果：是否补云端大模型描述，完全交给策略决定
            if (!policy.enabled) continue
            val m = analyzed[id] ?: continue
            val decision = VlmPolicy.decide(
                config = config,
                photoId = id,
                isNew = false,
                hasDescription = m.description.isNotBlank(),
                confidence = VlmPolicy.localConfidence(m)
            )
            if (decision.useVlm) {
                pending.add(id)
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

    /**
     * v3.10.0：相册变化检测。
     * ContentObserver 只回调「某处变了」，不携带具体增删；后台守护重新查一次
     * MediaStore 拿到当前 id 列表后，用上一次快照求差，得到真正新增 / 删除的 id，
     * 用于：① 判断是否需要通知前端增量刷新列表；② 避免元数据变更误触发全量重跑。
     * 纯逻辑，可离线评测（新增、删除、无变化、乱序、重复）。
     */
    data class AlbumDelta(
        val added: List<Long>,
        val removed: List<Long>,
        val currentCount: Int
    ) {
        val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
    }

    fun diff(previousIds: Set<Long>, currentIds: List<Long>): AlbumDelta {
        val current = LinkedHashSet<Long>(currentIds.size * 2)
        currentIds.forEach { current.add(it) }              // 去重且保序
        val added = ArrayList<Long>()
        for (id in current) if (id !in previousIds) added.add(id)
        val removed = ArrayList<Long>()
        for (id in previousIds) if (id !in current) removed.add(id)
        return AlbumDelta(added, removed, current.size)
    }
}
