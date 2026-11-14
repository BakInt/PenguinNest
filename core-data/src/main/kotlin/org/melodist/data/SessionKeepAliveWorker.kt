package org.melodist.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.melodist.api.ApiLogger
import org.melodist.api.KeepAliveResult
import org.melodist.api.SessionKeeper
import org.melodist.api.UserSession
import java.util.concurrent.TimeUnit

/**
 * 后台登录态保活任务。
 *
 * 只做一件事：调用 [SessionKeeper.keepAlive] 让长效票据（`refresh_token` / `psrf_qqaccess_token`）
 * 定期被"摸"一次。这样即使多日不打开 App，`musickey` 也会在过期前被续上；
 * 同时避免长效票据因闲置超时（约 30 天不用）被服务端作废。
 *
 * 返回值约定：
 * - [KeepAliveResult.Success] → `Result.success()`
 * - [KeepAliveResult.Transient] → `Result.retry()`（交给 WorkManager 指数退避）
 * - [KeepAliveResult.Rejected] / [KeepAliveResult.NoTicket] → `Result.success()`
 *   停止重试：票据已被服务端拒绝或根本没有票据，重试只会白撞风控，等用户重新登录。
 *
 * 注意：国产 ROM 会杀后台、Doze 会延后任务，后台续签只保证"尽量不掉"，
 * 真正的主路径仍是"打开 App 即续签"。
 */
class SessionKeepAliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!UserSession.isLoggedIn) return Result.success()
        return when (val result = SessionKeeper.keepAlive()) {
            KeepAliveResult.Success -> Result.success()
            is KeepAliveResult.Transient -> Result.retry()
            is KeepAliveResult.Rejected, KeepAliveResult.NoTicket -> Result.success()
        }
    }
}

/**
 * [SessionKeepAliveWorker] 的调度入口。
 *
 * 周期取 12 小时：WorkManager 最小周期为 15 分钟，而 12 小时已足以覆盖
 * `musickey` 1–2 小时寿命之外的"多日不开机"场景，同时不会给服务端造成可观测的规律性请求。
 */
object SessionKeepAliveScheduler {
    private const val TAG = "SessionKeepAlive"

    private const val PERIODIC_NAME = "melodist.session.keep-alive"
    private const val ONE_SHOT_NAME = "melodist.session.keep-alive.oneshot"

    private fun networkConstraints(): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /**
     * 注册 12 小时周期保活任务（幂等，重复调用不会重复入队）。
     *
     * @param context 任意应用上下文
     */
    fun start(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SessionKeepAliveWorker>(12, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        runCatching {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure {
            ApiLogger.w(TAG, "Failed to schedule periodic session keep-alive", it)
        }
    }

    /**
     * 立即补一次保活（回前台时若会话不健康则调用）。
     *
     * @param context 任意应用上下文
     */
    fun kick(context: Context) {
        if (!UserSession.isLoggedIn) return
        val request =
            OneTimeWorkRequestBuilder<SessionKeepAliveWorker>()
                .setConstraints(networkConstraints())
                .build()
        runCatching {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            ApiLogger.w(TAG, "Failed to enqueue one-shot session keep-alive", it)
        }
    }

    /**
     * 取消全部保活任务（退出登录时调用）。
     *
     * @param context 任意应用上下文
     */
    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_NAME)
        }.onFailure {
            ApiLogger.w(TAG, "Failed to cancel session keep-alive", it)
        }
    }
}
