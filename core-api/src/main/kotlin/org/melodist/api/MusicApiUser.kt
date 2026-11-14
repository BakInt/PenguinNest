package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 用户画像与 VIP 权益同步扩展
 */

/**
 * 账号档案刷新的互斥锁。
 *
 * 登录成功会同时触发登录对话框与主导航的刷新；两者并发时都是"读当前档案 → 改字段 → 整体写回"，
 * 后完成的一方能拿到的可能是旧副本，从而覆盖掉先完成方写入的会员信息。加锁使刷新串行化。
 */
private val profileRefreshMutex = Mutex()

/** 相邻两次档案刷新的最小间隔，用于合并登录后短时间内重复触发的刷新。 */
private const val PROFILE_REFRESH_DEDUP_MS = 10_000L

@Volatile
private var lastProfileRefreshInvokeAt = 0L

suspend fun MusicApiService.refreshCurrentUserProfile(force: Boolean = false): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        if (!force) {
            val sinceLast = System.currentTimeMillis() - lastProfileRefreshInvokeAt
            val cachedAt = UserSession.profile.lastProfileRefreshAt
            if (cachedAt > 0L && sinceLast < PROFILE_REFRESH_DEDUP_MS) {
                ApiLogger.i("MusicApiUser", "Skip duplicate profile refresh (${sinceLast}ms since last)")
                return@withContext true
            }
        }
        profileRefreshMutex.withLock {
            lastProfileRefreshInvokeAt = System.currentTimeMillis()
            refreshCurrentUserProfileInternal()
        }
    }

private suspend fun MusicApiService.refreshCurrentUserProfileInternal(): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        ensureMusicKeySafe()

        var uin = UserSession.profile.uin
        var authst = UserSession.profile.musicKey
        var loginType = UserSession.loginType

        fun buildProfilePayload(
            u: String,
            a: String,
            lt: Int,
        ): String =
            """
            {
              "comm": {
                "ct": 11,
                "cv": 14090008,
                "v": 14090008,
                "chid": "10003505",
                "tmeAppID": "qqmusic",
                "tmeLoginType": $lt,
                "qq": "$u",
                "authst": "$a"
              },
              "profile": {
                "module": "music.UnifiedHomepage.UnifiedHomepageSrv",
                "method": "GetHomepageHeader",
                "param": { "uin": "$u", "IsQueryTabDetail": 1 }
              },
              "vip": {
                "module": "VipLogin.VipLoginInter",
                "method": "vip_login_base",
                "param": {}
              }
            }
            """.trimIndent()

        try {
            var respJson = postGateway(buildProfilePayload(uin, authst, loginType))
            var root = Json.parseToJsonElement(respJson).jsonObject

            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val profileCode =
                root["profile"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull ?: 0
            val vipCode =
                root["vip"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull ?: 0

            if (rootCode == 2000 || profileCode == 2000 || vipCode == 2000) {
                ApiLogger.i("MusicApiUser", "Detected expired credentials (code 2000), attempting auto refresh...")
                if (LoginApiService.shared.forceRefreshMusicKey()) {
                    uin = UserSession.profile.uin
                    authst = UserSession.profile.musicKey
                    loginType = UserSession.loginType
                    respJson = postGateway(buildProfilePayload(uin, authst, loginType))
                    root = Json.parseToJsonElement(respJson).jsonObject
                }
            }

            // 续签后（或首次请求本就返回鉴权错误时）必须复检，否则会把失败响应当成"数据无变化"，
            // 让调用方误判为刷新成功、界面继续停留在"未开通"。
            val finalAuthFailed =
                (root["code"]?.jsonPrimitive?.intOrNull ?: 0) == 2000 ||
                    (root["profile"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0) == 2000 ||
                    (root["vip"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0) == 2000
            if (finalAuthFailed) {
                ApiLogger.w("MusicApiUser", "Credentials still invalid after renewal attempt; session requires re-login")
                UserSession.invalidateMusicKey()
                // 区分"鉴权失败"与"会员到期"：此前只置凭据失效，界面会把 0 值当成"未开通会员"，
                // 用户看到的是"会员没了"而不是"登录过期了"，排查成本极高。
                UserSession.setSessionState(SessionState.ReauthRequired)
                return@withContext false
            }

            var changed = false
            val newProfile = UserSession.profile.copy()

            // 用户基础信息
            val baseInfo =
                root["profile"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("Info")
                    ?.jsonObject
                    ?.get("BaseInfo")
                    ?.jsonObject
            if (baseInfo != null) {
                val name = baseInfo["Name"]?.jsonPrimitive?.contentOrNull
                val encUin = baseInfo["EncryptedUin"]?.jsonPrimitive?.contentOrNull
                val bigAvatar = baseInfo["BigAvatar"]?.jsonPrimitive?.contentOrNull
                val rawAvatar = baseInfo["Avatar"]?.jsonPrimitive?.contentOrNull

                val normalizedAvatar =
                    normalizeHighResAvatar(
                        avatarUrl = bigAvatar ?: rawAvatar.orEmpty(),
                        uin = uin,
                        isQqAccount = loginType != 1,
                    )

                if (!name.isNullOrBlank() && name != newProfile.nick) {
                    newProfile.nick = name
                    changed = true
                }
                if (!encUin.isNullOrBlank()) {
                    newProfile.encryptedUin = encUin
                    changed = true
                }
                if (normalizedAvatar.isNotBlank() && normalizedAvatar != newProfile.avatarUrl) {
                    newProfile.avatarUrl = normalizedAvatar
                    changed = true
                }
            }

            // VIP 与绿钻信息
            val vipData = root["vip"]?.jsonObject?.get("data")?.jsonObject
            if (vipData != null) {
                val svipRoot = vipData["svip"]?.jsonPrimitive?.intOrNull ?: 0
                val identity = vipData["identity"]?.jsonObject
                // 判据取"identity 是否完整"而不是"会员标志是否为 0"：后者无法区分
                // "会员真的到期"与"鉴权降级后服务端返回空壳 identity"。空壳一旦被当成真实状态，
                // 就会把已有会员信息覆盖成 false，界面显示"未开通会员"。
                val identitySignals =
                    listOf(
                        "vip", "HugeVip", "huge_vip", "svip", "super_vip", "iSuperVip", "svip_flag",
                        "eight", "twelve", "level", "overdate", "LMEnd", "eightEnd", "twelveEnd",
                        "yearflag", "yearffb", "CPLoverFlag", "GroupVipFlag",
                    )
                val identityComplete = identity != null && identity.keys.any { it in identitySignals }
                if (identity != null && identityComplete) {
                    val vip = identity["vip"]?.jsonPrimitive?.intOrNull ?: 0
                    val hugeVip =
                        identity["HugeVip"]?.jsonPrimitive?.intOrNull
                            ?: identity["huge_vip"]?.jsonPrimitive?.intOrNull
                            ?: 0
                    val svipId = identity["svip"]?.jsonPrimitive?.intOrNull ?: 0
                    val eight = identity["eight"]?.jsonPrimitive?.intOrNull ?: 0
                    val twelve = identity["twelve"]?.jsonPrimitive?.intOrNull ?: 0
                    val superVip =
                        identity["super_vip"]?.jsonPrimitive?.intOrNull
                            ?: identity["iSuperVip"]?.jsonPrimitive?.intOrNull
                            ?: identity["svip_flag"]?.jsonPrimitive?.intOrNull
                            ?: vipData["super_vip"]?.jsonPrimitive?.intOrNull
                            ?: 0

                    val hugeEnd = identity["HugeVipEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val overdate = identity["overdate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val lmEnd = identity["LMEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val eightEnd = identity["eightEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val twelveEnd = identity["twelveEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val isSvip =
                        hugeVip > 0 ||
                            (hugeEnd.isNotBlank() && hugeEnd != "0") ||
                            twelve > 0 ||
                            (twelveEnd.isNotBlank() && twelveEnd != "0") ||
                            superVip > 0
                    val isHuge =
                        eight > 0 ||
                            (eightEnd.isNotBlank() && eightEnd != "0") ||
                            svipRoot > 0 ||
                            svipId > 0
                    val isGreen = vip > 0 || (lmEnd.isNotBlank() && lmEnd != "0")
                    val isYear =
                        (identity["yearflag"]?.jsonPrimitive?.intOrNull ?: 0) > 0 ||
                            (identity["yearffb"]?.jsonPrimitive?.intOrNull ?: 0) > 0

                    val level = identity["level"]?.jsonPrimitive?.intOrNull ?: 0
                    val nextLevel = identity["nextlevel"]?.jsonPrimitive?.intOrNull ?: if (level > 0) level + 1 else 0
                    val upgradePct = identity["upgradepct"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val upgradeDays = identity["upgradeday"]?.jsonPrimitive?.intOrNull ?: 0

                    val svipEnd = if (isSvip) hugeEnd.ifBlank { twelveEnd.ifBlank { if (!isHuge) overdate else "" } } else ""
                    val hugeExpire = if (isHuge) eightEnd.ifBlank { overdate } else ""
                    val greenEnd = if (isGreen && !isHuge && !isSvip) lmEnd.ifBlank { overdate } else ""

                    val cpLoverFlag = identity["CPLoverFlag"]?.jsonPrimitive?.intOrNull ?: 0
                    val cpLoverEnd = identity["CPLoverEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val groupVipFlag = identity["GroupVipFlag"]?.jsonPrimitive?.intOrNull ?: 0
                    val groupVipEnd = identity["GroupVipEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val isCpLover = cpLoverFlag > 0
                    val isGroupVip = groupVipFlag > 0

                    newProfile.isVip = isGreen || isHuge || isSvip || isCpLover || isGroupVip
                    newProfile.isHugeVip = isHuge
                    newProfile.isSvip = isSvip
                    newProfile.isYearVip = isYear
                    newProfile.isCpLover = isCpLover
                    newProfile.cpLoverExpireAt = cpLoverEnd
                    newProfile.isGroupVip = isGroupVip
                    newProfile.groupVipExpireAt = groupVipEnd
                    newProfile.vipLevel = level
                    newProfile.nextVipLevel = nextLevel
                    newProfile.vipUpgradePercent = upgradePct
                    newProfile.vipUpgradeDays = upgradeDays
                    newProfile.hugeVipExpireAt = hugeExpire
                    newProfile.svipExpireAt = svipEnd
                    newProfile.greenVipExpireAt = greenEnd
                    newProfile.vipExpireAt =
                        when {
                            isSvip && svipEnd.isNotBlank() -> svipEnd
                            isHuge && hugeExpire.isNotBlank() -> hugeExpire
                            isGreen && greenEnd.isNotBlank() -> greenEnd
                            else -> cpLoverEnd.ifBlank { groupVipEnd.ifBlank { overdate } }
                        }
                    changed = true
                } else if (identity != null) {
                    // identity 存在但没有任何会员字段：视为鉴权降级/服务端降级返回，
                    // 保留上一次成功快照，稍后自动重试，不把会员清零。
                    ApiLogger.w("MusicApiUser", "VIP identity payload incomplete; keeping last known membership snapshot")
                    UserSession.setSessionState(SessionState.TransientFailure)
                }

                val userInfo = vipData["userinfo"]?.jsonObject
                if (userInfo != null) {
                    newProfile.musicLevel = userInfo["music_level"]?.jsonPrimitive?.intOrNull ?: 0
                    newProfile.musicScore = userInfo["score"]?.jsonPrimitive?.intOrNull ?: 0
                    changed = true
                }
            }

            if (changed) {
                UserSession.profile = newProfile
            }
            // 仅当确实解析到账号/会员数据时才算刷新成功；否则界面会拿不到会员信息，
            // 需要由调用方重试或提示重新登录。
            val hasAccountData = root["profile"]?.jsonObject?.get("data") != null || root["vip"]?.jsonObject?.get("data") != null
            if (hasAccountData) {
                UserSession.recordProfileRefresh()
            } else {
                ApiLogger.w("MusicApiUser", "Profile refresh returned no account data (changed=$changed)")
            }
            hasAccountData
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiUser", "refreshCurrentUserProfile failed", e)
            false
        }
    }

/**
 * 将 QQ 或微信头像 URL 转换为对应平台的高清规格尺寸。
 *
 * @param avatarUrl 原始头像 URL 地址
 * @param uin 账号 UIN 字符串，在头像为空时用于构造默认高清头像地址
 * @param isQqAccount 是否为 QQ 账号体系
 * @return 归一化后的高清头像 URL；无法解析且无有效 UIN 时返回空字符串
 */
fun normalizeHighResAvatar(
    avatarUrl: String,
    uin: String = "",
    isQqAccount: Boolean = true,
): String {
    var url = avatarUrl.trim().replace("http://", "https://")
    if (url.contains("t_user_default")) {
        url = ""
    }
    if (url.contains("qlogo.cn")) {
        // QQ 头像规格提升到 640x640 高清
        url = url.replace(Regex("&s=\\d+"), "&s=640")
        url = url.replace(Regex("/(40|100|140)$"), "/640")
    } else if (url.contains("thirdwx.qlogo.cn") || url.contains("/mmopen/")) {
        // 微信头像规格提升到 /0 最高清原图
        url = url.replace(Regex("/(132|96|64|46)$"), "/0")
    }

    if (isQqAccount && url.isBlank() && uin.isNotBlank() && uin.all { it.isDigit() } && uin.length in 5..11) {
        url = "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640"
    }
    return url
}
