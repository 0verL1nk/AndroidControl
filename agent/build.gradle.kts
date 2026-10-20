plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// 被协助端逻辑（以库的形式并入 :app）。
// AGP 9 起 Kotlin 支持内置，不再需要 kotlin-android 插件。

android {
    namespace = "dev.ling.agent"
    // androidx.core 1.19.1 要求 compileSdk ≥ 37
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // UserService 需要跨进程接口
    buildFeatures {
        aidl = true
        compose = true
        // AGP 8 起 BuildConfig 默认不生成，探针里用 BuildConfig.DEBUG
        buildConfig = true
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku：以 shell 身份启动采集引擎（不 root）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(project(":protocol"))
    implementation(project(":audio"))
    implementation(project(":design"))
}
