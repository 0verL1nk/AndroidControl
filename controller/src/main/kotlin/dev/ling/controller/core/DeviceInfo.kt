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
    val lastUsedAt: Long = 0L,
) {
    val address: String get() = "$host:$controlPort"

    /** 界面上用来分辨"是哪一台"的一行小字。 */
    val subtitle: String
        get() = listOf(brand, model, androidVersion.takeIf { it.isNotBlank() }?.let { "Android $it" })
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")
}
