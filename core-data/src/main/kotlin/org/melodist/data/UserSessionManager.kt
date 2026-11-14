package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.api.UserSession

object UserSessionManager {
    private const val PREFS_NAME = "qqmusic_user_session"

    /** v1.7.5 之前的文件名，升级覆盖安装时用于一次性迁移。 */
    private const val LEGACY_PREFS_NAME = "melodist_user_session"
    private const val KEY_PROFILE = "user_profile_json"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isObserving = false

    @Volatile
    private var isExplicitLogout = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        PlaybackCredentialsPersistence.init(appContext)
        PrefsMigration.migrate(appContext, PREFS_NAME, LEGACY_PREFS_NAME)
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PROFILE, null)
            if (!jsonStr.isNullOrBlank()) {
                UserSession.fromJson(jsonStr)
            }
        } catch (e: Exception) {
            Log.w("UserSessionManager", "Failed to restore user session", e)
        }

        if (!isObserving) {
            isObserving = true
            scope.launch {
                UserSession.profileFlow.collect { profile ->
                    try {
                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        if (UserSession.isLoggedIn) {
                            isExplicitLogout = false
                            prefs.edit().putString(KEY_PROFILE, UserSession.toJson()).commit()
                        } else if (isExplicitLogout) {
                            prefs.edit().remove(KEY_PROFILE).commit()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("UserSessionManager", "Operation failed", e)
                    }
                }
            }
        }
    }

    /**
     * 将当前会话持久化到磁盘。
     *
     * 已登出（会话被清空）时不做写入：调用方通常先 `UserSession.clear()` 再 `save()`，
     * 若无条件写盘会把空档案覆盖掉磁盘上仍然有效的旧会话，导致下次启动"有 token 无账号信息"。
     * 会话确需清除时应改用 [clear]。
     */
    fun save(context: Context) {
        if (!UserSession.isLoggedIn) {
            Log.i("UserSessionManager", "Skip saving user session: not logged in")
            return
        }
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = UserSession.toJson()
            prefs.edit().putString(KEY_PROFILE, jsonStr).apply()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("UserSessionManager", "Operation failed", e)
        }
    }

    fun clear(context: Context) {
        isExplicitLogout = true
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PROFILE).commit()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("UserSessionManager", "Operation failed", e)
        }
        UserSession.clear()
    }
}
