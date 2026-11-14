package org.melodist.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserProfile(
    var uin: String = "",
    var nick: String = "",
    var avatarUrl: String = "",
    var musicKey: String = "",
    var isVip: Boolean = false,
    var isHugeVip: Boolean = false,
    var isSvip: Boolean = false,
    var isYearVip: Boolean = false,
    var vipLevel: Int = 0,
    var nextVipLevel: Int = 0,
    var vipUpgradePercent: Float = 0f,
    var vipUpgradeDays: Int = 0,
    var vipExpireAt: String = "",
    var hugeVipExpireAt: String = "",
    var svipExpireAt: String = "",
    var greenVipExpireAt: String = "",
    var isCpLover: Boolean = false,
    var cpLoverExpireAt: String = "",
    var isGroupVip: Boolean = false,
    var groupVipExpireAt: String = "",
    var musicLevel: Int = 0,
    var musicScore: Int = 0,
    var encryptedUin: String = "",
    var cookies: Map<String, String> = emptyMap(),
    /**
     * 当前 [musicKey] / `qm_keyst` 的签发时间戳（毫秒）。
     *
     * `0` 表示来源未知（例如旧版本持久化的会话），此时凭据一律按"需要续签"处理，
     * 避免把已经失效的密钥当作有效凭据使用。
     */
    var musicKeyIssuedAt: Long = 0,
    /** 最近一次凭据续签失败的时间戳（毫秒），用于失败退避，`0` 表示无失败记录。 */
    var lastRefreshAttemptAt: Long = 0,
    /** 连续续签失败次数，成功续签后清零；用于指数退避。 */
    var refreshFailCount: Int = 0,
    /** 最近一次账号档案（会员信息）刷新成功的时间戳（毫秒）。 */
    var lastProfileRefreshAt: Long = 0,
    /**
     * 服务端下发的 `musickey` 过期时间戳（毫秒）。
     *
     * `0` 表示服务端未提供，此时回退到 [UserSession.MUSIC_KEY_TTL_MS] 推算。
     * 有该值时续签时机由服务端过期时间驱动，而不是"每 30 分钟盲续一次"。
     */
    var musicKeyExpireAt: Long = 0,
    /** 长效刷新票据（`refresh_token`）的过期时间戳（毫秒），`0` 表示未知。 */
    var refreshTokenExpireAt: Long = 0,
    /** 当前会话的鉴权健康状态，用于区分"会员到期"与"登录已过期"。 */
    var sessionState: SessionState = SessionState.Healthy,
) {
    val effectiveAvatarUrl: String
        get() {
            if (avatarUrl.isNotBlank() && !avatarUrl.contains("t_user_default")) return avatarUrl
            val qq =
                cookies["pt2gguin"].extractNumericQq()
                    ?: cookies["uin"].extractNumericQq()
                    ?: cookies["qqmusic_uin"].extractNumericQq()
                    ?: uin.extractNumericQq()
            return if (!qq.isNullOrBlank()) {
                "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=640"
            } else {
                avatarUrl
            }
        }
}

private fun String?.extractNumericQq(): String? {
    if (this == null) return null
    val trimmed = this.trim().trimStart('o')
    return if (trimmed.isNotBlank() && trimmed.all { it.isDigit() }) trimmed else null
}

object UserSession {
    /**
     * 鉴权凭据新鲜窗口的**兜底**时长。
     *
     * QQ 音乐 musicKey 的实际有效期约 1–2 小时；服务端返回 [UserProfile.musicKeyExpireAt] 时
     * 一律以服务端为准，本常量只在过期时间未知（旧档案、响应缺字段）时用于推算。
     *
     * 取 90 分钟而不是 30 分钟：旧实现每 30 分钟盲续一次，一天 48 次续签请求，
     * 纯属自找风控；按"剩余寿命 50% 才续"的策略，一天只需 3–6 次。
     */
    const val MUSIC_KEY_TTL_MS: Long = 90L * 60L * 1000L

    /**
     * 续签触发阈值：剩余寿命不足该值即触发续签，同时它也是"提前量"的上限。
     *
     * 例如 key 总寿命 90 分钟时，剩余 45 分钟即续；若服务端给出 8 小时有效期，
     * 也只在最后 45 分钟才续，避免过早续签浪费票据轮换次数。
     */
    const val REFRESH_LEAD_MAX_MS: Long = 45L * 60L * 1000L

    /** 续签失败退避的起始时长。 */
    private const val REFRESH_BACKOFF_BASE_MS: Long = 30L * 1000L

    /**
     * 续签失败退避的上限时长。
     *
     * 仅对 `Transient`（网络/超时）失败生效；服务端明确拒绝的凭据不再重试，
     * 因此上限从 30 分钟收紧到 10 分钟，网络恢复后能更快自愈。
     */
    private const val MAX_REFRESH_BACKOFF_MS: Long = 10L * 60L * 1000L

    private val _profileFlow = MutableStateFlow(UserProfile())
    val profileFlow: StateFlow<UserProfile> = _profileFlow.asStateFlow()

    private val _favoriteSongCount = MutableStateFlow<Int?>(null)
    val favoriteSongCount: StateFlow<Int?> = _favoriteSongCount.asStateFlow()

    /**
     * 更新当前用户收藏歌曲的总数缓存。
     *
     * @param count 收藏歌曲总数
     */
    fun updateFavoriteSongCount(count: Int) {
        _favoriteSongCount.value = count
    }

    var profile: UserProfile
        get() = _profileFlow.value
        set(value) {
            _profileFlow.value = value
        }

    val loginType: Int
        get() {
            val p = _profileFlow.value
            return p.cookies["tmeLoginType"]?.toIntOrNull()
                ?: if (p.musicKey.startsWith("W_X")) 1 else 2
        }

    val isLoggedIn: Boolean
        get() {
            val p = _profileFlow.value
            val hasValidKey =
                p.musicKey.isNotBlank() ||
                    p.cookies.containsKey("qm_keyst") ||
                    p.cookies.containsKey("p_skey") ||
                    p.cookies.containsKey("skey") ||
                    p.cookies.containsKey("qqmusic_key")
            val hasValidId = p.uin.isNotBlank() || p.cookies.containsKey("musicid")
            return hasValidId && hasValidKey
        }

    /**
     * 判断当前会话的鉴权凭据是否已进入"需要续签"的窗口，需要在发起业务请求前主动续签。
     *
     * [isLoggedIn] 只做存在性判断，无法区分"有凭据"与"凭据仍可用"；本属性补齐时效维度，
     * 使调用方能在服务端返回鉴权错误之前就触发续签。
     *
     * 判定优先使用服务端下发的 [UserProfile.musicKeyExpireAt]：剩余寿命不足"总寿命的一半"
     * 且不超过 [REFRESH_LEAD_MAX_MS] 时判定需要续签。过期时间未知时回退到签发时间 +
     * [MUSIC_KEY_TTL_MS] 推算。
     *
     * @return 凭据已过期、签发时间未知或已被标记为失效时返回 `true`
     */
    val isSessionStale: Boolean
        get() {
            val p = _profileFlow.value
            if (!isLoggedIn) return true
            val now = System.currentTimeMillis()
            val expireAt = p.musicKeyExpireAt
            if (expireAt > 0L) {
                val lifetime = (expireAt - p.musicKeyIssuedAt).takeIf { it > 0L } ?: MUSIC_KEY_TTL_MS
                val lead = minOf(lifetime / 2, REFRESH_LEAD_MAX_MS)
                return now + lead >= expireAt
            }
            if (p.musicKeyIssuedAt <= 0L) return true
            return now - p.musicKeyIssuedAt >= MUSIC_KEY_TTL_MS - REFRESH_LEAD_MAX_MS
        }

    /**
     * 当前凭据是否处于续签失败的退避窗口内。
     *
     * 续签接口被频繁调用本身也会触发风控，因此在连续失败后按指数退避压制重试。
     *
     * @return 仍在退避窗口内应跳过续签时返回 `true`
     */
    val isRefreshBackingOff: Boolean
        get() {
            val p = _profileFlow.value
            if (p.refreshFailCount <= 0) return false
            val elapsed = System.currentTimeMillis() - p.lastRefreshAttemptAt
            return elapsed < refreshBackoffMs(p.refreshFailCount)
        }

    /**
     * 计算续签失败后的退避时长。
     *
     * @param failCount 连续失败次数（从 1 开始）
     * @return 退避时长（毫秒），上限为 [MAX_REFRESH_BACKOFF_MS]
     */
    fun refreshBackoffMs(failCount: Int): Long {
        if (failCount <= 0) return 0L
        val exponent = (failCount - 1).coerceAtMost(10)
        val backoff = REFRESH_BACKOFF_BASE_MS shl exponent
        return backoff.coerceAtMost(MAX_REFRESH_BACKOFF_MS)
    }

    /**
     * 记录一次凭据续签结果，维护签发时间、过期时间、失败计数与退避时间戳。
     *
     * @param success 续签是否成功
     * @param musicKeyExpireAt 服务端返回的过期时间戳（毫秒）；`0` 表示未知，按 [MUSIC_KEY_TTL_MS] 推算
     */
    fun recordRefreshResult(
        success: Boolean,
        musicKeyExpireAt: Long = 0L,
    ) {
        val p = _profileFlow.value
        val now = System.currentTimeMillis()
        _profileFlow.value =
            if (success) {
                p.copy(
                    musicKeyIssuedAt = now,
                    // 优先采用续签腿写入的服务端过期时间；其次是调用方显式传入的值；最后才按兜底 TTL 推算。
                    musicKeyExpireAt =
                        when {
                            p.musicKeyExpireAt > now -> p.musicKeyExpireAt
                            musicKeyExpireAt > now -> musicKeyExpireAt
                            else -> now + MUSIC_KEY_TTL_MS
                        },
                    lastRefreshAttemptAt = now,
                    refreshFailCount = 0,
                    sessionState = SessionState.Healthy,
                )
            } else {
                p.copy(
                    lastRefreshAttemptAt = now,
                    refreshFailCount = p.refreshFailCount + 1,
                )
            }
    }

    /**
     * 标记当前凭据已过期，使下一次业务请求前必然尝试续签。
     */
    fun invalidateMusicKey() {
        _profileFlow.value = _profileFlow.value.copy(musicKeyIssuedAt = 0L, musicKeyExpireAt = 0L)
    }

    /**
     * 更新会话鉴权健康状态。
     *
     * 状态变化会通过 [profileFlow] 自动落盘，进程被杀后仍能保持"需要重新登录"的判定，
     * 避免重启后拿坏票据反复撞风控。
     *
     * @param state 新的会话状态
     */
    fun setSessionState(state: SessionState) {
        if (_profileFlow.value.sessionState == state) return
        _profileFlow.value = _profileFlow.value.copy(sessionState = state)
    }

    /** 是否已确定需要用户重新登录（凭据被服务端拒绝或票据全缺）。 */
    val isSessionReauthRequired: Boolean
        get() = _profileFlow.value.sessionState == SessionState.ReauthRequired

    /**
     * 记录账号档案（会员信息）刷新成功的时间戳。
     */
    fun recordProfileRefresh() {
        _profileFlow.value = _profileFlow.value.copy(lastProfileRefreshAt = System.currentTimeMillis())
    }

    /**
     * 更新当前登录用户的账号身份信息、凭证与 Cookie 集合。
     *
     * @param uin 用户身份标识 UIN 字符串，为空时将尝试从 Cookie 键中解析
     * @param nick 用户昵称
     * @param musicKey 核心鉴权密钥
     * @param cookies 会话关联的 Cookie 键值字典
     * @param avatarUrl 头像 URL 地址，为空时将根据 QQ 账号自动推导默认地址
     * @param isVip 是否具备有效 VIP 会员资格
     * @param preserveMembership 为 `true` 时保留当前档案中已有的会员/等级/到期字段。
     *   登录流程只持有身份与凭据、不含会员信息，需置为 `true`，否则会把此前刷新得到的
     *   会员信息清空，导致账号页显示"未开通"；账号切换时应保持默认 `false` 以免串号。
     * @param musicKeyExpireAt 服务端返回的凭据过期时间戳（毫秒），`0` 表示未知
     */
    fun update(
        uin: String,
        nick: String,
        musicKey: String,
        cookies: Map<String, String>,
        avatarUrl: String = "",
        isVip: Boolean = false,
        preserveMembership: Boolean = false,
        musicKeyExpireAt: Long = 0L,
    ) {
        // 服务端不同接口返回的 uin 形态不一致：有的带 `o` 前缀（如 `o12345678`），有的是纯数字。
        // 不归一化会导致同一账号被当成两个账号，进而丢失会员信息继承（preserveMembership 失效）。
        val normalizedInputUin = uin.trim().trimStart('o')
        val resolvedUin =
            normalizedInputUin.ifBlank {
                cookies["uin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["qqmusic_uin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["musicid"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["pt2gguin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["openid"]?.takeIf { it.isNotBlank() }
                    ?: ""
            }
        val isWeChatAccount =
            musicKey.startsWith("W_X") ||
                cookies["tmeLoginType"] == "1"

        val resolvedAvatar =
            avatarUrl.ifBlank {
                if (isWeChatAccount) {
                    ""
                } else {
                    val qq =
                        cookies["pt2gguin"].extractNumericQq()
                            ?: cookies["uin"].extractNumericQq()
                            ?: cookies["qqmusic_uin"].extractNumericQq()
                            ?: resolvedUin.extractNumericQq()
                    if (!qq.isNullOrBlank() && qq.length in 5..11) {
                        "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=640"
                    } else {
                        ""
                    }
                }
            }
        val resolvedNick = nick.ifBlank { if (resolvedUin.isNotEmpty()) "用户_$resolvedUin" else "已登录用户" }

        val previous = _profileFlow.value
        // 仅当仍是同一账号时才继承会员信息，避免切换账号时把上一个账号的会员状态带过来。
        // 比较前两边都做 `o` 前缀归一化，兼容旧版本持久化的带前缀 uin。
        val canPreserve =
            preserveMembership &&
                previous.uin.isNotBlank() &&
                previous.uin.trim().trimStart('o') == resolvedUin.trimStart('o')

        profile =
            if (canPreserve) {
                previous.copy(
                    uin = resolvedUin,
                    nick = resolvedNick,
                    musicKey = musicKey,
                    cookies = cookies,
                    avatarUrl = resolvedAvatar,
                    isVip = isVip || previous.isVip,
                    // 凭据已更新但尚未经服务端校验，签发时间留空以强制下一次请求前续签。
                    musicKeyIssuedAt = 0L,
                    musicKeyExpireAt = musicKeyExpireAt,
                    // 新凭据落地的同时清掉"需重登"标记，让下一次续签重新验证真实可用性。
                    sessionState = SessionState.Healthy,
                )
            } else {
                UserProfile(
                    uin = resolvedUin,
                    nick = resolvedNick,
                    musicKey = musicKey,
                    cookies = cookies,
                    avatarUrl = resolvedAvatar,
                    isVip = isVip,
                )
            }
    }

    /**
     * 重置并清空当前登录会话数据与收藏数量缓存。
     */
    fun clear() {
        profile = UserProfile()
        _favoriteSongCount.value = null
    }

    /**
     * 将当前会话中的 Cookie 键值对组装为标准 HTTP `Cookie` 请求头字符串。
     *
     * @return 格式化后的 Cookie 请求头字符串；无任何凭据时返回空字符串
     */
    fun getCookieHeader(): String {
        if (profile.cookies.isEmpty()) {
            val u = profile.uin
            val k = profile.musicKey
            return if (u.isNotBlank() && k.isNotBlank()) {
                "uin=$u; qqmusic_uin=$u; qqmusic_key=$k; qm_keyst=$k"
            } else if (u.isNotBlank()) {
                "uin=$u"
            } else {
                ""
            }
        }
        return profile.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * 将当前用户会话配置导出为 JSON 字符串。
     *
     * @return 序列化后的 JSON 字符串
     */
    fun toJson(): String = jsonHelper.encodeToString(profile)

    /**
     * 从 JSON 字符串解析并还原用户会话状态。
     *
     * @param jsonStr 序列化的用户会话 JSON 文本
     */
    fun fromJson(jsonStr: String) {
        try {
            val loaded = jsonHelper.decodeFromString<UserProfile>(jsonStr)
            if (loaded.uin.isBlank()) {
                val fallbackUin =
                    loaded.cookies["uin"]?.trimStart('o')
                        ?: loaded.cookies["qqmusic_uin"]?.trimStart('o')
                        ?: loaded.cookies["musicid"]
                        ?: loaded.cookies["pt2gguin"]?.trimStart('o')
                        ?: ""
                if (fallbackUin.isNotBlank()) {
                    loaded.uin = fallbackUin
                }
            }
            profile = loaded
        } catch (e: Exception) {
            ApiLogger.w("UserSession", "Failed to restore UserProfile from JSON, resetting session", e)
            clear()
        }
    }
}
