package dev.ling.controller.core

/**
 * 一台可被协助的设备（一位长辈的手机）。
 *
 * 多个长辈 = 多个 DeviceInfo；控制端把它们列出来，用名字区分。
 */
data class DeviceInfo(
    val host: String,
    val controlPort: Int,
    val videoPort: Int,
    val name: String,
    val model: String = "",
    val brand: String = "",
    val androidVersion: String = "",
    /** 首次配对时输入的口令，保存后即可一键连接。 */
    val token: String = "",
    /** 中继节点；为空表示局域网直连。 */
    val relayHost: String? = null,
    val relayPort: Int = 27180,
    /** 中继证书的 SHA-256 指纹；非空表示中继走 TLS（自签证书固定）。 */
    val relayFingerprint: String? = null,
    val lastUsedAt: Long = 0L,
) {
    val address: String get() = "$host:$controlPort"

    /** 界面上用来分辨"是哪一台"的一行小字。 */
    val subtitle: String
        get() = listOf(brand, model, androidVersion.takeIf { it.isNotBlank() }?.let { "Android $it" })
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")
}
