package com.example.photosearch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.photosearch.MainActivity
import com.example.photosearch.data.*
import com.example.photosearch.ml.EnhancedImageAnalyzer
import com.example.photosearch.ml.VisionLanguageAnalyzer
import com.example.photosearch.search.VlmMode
import com.example.photosearch.search.VlmPolicy
import com.example.photosearch.search.VlmPolicyConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.10.0：**常驻后台守护 + 相册变化监听 + 增量自动分析 + 检索隔离**。
 *
 * v3.11.2 针对「速度仍不快 / 后台就不分析了 / 进度一次跳几百」三处反馈重写调度：
 *
 *  1. **滑动窗口真并发**：v3.11.1 是「块内 N 张并发、块间串行收尾」，整块屏障
 *     让最慢的一张拖住同块其他线程。现在固定 N 个 worker 从 Channel 取任务，
 *     任何一张完成立刻补下一张，全程 N 张 in-flight；并发上限 1..8（默认 4）。
 *     分析跑在**低优先级专用固定线程池**，与检索用的 Dispatchers.Default 隔离。
 *  2. **后台持续分析**：持有 PARTIAL_WAKE_LOCK（熄屏后 CPU 不再被冻结），
 *     并且空闲时进入低频心跳对账（60s），即使 ContentObserver 没回调也能
 *     发现新照片并续跑；通知栏实时显示进度与暂停/继续入口。
 *  3. **进度单一数据源**：进度只由本服务的协调协程逐张累加并上报，
 *     ViewModel 不再用全库 count 自己算；[AnalysisControl.monotonic] 保证
 *     不回退，[AnalysisControl.shouldEmit] 时间节流且收尾必发最终值。
 *  4. **暂停 / 继续**：显式暂停状态持久化，服务重建后仍保持；暂停时当前
 *     任务收尾即挂起，继续后从剩余照片续跑，不重复、不丢结果。
 *
 * 磁盘（AnalysisResultStore）始终是结果唯一权威，增量事件只是运行期加速；
 * 前端只合并这批 id、只失效这批文档缓存，**绝不全量 clear / 全库重算检索**。
 */
class AnalysisService : Service() {

    companion object {
        const val CHANNEL_ID = "analysis_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_WATCH_START = "com.example.photosearch.action.WATCH_START"
        const val ACTION_SCAN = "com.example.photosearch.action.SCAN"
        const val ACTION_REANALYZE_ALL = "com.example.photosearch.action.REANALYZE_ALL"
        const val ACTION_STOP = "com.example.photosearch.action.STOP"
        /** 兼容旧调用点（v3.9.0 的 resume），语义仍是「拉起守护」。 */
        const val ACTION_RESUME = "com.example.photosearch.action.RESUME"
        /** v3.11.2：用户显式暂停 / 继续分析（与「启动守护」的 RESUME 区分）。 */
        const val ACTION_PAUSE_ANALYSIS = "com.example.photosearch.action.PAUSE_ANALYSIS"
        const val ACTION_CONTINUE = "com.example.photosearch.action.CONTINUE"

        private const val TAG = "AnalysisService"
        private const val RESULT_BATCH_MAX = 20
        private const val RESULT_BATCH_INTERVAL_MS = 300L

        private val _analysisState = MutableStateFlow(AnalysisProgress())
        val analysisState = _analysisState.asStateFlow()

        /** 进程内镜像：仅供同进程冷启动快速读取，真正权威是磁盘。 */
        val analyzedMetadata = ConcurrentHashMap<Long, PhotoMetadata>()

        /** 服务是否处于常驻守护状态（区别于「分析循环是否正在跑」）。 */
        @Volatile
        var watchActive: Boolean = false
            private set

        /**
         * v3.11.2：暂停标志放 companion，服务被系统回收后重建时也能读到；
         * 同时由 AppConfig 持久化，用户意图不会被开机/心跳偷偷覆盖。
         */
        @Volatile
        var pausedFlag: Boolean = false
            private set

        fun isRunning(): Boolean = _analysisState.value.isRunning
        fun isWatchActive(): Boolean = watchActive
        fun isPaused(): Boolean = pausedFlag

        fun reset() {
            _analysisState.value = AnalysisProgress()
            analyzedMetadata.clear()
        }

        // --- 外部请求入口（从 App 前台调用，避免后台启动前台服务限制） ---

        fun requestWatch(context: Context) = start(context, ACTION_WATCH_START)
        fun requestScan(context: Context) = start(context, ACTION_SCAN)
        fun requestReanalyzeAll(context: Context) = start(context, ACTION_REANALYZE_ALL)
        fun requestPause(context: Context) = start(context, ACTION_PAUSE_ANALYSIS)
        fun requestContinue(context: Context) = start(context, ACTION_CONTINUE)

        fun requestStop(context: Context) {
            if (!watchActive) return
            try {
                context.startService(Intent(context, AnalysisService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "停止守护失败", e)
            }
        }

        private fun start(context: Context, action: String) {
            try {
                val i = Intent(context, AnalysisService::class.java).setAction(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 后台启动受限时置 pending，等 App 回到前台兜底
                AppConfig.setWatchPending(context, true)
                Log.w(TAG, "启动守护受限，等待下次进入应用恢复", e)
            }
        }
    }

    data class AnalysisProgress(
        val isRunning: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val isComplete: Boolean = false,
        val isResumed: Boolean = false,
        val vlmEnabled: Boolean = false,
        val vlmErrors: Int = 0,
        /** v3.11.2：显式暂停中（守护仍常驻，只是不处理照片）。 */
        val paused: Boolean = false
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * v3.11.2：分析专用**固定线程池**（低 nice 值），不再是 v3.11.1 的单线程 executor。
     * 池按当前「分析并发数」懒创建，配置变化时重建；onDestroy 才真正 shutdown。
     */
    @Volatile
    private var poolParallelism = 0
    private var analysisPool: ExecutorService? = null
    private var analysisDispatcher: CoroutineDispatcher = Dispatchers.Default

    @Synchronized
    private fun prepareAnalysisPool() {
        val p = AnalysisScheduling.coerceParallelism(AppConfig.getAnalysisParallelism(this))
        if (p == poolParallelism && analysisPool != null && !analysisPool!!.isShutdown) return
        analysisPool?.shutdown()
        val pool = Executors.newFixedThreadPool(p) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "ps-analysis-$p").apply { isDaemon = true }
        }
        analysisPool = pool
        analysisDispatcher = pool.asCoroutineDispatcher()
        poolParallelism = p
    }

    /** v3.11.2：熄屏后保持 CPU 运行；分析时持有、空闲/暂停时释放。 */
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PhotoSearch:Analysis"
            ).apply {
                setReferenceCounted(false)
                acquire(AnalysisControl.WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败（不影响前台分析）", e)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock!!.release()
        } catch (_: Exception) { }
        wakeLock = null
    }

    private lateinit var resultStore: AnalysisResultStore
    private lateinit var observer: MediaStoreObserver

    private var analysisJob: Job? = null
    private var onDeviceAnalyzer: EnhancedImageAnalyzer? = null
    private var vlmAnalyzer: VisionLanguageAnalyzer? = null

    /** 上一次对账的相册 id 快照，用于检测运行期新增/删除。 */
    @Volatile
    private var lastAlbumIds: Set<Long> = emptySet()
    /** 服务本次启动后是否已完成过至少一次对账（首次对账不通知 UI 增量刷新）。 */
    @Volatile
    private var hasReconciledOnce = false
    /** 本次守护期间累计分析完成的张数（通知与 UI 展示用）。 */
    @Volatile
    private var autoAnalyzedCount = 0
    /** 本轮循环是否正常跑完；决定中断兜底是否写 markProgress。 */
    @Volatile
    private var completed = false
    @Volatile
    private var vlmDegraded = false

    /** v3.11.2：进度上报的上一帧值与时间，跨轮重置，避免节流把首帧吞掉。 */
    private var lastEmittedProgress = -1
    private var lastEmitTime = 0L

    /** v3.11.2：低频心跳的唤醒信号，「继续分析」时立刻结束等待重新对账。 */
    @Volatile
    private var resumeSignal: CompletableDeferred<Unit>? = null

    /** v3.11.1：并发分析时「手动选择消费」的互斥锁，避免 SharedPreferences 读改写竞态。 */
    private val vlmConsumeLock = Any()

    /**
     * v3.11.2：循环代次号。「重新分析全部」会 cancel 旧循环并立刻起新循环，
     * 旧循环的 finally 若晚于新循环的初始化执行，会误关掉新循环的分析器 /
     * 唤醒锁。这里让 finally 只在自己仍是最新一代时做资源清理。
     */
    @Volatile
    private var loopGeneration = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        resultStore = AnalysisResultStore(applicationContext)
        pausedFlag = AppConfig.isAnalysisPaused(this)
        observer = MediaStoreObserver(serviceScope) {
            // ContentObserver 防抖后回调：只做增量对账，不重新启动服务
            dispatchAnalysis(forceFull = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 契约：startForegroundService 启动后必须尽快进入前台。
        val notification = buildNotification(currentIdleText(), 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val wasActive = watchActive
        val watchEnabled = AppConfig.isBackgroundWatchEnabled(this)
        watchActive = true
        AppConfig.markWatchStarted(this)
        AppConfig.setWatchPending(this, false)
        if (watchEnabled) observer.register(contentResolver) else observer.unregister(contentResolver)
        emitWatch(analyzing = analysisJob?.isActive == true)

        when (intent?.action) {
            ACTION_STOP -> {
                stopWatch()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ANALYSIS -> {
                setPaused(true)
                // 不取消 job：正在跑的任务收尾后由 awaitIfPaused 挂起，已落盘结果不丢
                updateState(isRunning = false, paused = true)
                updateNotification(
                    "已暂停分析 · 已完成 ${_analysisState.value.progress} / ${_analysisState.value.total}",
                    _analysisState.value.progress, _analysisState.value.total
                )
            }
            ACTION_CONTINUE -> {
                setPaused(false)
                // 立刻唤醒低频心跳；若循环已不在则重新拉起
                resumeSignal?.complete(Unit)
                if (analysisJob?.isActive != true) dispatchAnalysis(forceFull = false)
                updateState(paused = false)
                updateNotification(
                    "继续分析 · 从剩余照片续跑",
                    _analysisState.value.progress, _analysisState.value.total
                )
            }
            ACTION_REANALYZE_ALL -> dispatchAnalysis(forceFull = true, clearFirst = true)
            ACTION_SCAN -> dispatchAnalysis(forceFull = false)
            ACTION_WATCH_START, ACTION_RESUME, null -> {
                if (!wasActive) dispatchAnalysis(forceFull = false)
            }
        }
        return if (watchEnabled) START_STICKY else START_NOT_STICKY
    }

    private fun setPaused(value: Boolean) {
        pausedFlag = value
        AppConfig.setAnalysisPaused(this, value)
        emitWatch(analyzing = analysisJob?.isActive == true && !value)
    }

    /** v3.11.2：统一构造守护状态，paused 单点写入，避免多处状态不一致。 */
    private fun emitWatch(analyzing: Boolean, autoAnalyzed: Int = autoAnalyzedCount) {
        AnalysisEvents.emitWatch(
            AnalysisEvents.WatchState(
                active = watchActive,
                autoAnalyzed = autoAnalyzed,
                analyzing = analyzing,
                paused = pausedFlag
            )
        )
    }

    /**
     * 串行调度分析。已有循环在跑且非 forceFull 时直接跳过（循环末尾会再对账一次，
     * 不会漏掉分析期间新增的照片），避免并发分析同一批、也避免高频唤醒。
     * v3.11.2：循环本身跑在 Dispatchers.Default，真正的分析 worker 在专用低优先级池。
     */
    private fun dispatchAnalysis(forceFull: Boolean = false, clearFirst: Boolean = false) {
        // v3.11.2：用户显式暂停时完全静默（不启动循环、不被相册变化唤醒）；
        // 「继续分析」会先清掉 pausedFlag 再重新拉起，按差集续跑。
        if (pausedFlag && !forceFull) return
        val active = analysisJob?.isActive == true
        if (active && !forceFull) return
        if (active) analysisJob?.cancel()
        val gen = ++loopGeneration
        analysisJob = serviceScope.launch(Dispatchers.Default) {
            runUntilSettled(clearFirst, gen)
        }
    }

    /**
     * 对账 + 分析，直到相册里没有待分析照片为止。
     * v3.11.2：守护开启且跑完一轮后**不退出循环**，进入低频心跳持续对账，
     * 保证「退到后台也一直在分析新照片」，而不是只依赖 ContentObserver 回调。
     */
    private suspend fun runUntilSettled(clearFirst: Boolean, generation: Long) {
        if (clearFirst) {
            resultStore.clear()
            analyzedMetadata.clear()
            lastAlbumIds = emptySet()
            hasReconciledOnce = false
            autoAnalyzedCount = 0
            completed = false
            AnalysisEvents.resetWatch()
            emitWatch(analyzing = true)
        }

        prepareAnalysisPool()
        val metaFlushStep = AppConfig.getSegmentSize(this@AnalysisService).coerceIn(10, 200)
        val repository = PhotoRepository(this@AnalysisService)

        onDeviceAnalyzer = EnhancedImageAnalyzer(this@AnalysisService)
        vlmAnalyzer = VisionLanguageAnalyzer(this@AnalysisService)

        try {
            var round = 0
            /** 上一轮待处理集合：避免云端 AI 持续失败时在 while 里原地无限重试。 */
            var lastPendingSignature: Set<Long> = emptySet()
            while (currentCoroutineContext().isActive) {
                val photos = repository.loadAllPhotos()
                val ids = photos.map { it.id }

                val delta = AnalysisPlanner.diff(lastAlbumIds, ids)
                lastAlbumIds = ids.toHashSet()
                if (hasReconciledOnce && delta.changed) {
                    AnalysisEvents.emitAlbum(
                        AnalysisEvents.AlbumChanged(delta.added.size, delta.removed.size)
                    )
                }
                hasReconciledOnce = true

                val vlmConfig = readVlmConfig()
                val useVlm = vlmConfig.enabled
                val planPolicy = AnalysisPlanner.VlmPlanPolicy(
                    enabled = useVlm,
                    modeId = vlmConfig.mode.id,
                    selectedIds = vlmConfig.selectedIds,
                    uncertainThreshold = vlmConfig.uncertainThreshold
                )

                val stored = resultStore.load()
                val analyzed: Map<Long, PhotoMetadata> = stored?.metadataMap ?: emptyMap()
                if (round == 0) {
                    analyzedMetadata.clear()
                    analyzedMetadata.putAll(analyzed)
                }

                val plan = AnalysisPlanner.plan(ids, analyzed, planPolicy)

                if (plan.complete) {
                    resultStore.markComplete(photos.size, analyzed.size)
                    emitIdle(photos.size, useVlm, minOf(analyzed.size, photos.size))
                    completed = true
                    releaseWakeLock()
                    if (!AppConfig.isBackgroundWatchEnabled(this@AnalysisService)) {
                        stopAfterOneShot()
                        return
                    }
                    // v3.11.2 低频心跳：只查 MediaStore id（不读 JSONL），
                    // 有新照片 / 用户点「继续」就立刻回到外层重新对账续跑。
                    awaitNextHeartbeat(repository)
                    continue
                }

                val signature = plan.pendingIds.toHashSet()
                if (round > 0 && signature == lastPendingSignature && delta.added.isEmpty()) {
                    Log.w(TAG, "同一批照片重复待处理，停止本轮对账循环")
                    resultStore.markProgress(photos.size, plan.doneCount)
                    emitIdle(photos.size, useVlm, plan.doneCount)
                    completed = true
                    releaseWakeLock()
                    if (!AppConfig.isBackgroundWatchEnabled(this@AnalysisService)) {
                        stopAfterOneShot()
                        return
                    }
                    awaitNextHeartbeat(repository)
                    lastPendingSignature = emptySet()
                    continue
                }
                lastPendingSignature = signature

                val byId = HashMap<Long, PhotoItem>(photos.size * 2)
                photos.forEach { byId[it.id] = it }

                acquireWakeLock()
                runPlan(photos, byId, plan, vlmConfig, metaFlushStep,
                    autoTriggered = hasReconciledOnce && round > 0)
                round++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "后台分析中断", e)
            updateState(isRunning = false, isComplete = false)
        } finally {
            if (!completed && analyzedMetadata.isNotEmpty()) {
                resultStore.markProgress(analyzedMetadata.size, analyzedMetadata.size)
            }
            // 只有当前代才清理共享资源；旧代已被 forceFull 取代，不要动新代的状态
            if (generation == loopGeneration) {
                releaseWakeLock()
                onDeviceAnalyzer?.close()
                onDeviceAnalyzer = null
                vlmAnalyzer = null
                emitWatch(analyzing = false)
            }
        }
    }

    /**
     * v3.11.2：空闲心跳。用轻量 id 差集判断是否有新照片，避免每 60 秒全量读 JSONL；
     * 「继续分析」通过 [resumeSignal] 立刻结束等待，不用等满一个周期。
     */
    private suspend fun awaitNextHeartbeat(repository: PhotoRepository) {
        while (currentCoroutineContext().isActive) {
            val signal = CompletableDeferred<Unit>()
            resumeSignal = signal
            var signaled = false
            try {
                withTimeoutOrNull(AnalysisControl.IDLE_HEARTBEAT_MS) {
                    signal.await()
                    signaled = true
                }
            } finally {
                if (resumeSignal === signal) resumeSignal = null
            }
            if (pausedFlag) continue   // 暂停中：继续等，不触发对账
            // 用户点「继续分析」被唤醒：立刻回外层重新对账，不等满 60 秒
            if (signaled) return
            val fresh = repository.loadAllPhotos().map { it.id }
            if (AnalysisPlanner.diff(lastAlbumIds, fresh).changed) return
            // 无变化：再等一个心跳周期；lastAlbumIds 不变，交给下一轮统一对账
        }
    }

    /**
     * v3.11.2：滑动窗口并发跑一批 pending。
     *  - N 个 worker 从 workChannel 取 id，任何一张完成立刻取下一张（无块屏障）；
     *  - worker 只负责「分析 + 单张落盘」，结果经 resultChannel 交给唯一协调点；
     *  - 协调点独占 buffer / done / 进度 / 通知 / 增量事件，无共享可变状态，
     *    进度逐张 +1 且单调不回退 —— 这是「进度不再跳几百」的关键。
     */
    private suspend fun runPlan(
        photos: List<PhotoItem>,
        byId: Map<Long, PhotoItem>,
        plan: AnalysisPlanner.Plan,
        vlmConfig: VlmPolicyConfig,
        metaFlushStep: Int,
        autoTriggered: Boolean
    ) {
        val useVlm = vlmConfig.enabled
        val parallelism = AnalysisScheduling.coerceParallelism(
            AppConfig.getAnalysisParallelism(this@AnalysisService)
        )
        var done = plan.doneCount
        var sinceFlush = 0
        var degradedNotified = false
        val vlmErrorCount = AtomicInteger(0)
        vlmDegraded = false

        val buffer = ArrayList<Pair<Long, PhotoMetadata>>(RESULT_BATCH_MAX)
        var lastFlushMs = System.currentTimeMillis()
        lastEmittedProgress = done - 1
        lastEmitTime = 0L

        updateState(
            isRunning = true, progress = done, total = photos.size, isComplete = false,
            isResumed = plan.resumed, vlmEnabled = useVlm, vlmErrors = 0,
            paused = pausedFlag
        )
        val startText = if (plan.resumed) {
            "继续分析：还剩 ${plan.pendingCount} 张（已完成 ${plan.doneCount}）"
        } else {
            "开始分析 ${plan.pendingCount} 张照片"
        }
        updateNotification(startText, done, photos.size)
        emitWatch(analyzing = true)

        val workChannel = Channel<Long>(Channel.UNLIMITED)
        val resultChannel = Channel<Pair<Long, PhotoMetadata?>>(Channel.UNLIMITED)
        plan.pendingIds.forEach { workChannel.trySend(it) }
        workChannel.close()

        try {
            coroutineScope {
                val workers = (0 until parallelism).map {
                    launch(analysisDispatcher) {
                        for (id in workChannel) {
                            awaitIfPaused()
                            currentCoroutineContext().ensureActive()
                            val photo = byId[id]
                            val metadata = if (photo == null) null else {
                                try {
                                    analyzeOne(photo, vlmConfig, analyzedMetadata[id], vlmErrorCount)
                                } catch (e: Exception) {
                                    Log.e(TAG, "单张分析失败 [$id]", e)
                                    PhotoMetadata(photoId = id, isAnalyzed = true)
                                }
                            }
                            if (metadata != null) {
                                resultStore.appendResult(id, metadata)   // O(1)，唯一权威
                                analyzedMetadata[id] = metadata
                            }
                            resultChannel.send(id to metadata)
                        }
                    }
                }
                // 所有 worker 结束后关闭结果通道，协调循环自然收尾
                launch {
                    workers.joinAll()
                    resultChannel.close()
                }

                for ((id, metadata) in resultChannel) {
                    currentCoroutineContext().ensureActive()
                    done++                       // 唯一写入点：每张 +1，绝不批量跳变
                    sinceFlush++
                    autoAnalyzedCount++
                    if (metadata != null) buffer += id to metadata

                    if (vlmDegraded && !degradedNotified) {
                        degradedNotified = true
                        updateNotification(
                            "AI 频繁出错，本轮改用本地分析（下次自动补齐）", done, photos.size
                        )
                    }

                    if (sinceFlush >= metaFlushStep) {
                        resultStore.markProgress(photos.size, done)
                        sinceFlush = 0
                    }

                    val now = System.currentTimeMillis()
                    if (IncrementBuffer.shouldFlush(
                            buffer.size, lastFlushMs, now, RESULT_BATCH_MAX, RESULT_BATCH_INTERVAL_MS
                        )
                    ) {
                        val batch = IncrementBuffer.coalesce(buffer)
                        buffer.clear()
                        AnalysisEvents.emitResults(AnalysisEvents.ResultBatch(batch, autoTriggered))
                        emitWatch(analyzing = true)
                        lastFlushMs = now
                    }

                    val statusText = if (useVlm) {
                        "后台分析 $done / ${photos.size}" +
                                if (vlmErrorCount.get() > 0) " (AI错误:${vlmErrorCount.get()})" else ""
                    } else {
                        "后台分析 $done / ${photos.size}（${parallelism} 路并发）"
                    }
                    emitProgress(done, photos.size, plan.resumed, useVlm, vlmErrorCount.get(), statusText)
                }

                workers.joinAll()   // 传播 worker 内未捕获异常
            }

            if (buffer.isNotEmpty()) {
                AnalysisEvents.emitResults(
                    AnalysisEvents.ResultBatch(IncrementBuffer.coalesce(buffer), autoTriggered)
                )
                buffer.clear()
            }

            resultStore.markComplete(photos.size, done)
            // 收尾帧必发，保证最终值 100% 到达 UI
            emitProgress(done, photos.size, plan.resumed, useVlm, vlmErrorCount.get(),
                "已分析 $done 张", force = true)
            updateState(
                isRunning = false, isComplete = true, progress = done, total = photos.size,
                isResumed = false, vlmEnabled = useVlm, vlmErrors = vlmErrorCount.get(),
                paused = pausedFlag
            )
            val completeMsg = when {
                useVlm && vlmErrorCount.get() == 0 -> "已分析 ${done} 张照片（含AI描述）"
                useVlm -> "已分析 ${done} 张，${vlmErrorCount.get()} 张缺AI描述，下次自动补齐"
                else -> "已分析 ${done} 张（仅本地标签）"
            }
            updateNotification(completeMsg, done, photos.size)
            completed = true
        } catch (e: CancellationException) {
            // 已落盘的结果即使事件没发出也不会丢：下次打开 App 会从磁盘 load() 补齐
            if (buffer.isNotEmpty()) {
                AnalysisEvents.emitResults(
                    AnalysisEvents.ResultBatch(IncrementBuffer.coalesce(buffer), autoTriggered)
                )
            }
            resultStore.markProgress(photos.size, done)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "单批分析异常", e)
            resultStore.markProgress(photos.size, done)
            updateState(isRunning = false, isComplete = false)
        }
    }

    /**
     * v3.11.2 暂停门控：worker 在取下一张前调用。暂停时释放 WakeLock、通知切暂停态，
     * 轮询等到继续后重新持有；用 [AnalysisControl.PAUSE_POLL_MS] 保证最多 300ms 恢复，
     * 不需要额外唤醒机制，也不会取消 Job（已落盘结果不丢）。
     */
    private suspend fun awaitIfPaused() {
        var entered = false
        while (pausedFlag && currentCoroutineContext().isActive) {
            if (!entered) {
                entered = true
                releaseWakeLock()
                val p = _analysisState.value.progress
                val t = _analysisState.value.total
                updateState(isRunning = false, paused = true)
                updateNotification("已暂停分析 · 已完成 $p / $t", p, t)
                emitWatch(analyzing = false)
            }
            delay(AnalysisControl.PAUSE_POLL_MS)
        }
        if (entered && !pausedFlag) {
            acquireWakeLock()
            updateState(isRunning = true, paused = false)
            emitWatch(analyzing = true)
        }
    }

    /** 无待分析照片：更新状态，通知切回「守护中」，但服务不退。 */
    private fun emitIdle(total: Int, useVlm: Boolean, progress: Int) {
        // v3.11.2：不再把 progress 直接设成 total（旧版会让进度条瞬间 100% 再回跳）
        updateState(
            isRunning = false, isComplete = true,
            progress = AnalysisControl.monotonic(_analysisState.value.progress, progress),
            total = total, isResumed = false, vlmEnabled = useVlm, vlmErrors = 0,
            paused = pausedFlag
        )
        updateNotification(
            if (autoAnalyzedCount > 0) "后台守护中 · 本次已分析 $autoAnalyzedCount 张，新照片将自动分析"
            else "后台守护中 · 新照片将自动分析",
            0, 0
        )
        emitWatch(analyzing = false)
    }

    /**
     * v3.11.0：分析单张。
     *  - 本地标签必写；已经有本地结果的照片直接复用，不重复跑 ML Kit；
     *  - 是否调用云端大模型由 [VlmPolicy.decide] 按「使用策略」逐张决定；
     *  - 手动勾选的照片在尝试过（无论成败）后即从待办集合消费，避免无限重跑；
     *  - 云端 AI 失败时写占位结果，下次由计划器按策略自动补齐。
     */
    private suspend fun analyzeOne(
        photo: PhotoItem,
        vlmConfig: VlmPolicyConfig,
        existing: PhotoMetadata?,
        vlmErrorCount: AtomicInteger
    ): PhotoMetadata {
        val isNew = existing == null || !existing.isAnalyzed
        val onDevice = if (isNew) {
            try {
                onDeviceAnalyzer!!.analyzePhoto(photo.uri, applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "本地分析失败 [${photo.id}]", e)
                PhotoMetadata(photoId = photo.id, isAnalyzed = true)
            }
        } else {
            existing!!
        }

        val confidence = VlmPolicy.localConfidence(onDevice)
        val hasDescription = onDevice.description.isNotBlank()
        val decision = VlmPolicy.decide(
            config = vlmConfig,
            photoId = photo.id,
            isNew = isNew,
            hasDescription = hasDescription,
            confidence = confidence
        )

        var description = onDevice.description
        var vlmKeywords = onDevice.vlmKeywords
        if (decision.useVlm && !vlmDegraded) {
            try {
                val vlmResult = vlmAnalyzer!!.analyzePhoto(photo.uri)
                if (vlmResult.success) {
                    description = vlmResult.description
                    vlmKeywords = vlmResult.keywords
                } else {
                    vlmErrorCount.incrementAndGet()
                    Log.w(TAG, "AI分析失败 [${photo.id}]: ${vlmResult.error}")
                }
            } catch (e: Exception) {
                vlmErrorCount.incrementAndGet()
                Log.e(TAG, "AI异常 [${photo.id}]", e)
            }
            if (photo.id in vlmConfig.selectedIds) {
                synchronized(vlmConsumeLock) {
                    AppConfig.removeVlmSelectedIds(this, listOf(photo.id))
                }
            }
            if (vlmErrorCount.get() >= 20) {
                vlmDegraded = true
                Log.w(TAG, "AI 连续失败 20 次，本轮降级为本地分析")
            }
        }

        return onDevice.copy(
            photoId = photo.id,
            description = description,
            vlmKeywords = vlmKeywords
        )
    }

    /** v3.11.0：从配置读取一次策略快照（含网络条件判断）。 */
    private fun readVlmConfig(): VlmPolicyConfig = VlmPolicyConfig(
        mode = VlmMode.fromId(AppConfig.getVlmModeId(this)),
        uncertainThreshold = AppConfig.getUncertainThreshold(this),
        selectedIds = AppConfig.getVlmSelectedIds(this),
        apiKeyPresent = AppConfig.hasApiKey(this),
        wifiOnly = AppConfig.isVlmWifiOnly(this),
        wifiAvailable = isWifiAvailable()
    )

    /** 「仅 Wi-Fi 下使用大模型」的网络判断；无权限 / 判断失败时按可用处理。 */
    private fun isWifiAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            true
        }
    }

    /** v3.11.2：时间节流 + 单调不回退 + 收尾必发最终值。 */
    private fun emitProgress(
        current: Int,
        total: Int,
        isResumed: Boolean,
        vlmEnabled: Boolean,
        vlmErrors: Int,
        statusText: String,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val value = AnalysisControl.monotonic(lastEmittedProgress, current)
        if (!AnalysisControl.shouldEmit(lastEmittedProgress, value, lastEmitTime, now, force)) return
        lastEmittedProgress = value
        lastEmitTime = now
        updateState(
            isRunning = true, progress = value, total = total, isComplete = false,
            isResumed = isResumed, vlmEnabled = vlmEnabled, vlmErrors = vlmErrors,
            paused = pausedFlag
        )
        updateNotification(statusText, value, total)
    }

    private fun currentIdleText(): String = when {
        pausedFlag -> "已暂停分析 · 点「继续分析」从剩余照片续跑"
        _analysisState.value.isRunning -> "正在分析 ${_analysisState.value.progress} / ${_analysisState.value.total}"
        else -> "后台守护中 · 新照片将自动分析"
    }

    private fun stopWatch() {
        observer.unregister(contentResolver)
        analysisJob?.cancel()
        watchActive = false
        completed = true
        emitWatch(analyzing = false)
        releaseWakeLock()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 守护关闭时的一次性收尾：注销监听、退出前台、停止服务。 */
    private fun stopAfterOneShot() {
        observer.unregister(contentResolver)
        watchActive = false
        emitWatch(analyzing = false)
        releaseWakeLock()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(
        isRunning: Boolean = _analysisState.value.isRunning,
        progress: Int = _analysisState.value.progress,
        total: Int = _analysisState.value.total,
        isComplete: Boolean = _analysisState.value.isComplete,
        isResumed: Boolean = _analysisState.value.isResumed,
        vlmEnabled: Boolean = _analysisState.value.vlmEnabled,
        vlmErrors: Int = _analysisState.value.vlmErrors,
        paused: Boolean = _analysisState.value.paused
    ) {
        _analysisState.value = AnalysisProgress(
            isRunning = isRunning, progress = progress, total = total,
            isComplete = isComplete, isResumed = isResumed,
            vlmEnabled = vlmEnabled, vlmErrors = vlmErrors, paused = paused
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "照片分析", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台自动分析守护与进度通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AnalysisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("智能相册搜索 · 后台守护")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // v3.11.2：通知栏也提供暂停 / 继续入口，后台不用打开 App 就能控制
        if (pausedFlag) {
            val resumeIntent = PendingIntent.getService(
                this, 3,
                Intent(this, AnalysisService::class.java).setAction(ACTION_CONTINUE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "继续分析", resumeIntent)
        } else if (_analysisState.value.isRunning) {
            val pauseIntent = PendingIntent.getService(
                this, 2,
                Intent(this, AnalysisService::class.java).setAction(ACTION_PAUSE_ANALYSIS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停分析", pauseIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止守护", stopIntent)

        if (total > 0) builder.setProgress(total, progress, false)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress, total))
    }

    override fun onDestroy() {
        observer.unregister(contentResolver)
        watchActive = false
        if (!completed && analyzedMetadata.isNotEmpty()) {
            try {
                resultStore.markProgress(analyzedMetadata.size, analyzedMetadata.size)
            } catch (_: Exception) { }
        }
        emitWatch(analyzing = false)
        releaseWakeLock()
        serviceScope.cancel()
        analysisPool?.shutdownNow()
        analysisPool = null
        super.onDestroy()
    }
}
