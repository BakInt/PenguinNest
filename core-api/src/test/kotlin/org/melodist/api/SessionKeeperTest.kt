package org.melodist.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SessionKeeperTest {
    @BeforeEach
    fun setUp() {
        UserSession.clear()
    }

    @AfterEach
    fun tearDown() {
        UserSession.clear()
    }

    // ---- parseSessionExpireAt：把服务端各种"过期时间"写法统一成毫秒时间戳 ----

    @Test
    fun `parseSessionExpireAt rejects empty and non-positive values`() {
        assertNull(parseSessionExpireAt(null))
        assertNull(parseSessionExpireAt(""))
        assertNull(parseSessionExpireAt("0"))
        assertNull(parseSessionExpireAt("-1"))
    }

    @Test
    fun `parseSessionExpireAt treats epoch seconds as absolute time`() {
        // 1770000000 秒 ≈ 2026-02-02，属于"绝对时间"量级。
        assertEquals(1_770_000_000_000L, parseSessionExpireAt("1770000000"))
    }

    @Test
    fun `parseSessionExpireAt keeps millisecond timestamps as-is`() {
        assertEquals(1_770_000_000_000L, parseSessionExpireAt("1770000000000"))
    }

    @Test
    fun `parseSessionExpireAt treats small numbers as relative seconds`() {
        val now = 1_700_000_000_000L
        // expired_in=7776000（90 天）是相对时长，必须换算成 now + 90 天，
        // 否则会被当成 1970 年的时间戳而触发无休止续签。
        assertEquals(now + 7_776_000_000L, parseSessionExpireAt("7776000", now))
    }

    @Test
    fun `parseSessionExpireAt delegates date strings to the shared parser`() {
        val parsed = parseSessionExpireAt("2030-01-01 00:00:00")
        assertNotNull(parsed)
        assertTrue(parsed!! > System.currentTimeMillis())
    }

    // ---- musicKeyExpireAt 驱动的失效判定 ----

    @Test
    fun `recordRefreshResult falls back to the local TTL when the server sends no expiry`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        val before = System.currentTimeMillis()
        UserSession.recordRefreshResult(success = true)

        val expireAt = UserSession.profile.musicKeyExpireAt
        assertTrue(expireAt >= before + UserSession.MUSIC_KEY_TTL_MS)
        assertFalse(UserSession.isSessionStale)
    }

    @Test
    fun `recordRefreshResult keeps a server expiry that is further out than the local TTL`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        val serverExpireAt = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        UserSession.recordRefreshResult(success = true, musicKeyExpireAt = serverExpireAt)

        assertEquals(serverExpireAt, UserSession.profile.musicKeyExpireAt)
        assertFalse(UserSession.isSessionStale)
    }

    @Test
    fun `isSessionStale triggers before the key actually expires`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        val now = System.currentTimeMillis()
        // 服务端给了 60 分钟寿命：提前量 = 半衰期 30 分钟（未超过 45 分钟上限）。
        // 剩余 20 分钟 → 该续签了。
        UserSession.profile =
            UserSession.profile.copy(
                musicKeyIssuedAt = now - 40L * 60 * 1000,
                musicKeyExpireAt = now + 20L * 60 * 1000,
            )
        assertTrue(UserSession.isSessionStale)

        // 剩余 40 分钟 → 还不到提前量，不应每次请求都触发续签。
        UserSession.profile =
            UserSession.profile.copy(
                musicKeyIssuedAt = now - 20L * 60 * 1000,
                musicKeyExpireAt = now + 40L * 60 * 1000,
            )
        assertFalse(UserSession.isSessionStale)
    }

    @Test
    fun `invalidateMusicKey clears both issuance and expiry stamps`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        UserSession.recordRefreshResult(success = true)

        UserSession.invalidateMusicKey()

        assertEquals(0L, UserSession.profile.musicKeyIssuedAt)
        assertEquals(0L, UserSession.profile.musicKeyExpireAt)
        assertTrue(UserSession.isSessionStale)
    }

    // ---- 会话状态机 ----

    @Test
    fun `session state defaults to healthy and can be moved to reauth required`() {
        UserSession.update(uin = "1", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        assertEquals(SessionState.Healthy, UserSession.profile.sessionState)
        assertFalse(UserSession.isSessionReauthRequired)

        UserSession.setSessionState(SessionState.ReauthRequired)
        assertTrue(UserSession.isSessionReauthRequired)

        UserSession.recordRefreshResult(success = true)
        assertEquals(SessionState.Healthy, UserSession.profile.sessionState)
    }

    @Test
    fun `update clears a stale reauth flag when the same account logs in again`() {
        UserSession.update(uin = "12345678", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        UserSession.setSessionState(SessionState.ReauthRequired)

        UserSession.update(
            uin = "12345678",
            nick = "n",
            musicKey = "K2",
            cookies = mapOf("qm_keyst" to "K2"),
            preserveMembership = true,
        )

        assertEquals(SessionState.Healthy, UserSession.profile.sessionState)
    }

    @Test
    fun `update normalises the o-prefixed uin so preserved membership still applies`() {
        UserSession.update(uin = "12345678", nick = "n", musicKey = "K", cookies = mapOf("qm_keyst" to "K"))
        UserSession.profile = UserSession.profile.copy(isVip = true, isSvip = true)

        // QQ 扫码/续签路径会带回 "o12345678" 形式的 uin，必须归一化后才能命中同一账号。
        UserSession.update(
            uin = "o12345678",
            nick = "n",
            musicKey = "K2",
            cookies = mapOf("qm_keyst" to "K2"),
            preserveMembership = true,
        )

        assertEquals("12345678", UserSession.profile.uin)
        assertTrue(UserSession.profile.isVip)
        assertTrue(UserSession.profile.isSvip)
    }
}
