package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * SharedPreferences 文件名迁移（品牌重命名 `melodist_*` → `qqmusic_*`）。
 *
 * v1.7.5 起三个偏好文件名被改名，但升级覆盖安装时旧文件仍留在磁盘上、新代码只读新名字，
 * 结果就是"登录态消失、所有设置回到默认、播放凭据失效"。
 *
 * [migrate] 的语义是"新文件为空才搬"：
 * - 新文件已有数据 → 什么都不做（用户已在新版本里用过，旧数据视为过期）；
 * - 新文件为空且旧文件有数据 → 逐键原样复制到新文件，`commit()` 成功后清空旧文件。
 *
 * 复制而不是重命名文件，是为了让 [SharedPreferences] 缓存与新文件名保持一致；
 * 旧文件保留（清空而非删除），万一迁移逻辑本身有问题，用户数据仍可人工找回。
 */
internal object PrefsMigration {
    private const val TAG = "PrefsMigration"

    fun migrate(
        context: Context,
        newName: String,
        legacyName: String,
    ) {
        try {
            val app = context.applicationContext
            val newPrefs = app.getSharedPreferences(newName, Context.MODE_PRIVATE)
            if (newPrefs.all.isNotEmpty()) return
            val legacyPrefs = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            val legacyAll = legacyPrefs.all
            if (legacyAll.isEmpty()) return

            val editor = newPrefs.edit()
            var copied = 0
            legacyAll.forEach { (key, value) ->
                if (putValue(editor, key, value)) copied++
            }
            if (editor.commit()) {
                legacyPrefs.edit().clear().commit()
                Log.i(TAG, "Migrated $legacyName -> $newName ($copied/${legacyAll.size} keys)")
            } else {
                Log.w(TAG, "Commit failed, keeping legacy prefs: $legacyName -> $newName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate $legacyName -> $newName", e)
        }
    }

    private fun putValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
    ): Boolean {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> {
                Log.w(TAG, "Skip unsupported preference type: $key (${value?.javaClass?.name})")
                return false
            }
        }
        return true
    }
}
