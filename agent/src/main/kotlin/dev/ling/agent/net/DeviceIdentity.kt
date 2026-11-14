package dev.ling.agent.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备身份：稳定设备码 + 机型，随信令上报给中继的管理面板。
 *
 * 设备码来自 `ANDROID_ID`（免权限；同一签名、同一用户下重装不变，恢复出厂会变），
 * 先加盐哈希再取前 16 位——原始 ANDROID_ID 不出设备，也不进日志。
 * 它只是面板上认设备用的标签，真正的授权凭证仍是 6 位配对码。
 */
object DeviceIdentity {

    fun id(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("androidcontrol:$androidId".toByteArray())
        return "d-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** 面板上显示的一行：机型 + 设备码。 */
    fun label(context: Context): String = "${model()} · ${id(context)}"
}
