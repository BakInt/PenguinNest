package org.melodist.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserSessionTest {
    @BeforeEach
    fun setUp() {
        UserSession.clear()
    }

    @AfterEach
    fun tearDown() {
        UserSession.clear()
    }

    @Test
    fun `isLoggedIn returns false initially`() {
        assertFalse(UserSession.isLoggedIn)
        assertEquals("", UserSession.profile.uin)
    }

    @Test
    fun `update populates session and isLoggedIn returns true`() {
        UserSession.update(
            uin = "12345678",
            nick = "测试用户",
            musicKey = "TEST_MUSIC_KEY",
            cookies = mapOf("qm_keyst" to "TEST_MUSIC_KEY", "uin" to "12345678"),
            avatarUrl = "https://example.com/avatar.jpg",
            isVip = true,
        )

        assertTrue(UserSession.isLoggedIn)
        assertEquals("12345678", UserSession.profile.uin)
        assertEquals("测试用户", UserSession.profile.nick)
        assertTrue(UserSession.profile.isVip)

        val cookieHeader = UserSession.getCookieHeader()
        assertTrue(cookieHeader.contains("qm_keyst=TEST_MUSIC_KEY"))
        assertTrue(cookieHeader.contains("uin=12345678"))
    }

    @Test
    fun `update resolves empty uin from cookies`() {
        UserSession.update(
            uin = "",
            nick = "",
            musicKey = "KEY",
            cookies = mapOf("musicid" to "88776655"),
        )

        assertTrue(UserSession.isLoggedIn)
        assertEquals("88776655", UserSession.profile.uin)
    }

    @Test
    fun `effectiveAvatarUrl falls back to uin when cookies have blank values`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                cookies =
                    mapOf(
                        "pt2gguin" to "",
                        "uin" to "o",
                        "qqmusic_uin" to "   ",
                    ),
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl resolves valid numeric qq from cookie with o prefix`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                cookies =
                    mapOf(
                        "pt2gguin" to "o$testUin",
                    ),
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl prefers explicit avatarUrl when present`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                avatarUrl = "https://custom.avatar/pic.jpg",
            )
        assertEquals("https://custom.avatar/pic.jpg", profile.effectiveAvatarUrl)
    }

    @Test
    fun `update does not synthesize qq avatar for wechat accounts`() {
        val testMusicId = "88887777"
        UserSession.update(
            uin = testMusicId,
            nick = "微信用户",
            musicKey = "W_X_TEST_KEY",
            cookies = mapOf("tmeLoginType" to "1"),
            avatarUrl = "",
        )
        assertEquals("", UserSession.profile.avatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl ignores t_user_default placeholder and falls back to qq avatar`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                avatarUrl = "https://y.qq.com/music/common/upload/t_user_default/3284895.png",
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }

    // ---- 凭据时效与退避 ----

    @Test
    fun `isSessionStale is true when logged out`() {
        assertFalse(UserSession.isLoggedIn)
        assertTrue(UserSession.isSessionStale)
    }

    @Test
    fun `isSessionStale is true when issued time is unknown`() {
        UserSession.update(
            uin = "12345678",
            nick = "n",
            musicKey = "KEY",
            cookies = mapOf("qm_keyst" to "KEY"),
        )
        // update() deliberately leaves musicKeyIssuedAt at 0 so the next request renews first.
        assertEquals(0L, UserSession.profile.musicKeyIssuedAt)
        assertTrue(UserSession.isSessionStale)
    }

    @Test
    fun `isSessionStale follows the freshness window`() {
        UserSession.update(
            uin = "12345678",
            nick = "n",
            musicKey = "KEY",
            cookies = mapOf("qm_keyst" to "KEY"),
        )
        UserSession.recordRefreshResult(success = true)
        assertFalse(UserSession.isSessionStale)

        // Move the issuance time just outside the TTL window (expireAt cleared to exercise the fallback).
        UserSession.profile =
            UserSession.profile.copy(
                musicKeyIssuedAt = System.currentTimeMillis() - UserSession.MUSIC_KEY_TTL_MS - 1,
                musicKeyExpireAt = 0L,
            )
        assertTrue(UserSession.isSessionStale)
    }

    @Test
    fun `recordRefreshResult(success) resets failure count and stamps issuance`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        UserSession.recordRefreshResult(success = false)
        UserSession.recordRefreshResult(success = false)
        assertEquals(2, UserSession.profile.refreshFailCount)

        UserSession.recordRefreshResult(success = true)
        assertEquals(0, UserSession.profile.refreshFailCount)
        assertTrue(UserSession.profile.musicKeyIssuedAt > 0L)
    }

    @Test
    fun `isRefreshBackingOff engages only after a failure and expires`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        assertFalse(UserSession.isRefreshBackingOff)

        UserSession.recordRefreshResult(success = false)
        assertTrue(UserSession.isRefreshBackingOff)

        // Backdated attempt falls outside the first backoff window.
        UserSession.profile =
            UserSession.profile.copy(
                lastRefreshAttemptAt = System.currentTimeMillis() - UserSession.refreshBackoffMs(1) - 1,
            )
        assertFalse(UserSession.isRefreshBackingOff)
    }

    @Test
    fun `refreshBackoffMs grows exponentially and is capped at 10 minutes`() {
        assertEquals(0L, UserSession.refreshBackoffMs(0))
        assertEquals(30_000L, UserSession.refreshBackoffMs(1))
        assertEquals(60_000L, UserSession.refreshBackoffMs(2))
        assertEquals(120_000L, UserSession.refreshBackoffMs(3))
        // 30s << n grows fast; anything past the 10-minute cap must clamp.
        assertEquals(10L * 60L * 1000L, UserSession.refreshBackoffMs(20))
    }

    @Test
    fun `invalidateMusicKey forces a renewal on the next stale check`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        UserSession.recordRefreshResult(success = true)
        assertFalse(UserSession.isSessionStale)

        UserSession.invalidateMusicKey()
        assertTrue(UserSession.isSessionStale)
    }

    // ---- 会员信息保留（登录/续签路径不得清空会员字段） ----

    @Test
    fun `update with preserveMembership keeps membership fields for the same account`() {
        UserSession.update(uin = "12345678", nick = "n", musicKey = "K1", cookies = mapOf("qm_keyst" to "K1"))
        UserSession.profile =
            UserSession.profile.copy(
                isVip = true,
                isSvip = true,
                isHugeVip = true,
                vipLevel = 7,
                musicScore = 900,
                vipExpireAt = "2030-01-01",
            )

        UserSession.update(
            uin = "12345678",
            nick = "n",
            musicKey = "K2",
            cookies = mapOf("qm_keyst" to "K2"),
            preserveMembership = true,
        )

        val p = UserSession.profile
        assertEquals("K2", p.musicKey)
        assertTrue(p.isVip)
        assertTrue(p.isSvip)
        assertTrue(p.isHugeVip)
        assertEquals(7, p.vipLevel)
        assertEquals(900, p.musicScore)
        assertEquals("2030-01-01", p.vipExpireAt)
    }

    @Test
    fun `update without preserveMembership resets membership fields`() {
        UserSession.update(uin = "12345678", nick = "n", musicKey = "K1", cookies = mapOf("qm_keyst" to "K1"))
        UserSession.profile = UserSession.profile.copy(isVip = true, isSvip = true, vipLevel = 7)

        UserSession.update(
            uin = "12345678",
            nick = "n",
            musicKey = "K2",
            cookies = mapOf("qm_keyst" to "K2"),
        )

        val p = UserSession.profile
        assertFalse(p.isVip)
        assertFalse(p.isSvip)
        assertEquals(0, p.vipLevel)
    }

    @Test
    fun `update with preserveMembership does not leak membership across accounts`() {
        UserSession.update(uin = "11111111", nick = "a", musicKey = "K1", cookies = mapOf("qm_keyst" to "K1"))
        UserSession.profile = UserSession.profile.copy(isSvip = true, vipLevel = 9)

        UserSession.update(
            uin = "22222222",
            nick = "b",
            musicKey = "K2",
            cookies = mapOf("qm_keyst" to "K2"),
            preserveMembership = true,
        )

        val p = UserSession.profile
        assertEquals("22222222", p.uin)
        assertFalse(p.isSvip)
        assertEquals(0, p.vipLevel)
    }

    // ---- 向后兼容：旧 JSON 缺少新字段时必须仍能解析 ----

    @Test
    fun `fromJson tolerates legacy payloads without freshness fields`() {
        val legacy =
            """
            {
              "uin": "12345678",
              "nick": "旧用户",
              "musicKey": "LEGACY_KEY",
              "cookies": { "qm_keyst": "LEGACY_KEY" },
              "isVip": true
            }
            """.trimIndent()

        UserSession.fromJson(legacy)

        assertTrue(UserSession.isLoggedIn)
        assertEquals("12345678", UserSession.profile.uin)
        assertTrue(UserSession.profile.isVip)
        assertEquals(0L, UserSession.profile.musicKeyIssuedAt)
        assertEquals(0, UserSession.profile.refreshFailCount)
        // Legacy sessions have no issuance time, so they must renew before the next request.
        assertTrue(UserSession.isSessionStale)
    }
}
