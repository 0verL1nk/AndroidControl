package dev.ling.agent.trust

import android.content.Context
import java.security.SecureRandom

/**
 * 配对材料的持久化存储。
 *
 * 口令**故意持久化**而不是每次会话随机：产品要求是"长辈端一打开，控制端就能连"，
 * 若口令每次都变，控制端就必须有人手工输入，那长辈端就不是零操作了。
 * 安全性由隧道（WireGuard 加密 + 固定内网 IP）与设备绑定共同保证。
 *
 * 口令只出现在长辈端的"高级信息"面板里，主界面永远不显示。
 */
class PairingStore(context: Context) {

    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /** 本机与控制端约定的 6 位口令，首次调用时生成。 */
    fun token(): String {
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val generated = secureSixDigits()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /** 部署者手动重设口令（怀疑泄露时用）。 */
    fun resetToken(): String {
        val generated = secureSixDigits()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /** 已绑定的控制端（M1 仅记录，白名单鉴权在后续实现）。 */
    fun addBinding(deviceId: String, displayName: String) {
        prefs.edit().putString("binding.$deviceId", displayName).apply()
    }

    fun bindings(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith("binding.") }
            .mapKeys { it.key.removePrefix("binding.") }
            .mapValues { it.value as? String ?: "" }

    private fun secureSixDigits(): String =
        (100000 + SecureRandom().nextInt(900000)).toString()

    private companion object {
        const val KEY_TOKEN = "token"
    }
}
