package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LoginApiService(
    internal val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
) {
    internal val officialAppSessions = ConcurrentHashMap<String, OfficialAppLoginSession>()

    companion object {
        private const val TAG = "LoginApiService"

        /** QQ 音乐"鉴权凭据已过期"的业务错误码。 */
        internal const val AUTH_EXPIRED_CODE = 2000

        internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val refreshMutex = Mutex()

        /**
         * 进程内共享的登录服务实例。
         *
         * [refreshMutex] 是全局唯一的，但官方 App 授权会话表 `officialAppSessions` 挂在实例上；
         * 各调用点若各自 `LoginApiService()`，会出现同一账号多份会话表、并发续签互相覆盖的问题。
         * 业务代码应通过本属性取用，仅在需要注入自定义 [OkHttpClient] 时才直接构造。
         */
        val shared: LoginApiService by lazy { LoginApiService() }

        internal fun extractCookie(
            setCookies: List<String>,
            name: String,
        ): String? {
            val prefix = "$name="
            for (sc in setCookies) {
                if (sc.startsWith(prefix, ignoreCase = true)) {
                    return sc.substringAfter("=").substringBefore(";").trim()
                }
            }
            return null
        }
    }

    suspend fun fetchQqQrCode(): QrCodeInfo = fetchQqQrCodeImpl()

    suspend fun pollQqQrStatus(qrsig: String): PollResult = pollQqQrStatusImpl(qrsig)

    suspend fun exchangeOAuthForMusicKey(cookies: MutableMap<String, String>): Boolean = exchangeOAuthForMusicKeyImpl(cookies)

    internal suspend fun refreshByExistingMusicKey(
        musicId: String,
        musicKey: String,
    ): LegOutcome = refreshByExistingMusicKeyImpl(musicId, musicKey)

    internal suspend fun refreshQQLoginToken(
        openid: String,
        accessToken: String,
        refreshToken: String = "",
        musicId: String = "",
        musicKey: String = "",
    ): LegOutcome =
        refreshQQLoginTokenImpl(
            openid = openid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            musicId = musicId,
            musicKey = musicKey,
        )

    internal suspend fun refreshWeChatLoginToken(
        musicId: String,
        musicKey: String,
        refreshToken: String,
        openid: String = "",
    ): LegOutcome =
        refreshWeChatLoginTokenImpl(
            musicId = musicId,
            musicKey = musicKey,
            refreshToken = refreshToken,
            openid = openid,
        )

    suspend fun fetchWeChatQrCode(): QrCodeInfo = fetchWeChatQrCodeImpl()

    suspend fun pollWeChatQrStatus(uuid: String): PollResult = pollWeChatQrStatusImpl(uuid)

    suspend fun fetchOfficialAppQrCode(): QrCodeInfo = fetchOfficialAppQrCodeImpl()

    fun pollOfficialAppQrStatus(identifier: String): PollResult = pollOfficialAppQrStatusImpl(identifier)

    fun cancelOfficialAppSession(identifier: String) {
        cancelOfficialAppSessionImpl(identifier)
    }

    internal suspend fun refreshOfficialAppToken(
        musicId: String,
        refreshToken: String,
        refreshKey: String,
        musicKey: String,
        loginType: Int,
        openid: String = "",
        accessToken: String = "",
    ): LegOutcome =
        refreshOfficialAppTokenImpl(
            musicId = musicId,
            refreshToken = refreshToken,
            refreshKey = refreshKey,
            musicKey = musicKey,
            loginType = loginType,
            openid = openid,
            accessToken = accessToken,
        )

    suspend fun refreshPlaybackCredentials(creds: PlaybackCredentials): PlaybackCredentials? = refreshPlaybackCredentialsImpl(creds)

    /**
     * 校验当前会话中的 `qm_keyst` 凭据有效性；已失效或指定强制刷新时，加锁并发安全地执行登录凭据续签。
     *
     * 判定不再只看"`qm_keyst` 是否存在"，而是由 [UserSession.isSessionStale] 结合服务端下发的
     * 过期时间（或 [UserSession.MUSIC_KEY_TTL_MS] 兜底）决定是否已进入续签窗口。
     *
     * @param forceRefresh 是否强制忽略现有凭证进行续签刷新
     * @return 刷新成功或现有凭据有效返回 `true`；刷新失败返回 `false`
     */
    suspend fun ensureMusicKey(forceRefresh: Boolean = false): Boolean = renewSession(forceRefresh) is KeepAliveResult.Success

    /**
     * 执行一次"保活"：确保当前凭据可用，必要时续签，并把结果**分类**返回。
     *
     * 分类是整套 90 天方案的核心：`Transient`（网络/超时）值得短退避重试，
     * `Rejected`/`NoTicket`（服务端拒绝、票据全缺）重试只会白撞风控，必须直接提示重新登录。
     *
     * @param force 忽略新鲜窗口、退避与"需重登"标记，强制发起一次续签
     * @return 分类后的续签结果
     */
    internal suspend fun renewSession(force: Boolean = false): KeepAliveResult =
        withContext(Dispatchers.IO) {
            if (!force && isMusicKeyFresh()) {
                return@withContext KeepAliveResult.Success
            }
            if (!force && UserSession.isSessionReauthRequired) {
                // 已知凭据被服务端拒绝：不拿坏票据反复撞风控，等用户重新登录（或显式 force）。
                ApiLogger.i(TAG, "Skip music key renewal: session requires re-login")
                return@withContext KeepAliveResult.Rejected(AUTH_EXPIRED_CODE)
            }
            if (!force && UserSession.isRefreshBackingOff) {
                ApiLogger.i(TAG, "Skip music key renewal: refresh backoff active (failCount=${UserSession.profile.refreshFailCount})")
                return@withContext KeepAliveResult.Transient("refresh backoff active")
            }
            refreshMutex.withLock {
                // 并发调用在等锁期间可能已被其它协程完成续签，锁内重新评估。
                if (!force && isMusicKeyFresh()) {
                    return@withLock KeepAliveResult.Success
                }
                UserSession.setSessionState(SessionState.Renewing)
                val result = renewMusicKeyLocked()
                when (result) {
                    KeepAliveResult.Success -> {
                        UserSession.recordRefreshResult(success = true)
                        ApiLogger.i(TAG, "Music key renewed successfully")
                    }

                    is KeepAliveResult.Transient -> {
                        UserSession.recordRefreshResult(success = false)
                        UserSession.setSessionState(SessionState.TransientFailure)
                        ApiLogger.w(
                            TAG,
                            "Music key renewal transient failure: ${result.reason} (failCount=${UserSession.profile.refreshFailCount})",
                        )
                    }

                    is KeepAliveResult.Rejected, KeepAliveResult.NoTicket -> {
                        // 明确失败：不进入退避重试循环，直接置为"需重新登录"。
                        UserSession.recordRefreshResult(success = false)
                        UserSession.setSessionState(SessionState.ReauthRequired)
                        ApiLogger.w(TAG, "Music key renewal rejected: $result")
                    }
                }
                result
            }
        }

    /**
     * 判断当前会话凭据是否仍处于新鲜窗口内。
     *
     * 与上游 v1.8.0 的"`qm_keyst` 非空即新鲜"快速通道对齐，但保留 [UserSession.isSessionStale]
     * 的临近过期判断：只判非空会让 90 天保活退化成"等失败再续"，与本项目"永不让用户看到过期"
     * 的目标冲突。
     *
     * @return 存在任一 musickey 键且未进入 [UserSession.isSessionStale] 的续签窗口时返回 `true`
     */
    private fun isMusicKeyFresh(): Boolean {
        val profile = UserSession.profile
        val hasKey =
            profile.musicKey.isNotBlank() ||
                !profile.cookies["qm_keyst"].isNullOrEmpty() ||
                !profile.cookies["musickey"].isNullOrEmpty() ||
                !profile.cookies["qqmusic_key"].isNullOrEmpty()
        if (!hasKey) return false
        return !UserSession.isSessionStale
    }

    /**
     * 在已持有 [refreshMutex] 的前提下，按上游 v1.8.0 的腿序依次尝试续签。
     *
     * 腿序按"票据寿命"而非"代码顺序"排列：统一账户网关 `refresh_token`（数月，三渠道共用）
     * → 微信专有换票 → QQ 互联 `access_token`（约 90 天）→ 旧 `musickey`（1–2 小时）
     * → `p_skey`/`skey`（约 1 天）。任何一腿成功即返回。
     *
     * @return 分类后的续签结果
     */
    private suspend fun renewMusicKeyLocked(): KeepAliveResult {
        val cookies = UserSession.profile.cookies.toMutableMap()
        val currentKey = UserSession.profile.musicKey.ifBlank { cookies["qm_keyst"] ?: cookies["musickey"] ?: "" }
        val currentUin = UserSession.profile.uin.ifBlank { cookies["musicid"] ?: cookies["uin"] ?: "" }
        val isWeChat = cookies["tmeLoginType"] == "1" || currentKey.startsWith("W_X_")

        val refreshKey = cookies["refresh_key"].orEmpty()
        val rawLoginType =
            cookies["tmeLoginType"]?.toIntOrNull()?.takeIf { it in listOf(1, 2) }
                ?: if (isWeChat) 1 else 2
        val refreshToken =
            cookies["refresh_token"]
                ?: if (isWeChat) cookies["psrf_wxrefresh_token"] else cookies["psrf_qqrefresh_token"]
        val openid =
            if (isWeChat) {
                cookies["openid"] ?: cookies["psrf_wxopenid"].orEmpty()
            } else {
                cookies["psrf_qqopenid"] ?: cookies["openid"].orEmpty()
            }
        val accessToken =
            if (isWeChat) {
                cookies["access_token"] ?: cookies["psrf_wx_access_token"].orEmpty()
            } else {
                cookies["psrf_qqaccess_token"] ?: cookies["access_token"].orEmpty()
            }

        var attempted = false
        var transientReason: String? = null
        var rejectedCode = 0

        // ① 统一账户网关续签通道（覆盖官方 App 扫码、微信扫码与 QQ 互联扫码）。
        if (!refreshToken.isNullOrEmpty() && currentUin.isNotEmpty()) {
            attempted = true
            when (refreshOfficialAppToken(
                musicId = currentUin,
                refreshToken = refreshToken,
                refreshKey = refreshKey,
                musicKey = currentKey,
                loginType = rawLoginType,
                openid = openid,
                accessToken = accessToken,
            )) {
                LegOutcome.Success -> return KeepAliveResult.Success
                LegOutcome.Transient -> transientReason = "refreshOfficialAppToken: network"
                LegOutcome.Rejected -> rejectedCode = rejectedCode.takeIf { it != 0 } ?: -2
                LegOutcome.Unavailable -> Unit
            }
        }

        // ② 微信登录专有换票重试（上游 v1.8.0 新增腿，微信账号在网关腿失败后的专用通道）。
        if (isWeChat && !refreshToken.isNullOrEmpty() && currentUin.isNotEmpty()) {
            attempted = true
            when (refreshWeChatLoginToken(
                musicId = currentUin,
                musicKey = currentKey,
                refreshToken = refreshToken,
                openid = openid,
            )) {
                LegOutcome.Success -> return KeepAliveResult.Success
                LegOutcome.Transient -> transientReason = "refreshWeChatLoginToken: network"
                LegOutcome.Rejected -> rejectedCode = rejectedCode.takeIf { it != 0 } ?: -4
                LegOutcome.Unavailable -> Unit
            }
        }

        // ③ QQ 互联 OAuth 降级换票兜底（非微信账号且具备 openid 与 accessToken）。
        if (!isWeChat && openid.isNotEmpty() && accessToken.isNotEmpty()) {
            attempted = true
            val qqRefreshToken = cookies["psrf_qqrefresh_token"] ?: cookies["refresh_token"].orEmpty()
            when (refreshQQLoginToken(
                openid = openid,
                accessToken = accessToken,
                refreshToken = qqRefreshToken,
                musicId = currentUin,
                musicKey = currentKey,
            )) {
                LegOutcome.Success -> return KeepAliveResult.Success
                LegOutcome.Transient -> transientReason = "refreshQQLoginToken: network"
                LegOutcome.Rejected -> rejectedCode = rejectedCode.takeIf { it != 0 } ?: -3
                LegOutcome.Unavailable -> Unit
            }
        }

        // ④ 使用现有 musickey 申请续期：上游 v1.8.0 起不再限制渠道，也不再前置判断 key 是否过期。
        if (currentUin.isNotEmpty() && currentKey.isNotEmpty()) {
            attempted = true
            when (refreshByExistingMusicKey(currentUin, currentKey)) {
                LegOutcome.Success -> return KeepAliveResult.Success
                LegOutcome.Transient -> transientReason = transientReason ?: "refreshByExistingMusicKey: network"
                LegOutcome.Rejected -> rejectedCode = rejectedCode.takeIf { it != 0 } ?: AUTH_EXPIRED_CODE
                LegOutcome.Unavailable -> Unit
            }
        }

        // ⑤ p_skey / skey 换 musickey：兜底腿。失败多为网络或 skey 已过期，
        //    不单独据此判定"需重新登录"，避免一次网络抖动就把用户踢下线。
        val pskey = cookies["p_skey"] ?: cookies["skey"]
        if (!pskey.isNullOrEmpty()) {
            attempted = true
            if (exchangeOAuthForMusicKey(cookies)) return KeepAliveResult.Success
            transientReason = transientReason ?: "exchangeOAuthForMusicKey: failed"
        }

        return when {
            !attempted -> KeepAliveResult.NoTicket
            rejectedCode != 0 -> KeepAliveResult.Rejected(rejectedCode)
            transientReason != null -> KeepAliveResult.Transient(transientReason)
            else -> KeepAliveResult.Rejected(-9)
        }
    }

    /**
     * 强制执行一次音乐鉴权凭证 `qm_keyst` 的续期与刷新。
     *
     * @return 刷新成功返回 `true`，失败返回 `false`
     */
    suspend fun forceRefreshMusicKey(): Boolean = ensureMusicKey(forceRefresh = true)
}
