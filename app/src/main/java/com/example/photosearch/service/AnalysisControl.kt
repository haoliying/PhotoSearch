package com.example.photosearch.service

/**
 * v3.11.2：分析「暂停 / 继续」与「进度上报」的纯状态逻辑（无 Android 依赖）。
 *
 * 背景（用户反馈）：分析进度「每次会跳动几百个」。根因是 v3.11.1 里进度由
 * 两条链路并发写同一个 UI 状态、口径还不一致：
 *  - Service 的 analysisState 写 progress = 本轮 done；
 *  - ViewModel 的 absorbIncrement 又用 metadataCache 全库 count 写 doneCount。
 * 两个协程各自基于旧快照 copy()，互相覆盖，加上 emitIdle 直接把进度设成 100%，
 * 于是出现忽大忽小的跳变。
 *
 * 现在约定：**进度只有一个数据源（Service 的 AtomicInteger）**，ViewModel 只透传、
 * 不再自己算；上报一律经 [shouldEmit] 时间节流且保证最终值必达；数值经
 * [monotonic] 保证不回退。暂停状态由 [transition] 统一管理并持久化。
 */
object AnalysisControl {

    /** 进度上报最小间隔：既不平刷 UI，也不让数字一次跳几百。 */
    const val PROGRESS_EMIT_INTERVAL_MS = 200L
    /** 兜底：即使间隔未到，累计增量达到该值也发一次（防止极快分析时长时间不刷新）。 */
    const val PROGRESS_EMIT_STEP = 8
    /** 暂停门控轮询间隔：继续后最多等这么久就恢复，不需要额外唤醒信号。 */
    const val PAUSE_POLL_MS = 300L
    /** 守护空闲时的低频对账心跳：即使 ContentObserver 没回调，也保证后台持续分析。 */
    const val IDLE_HEARTBEAT_MS = 60_000L
    /** WakeLock 单次持有上限（30 分钟），防止异常路径忘记释放导致耗电。 */
    const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

    /**
     * 是否需要发一帧进度。
     *  - force = true（收尾 / 暂停 / 完成）时必发，保证最终值不丢；
     *  - 进度未增长不发（避免重复帧把 UI 拉回旧值）；
     *  - 距上次超过间隔、或累计增量达到步长，发一帧。
     */
    fun shouldEmit(
        lastValue: Int,
        value: Int,
        lastTimeMs: Long,
        nowMs: Long,
        force: Boolean = false
    ): Boolean {
        if (force) return true
        if (value <= lastValue) return false
        return (nowMs - lastTimeMs >= PROGRESS_EMIT_INTERVAL_MS) ||
                (value - lastValue >= PROGRESS_EMIT_STEP)
    }

    /** 进度单调不回退：跨轮对账、回到前台补齐磁盘结果时都不会往回跳。 */
    fun monotonic(prev: Int, next: Int): Int = if (next > prev) next else prev

    /** 是否允许开始下一张：暂停或服务停止时门控关闭。 */
    fun canProceed(paused: Boolean, active: Boolean): Boolean = active && !paused

    /** 分析控制状态。 */
    enum class State { RUNNING, PAUSED, IDLE }

    /** 用户 / 系统动作。 */
    enum class Action { PAUSE, CONTINUE, SETTLE, START }

    /**
     * 状态迁移（纯函数，便于离线评测覆盖所有边）：
     *  - RUNNING + PAUSE → PAUSED；PAUSED + CONTINUE → RUNNING；
     *  - 任意状态 + SETTLE → IDLE（本轮跑完，守护仍常驻）；
     *  - 任意状态 + START → RUNNING；
     *  - PAUSED 状态下 SETTLE 保持 PAUSED（用户显式暂停优先，不许被对账偷偷恢复）。
     */
    fun transition(state: State, action: Action): State = when (action) {
        Action.PAUSE -> State.PAUSED
        Action.CONTINUE -> State.RUNNING
        Action.START -> State.RUNNING
        Action.SETTLE -> if (state == State.PAUSED) State.PAUSED else State.IDLE
    }
}
