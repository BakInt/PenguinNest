package org.melodist.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/**
 * 会话鉴权健康状态。
 *
 * 与"是否登录"无关：登录态只说明本地有凭据，本状态说明凭据是否仍能换到有效直链。
 * 界面据此区分"会员到期"与"登录已过期"，避免把鉴权失败显示成"未开通会员"。
 */
@Serializable
enum class SessionState {
    /** 凭据新鲜，无需处理。 */
    Healthy,

    /** 正在续签，界面可展示"同步中"。 */
    Renewing,

    /** 网络/服务端临时异常，凭据本身仍可能有效，按退避自动重试。 */
    TransientFailure,

    /** 服务端明确拒绝或已无任何可用票据，只能由用户重新登录。 */
    ReauthRequired,
}

/**
 * 一次"保活"（凭据续签）的结果分类。
 *
 * 分类的意义在于决定后续动作：`Transient` 值得重试，`Rejected`/`NoTicket` 重试只会白撞风控。
 */
sealed interface KeepAliveResult {
    /** 凭据已新鲜或续签成功。 */
    data object Success : KeepAliveResult

    /** 网络异常 / HTTP 非 2xx / 超时，可短退避后重试。 */
    data class Transient(
        val reason: String,
    ) : KeepAliveResult

    /** 服务端明确拒绝（code≠0、2000 等），需重新登录。 */
    data class Rejected(
        val code: Int,
    ) : KeepAliveResult

    /** 四条续签腿全部缺少票据，需重新登录。 */
    data object NoTicket : KeepAliveResult
}

/**
 * 单条续签腿的执行结果。
 *
 * 仅用于内部把"网络失败"与"服务端拒绝"区分开，不对外暴露。
 */
internal enum class LegOutcome {
    Success,
    Rejected,
    Transient,
    /** 该腿缺少必要票据，未发起请求。 */
    Unavailable,
}

/**
 * 解析服务端返回的过期时间字段为毫秒时间戳。
 *
 * 兼容三种常见形态：毫秒时间戳、秒级时间戳、相对秒数（如 `expire_in`）。
 * 特别地，小于 1.6e9 的数值按"相对秒数"处理——否则一个 `expire_in=7776000`
 * 会被当成 1970 年的绝对时间，导致凭据被立即判定过期而疯狂续签。
 *
 * @param raw 服务端原始字段值
 * @param now 当前时间戳（毫秒），仅用于相对秒数换算
 * @return 毫秒级过期时间戳；无法解析或非正数时返回 `null`
 */
internal fun parseSessionExpireAt(
    raw: String?,
    now: Long = System.currentTimeMillis(),
): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val value = text.toLongOrNull()
    if (value != null) {
        return when {
            value <= 0L -> null
            value >= 100_000_000_000L -> value
            value >= 1_600_000_000L -> value * 1000L
            else -> now + value * 1000L
        }
    }
    return PlaybackCredentialsManager.parseExpireTimestamp(text)
}

/**
 * 登录态保活入口。
 *
 * 只做一件事：把"凭据还能不能用"收敛成一个可分类的结果，供界面、后台任务与播放链路共同消费。
 * 真正的续签逻辑与互斥仍在 [LoginApiService] 内（single-flight 语义不变）。
 */
object SessionKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 会话状态的只读流，供 Compose 界面订阅。 */
    val stateFlow: StateFlow<SessionState> =
        UserSession.profileFlow
            .map { it.sessionState }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, UserSession.profile.sessionState)

    /** 当前会话状态的同步快照。 */
    val state: SessionState
        get() = UserSession.profile.sessionState

    /** 是否已确定需要用户重新登录。 */
    val isReauthRequired: Boolean
        get() = state == SessionState.ReauthRequired

    /**
     * 确保当前凭据可用；必要时执行一次续签。
     *
     * @param force 忽略新鲜窗口与退避，强制续签（登录后、用户手动重试时使用）
     * @return 分类后的续签结果
     */
    suspend fun keepAlive(force: Boolean = false): KeepAliveResult = LoginApiService.shared.renewSession(force)
}
