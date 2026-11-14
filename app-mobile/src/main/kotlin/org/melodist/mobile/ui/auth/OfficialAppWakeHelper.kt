package org.melodist.mobile.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * QQ 音乐官方 App 一键唤醒登录助手。
 *
 * 官方渠道确认页 URL 可由出码响应中的 qrcodeID 直接拼出（探针实测：二维码短链 302 至
 * `https://y.qq.com/m/client/qr_code_login/authorize.html?qrcode_id=<qrcodeID>`），
 * 以 VIEW intent 将其送入官方 App 即等价于扫码后的确认流程；
 * 凭据经既有 MQTT 会话推送回 `LoginApiService` 完成换取。
 */
object OfficialAppWakeHelper {
    private const val OFFICIAL_APP_PACKAGE = "com.tencent.qqmusic"
    private const val AUTHORIZE_URL_PREFIX = "https://y.qq.com/m/client/qr_code_login/authorize.html?qrcode_id="

    /** 官方登录确认页地址（qrcodeID 来自 fetchOfficialAppQrCode 的出码响应） */
    fun buildAuthorizeUrl(qrcodeId: String): String = AUTHORIZE_URL_PREFIX + Uri.encode(qrcodeId)

    /**
     * 官方 App 是否可用于唤起。
     * originOs flavor 自身 applicationId 即 com.tencent.qqmusic（与官方 App 互斥安装），需排除自身。
     */
    fun isOfficialAppAvailable(context: Context): Boolean {
        if (context.packageName == OFFICIAL_APP_PACKAGE) return false
        return try {
            context.packageManager.getLaunchIntentForPackage(OFFICIAL_APP_PACKAGE) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 唤起官方 App 打开确认页；官方 App 未接管该链接时回退系统浏览器打开。
     *
     * @return 是否成功发起唤起（ false 时 UI 应提示改用扫码）
     */
    fun wake(context: Context, qrcodeId: String): Boolean {
        val url = buildAuthorizeUrl(qrcodeId)
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(OFFICIAL_APP_PACKAGE),
            )
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
