package dev.ling.controller.store

import android.content.Context
import dev.ling.controller.core.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已绑定的长辈设备列表（支持多个长辈）。
 *
 * 保存了地址与口令，所以"打开 App → 点一台设备"就能连，不需要重复输入。
 * 口令是敏感信息，只落在应用私有目录，不上传、不导出。
 */
class DeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<DeviceInfo> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DeviceInfo(
                    host = o.getString("host"),
                    controlPort = o.getInt("controlPort"),
                    videoPort = o.getInt("videoPort"),
                    name = o.getString("name"),
                    model = o.optString("model"),
                    brand = o.optString("brand"),
                    androidVersion = o.optString("android"),
                    token = o.optString("token"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                    relayHost = o.optString("relayHost").takeIf { it.isNotBlank() },
                    relayPort = o.optInt("relayPort", 27180),
                    relayFingerprint =
                        o.optString("relayFingerprint").takeIf { it.isNotBlank() }
                            ?: relayFingerprint(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 以地址为唯一键：同一台设备重复配对时覆盖，不产生重复条目。 */
    fun upsert(device: DeviceInfo) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.host == device.host }
        if (index >= 0) list[index] = device else list.add(device)
        save(list)
    }

    fun remove(host: String) {
        save(all().filterNot { it.host == host })
    }

    fun touch(host: String, at: Long = System.currentTimeMillis()) {
        val list = all().map { if (it.host == host) it.copy(lastUsedAt = at) else it }
        save(list)
    }

    /** 中继节点地址（"host:port"）。填了就跨网络直连，不用再输地址。 */
    fun relayAddress(): String? =
        prefs.getString(KEY_RELAY, null)?.takeIf { it.isNotBlank() }
            ?: dev.ling.controller.BuildConfig.DEFAULT_RELAY_ADDRESS.takeIf { it.isNotBlank() }

    fun setRelayAddress(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RELAY) else putString(KEY_RELAY, value.trim())
        }.apply()
        // 中继是全局设置：已保存的设备也要跟着换，否则改完地址旧设备还连老节点
        val trimmed = value?.trim().orEmpty()
        val host = trimmed.substringBefore(':').takeIf { it.isNotBlank() } ?: return
        val port = trimmed.substringAfter(':', "").toIntOrNull()
        val list = all()
        if (list.isNotEmpty()) {
            save(list.map { it.copy(relayHost = host, relayPort = port ?: it.relayPort) })
        }
    }

    /** 中继证书指纹（十六进制）；配置了就走 TLS。留空 = 明文。 */
    fun relayFingerprint(): String? =
        prefs.getString(KEY_RELAY_FINGERPRINT, null)?.takeIf { it.isNotBlank() }
            ?: dev.ling.controller.BuildConfig.DEFAULT_RELAY_FINGERPRINT.takeIf {
                it.isNotBlank()
            }

    fun setRelayFingerprint(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove(KEY_RELAY_FINGERPRINT)
            } else {
                putString(KEY_RELAY_FINGERPRINT, value.trim())
            }
        }.apply()
        val list = all()
        if (list.isNotEmpty()) save(list.map { it.copy(relayFingerprint = value?.trim()) })
    }

    private fun save(list: List<DeviceInfo>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("host", d.host)
                    put("controlPort", d.controlPort)
                    put("videoPort", d.videoPort)
                    put("name", d.name)
                    put("model", d.model)
                    put("brand", d.brand)
                    put("android", d.androidVersion)
                    put("token", d.token)
                    put("relayHost", d.relayHost ?: "")
                    put("relayPort", d.relayPort)
                    put("relayFingerprint", d.relayFingerprint ?: "")
                    put("lastUsedAt", d.lastUsedAt)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
        const val KEY_RELAY = "relay_address"
        const val KEY_RELAY_FINGERPRINT = "relay_fingerprint"
    }
}
