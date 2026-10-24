import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// 默认中继地址从本地 local.properties 读取（该文件不入库），
// 这样"自己的构建默认连自己的节点"，而仓库里不出现任何个人基础设施。
val relayProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val defaultRelayAddress: String = relayProps.getProperty("relay.defaultAddress", "")
val defaultRelayFingerprint: String = relayProps.getProperty("relay.tlsFingerprint", "")

android {
    namespace = "dev.ling.controller"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "DEFAULT_RELAY_ADDRESS", "\"$defaultRelayAddress\"")
        buildConfigField(
            "String",
            "DEFAULT_RELAY_FINGERPRINT",
            "\"$defaultRelayFingerprint\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":protocol"))
    implementation(project(":audio"))
    implementation(project(":design"))
}
