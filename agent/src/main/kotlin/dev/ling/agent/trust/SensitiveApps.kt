package dev.ling.agent.trust

/**
 * 支付 / 银行类应用。命中后长辈端会把荧光提示切成**红色**并写明应用名，
 * 让双方都清楚"现在停在了会动钱的地方"。
 *
 * 只做包名匹配，不做页面文本分析：
 * - 读屏（AccessibilityService）代价大、范围广，而且本身侵犯隐私；
 * - 包名匹配足够覆盖"打开了银行/支付 App"这个主要风险场景，
 *   微信/支付宝内部的收付款页无法在此层区分，交给双方的自觉与红色提示。
 *
 * 匹配用前缀，兼容各家的渠道包（如 `com.icbc` 的不同版本）。
 */
object SensitiveApps {

    private val PREFIXES = listOf(
        // 支付 / 钱包
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.unionpay",                  // 云闪付 / 银联
        "com.jd.jrapp",                  // 京东金融
        "com.mipay.wallet",              // 小米钱包
        "com.miui.wallet",               // 小米钱包（旧版）
        "com.huawei.wallet",             // 华为钱包
        "com.paypal",                    // PayPal

        // 银行
        "com.icbc",                      // 工商银行
        "com.chinamworld.main",          // 建设银行
        "com.chinamworld.bocmbci",       // 中国银行
        "com.android.bankabc",           // 农业银行
        "com.bankcomm",                  // 交通银行
        "cmb.pb",                        // 招商银行
        "com.yitong.mbank.psbc",         // 邮储银行
        "com.spdbccc",                   // 浦发银行
        "com.ecitic.bank",               // 中信银行
        "com.cmbc.cc.mbank",             // 民生银行
        "com.cib.xyk",                   // 兴业银行
        "com.pingan.paces.ccms",         // 平安口袋银行
        "com.pingan.bank",
        "com.bankofbeijing",             // 北京银行
        "com.shanghai.bank",             // 上海银行
        "com.csii.weixin",               // 各地农商行常见包名
    )

    fun matches(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return PREFIXES.any { packageName.startsWith(it, ignoreCase = true) }
    }
}
