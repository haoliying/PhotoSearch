package com.example.photosearch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.photosearch.data.AppConfig
import com.example.photosearch.service.AnalysisService

/**
 * v3.10.0：开机 / 应用升级后恢复「后台自动分析」守护。
 *
 * Android 12+ 限制后台启动前台服务，广播里直接 startForegroundService 可能抛
 * ForegroundServiceStartNotAllowedException。这里只做「尽力拉起」：
 *  - 成功：ContentObserver 随即恢复监听；
 *  - 失败：置一个 pending 标记，用户下次打开 App 时 MainActivity.onResume
 *    的 ensureBackgroundWatch() 会兜底启动（App 在前台时不受该限制）。
 *
 * 只有用户开启了「后台自动分析」且之前确实启用过守护才尝试，
 * 避免首次安装 / 未授权时擅自启动云端 AI 偷跑流量。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!AppConfig.isBackgroundWatchEnabled(context)) return
        if (!AppConfig.wasWatchEverStarted(context)) return

        AppConfig.setWatchPending(context, true)
        try {
            val i = Intent(context, AnalysisService::class.java).apply {
                this.action = AnalysisService.ACTION_WATCH_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
            AppConfig.setWatchPending(context, false)
        } catch (e: Exception) {
            // 后台启动受限：保持 pending，等待用户下次进入应用时兜底恢复
            Log.w(TAG, "开机后台启动受限，等待下次进入应用恢复守护", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
