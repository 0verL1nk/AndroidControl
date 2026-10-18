plugins {
    alias(libs.plugins.android.application)
}

// AGP 9 起 Kotlin 支持内置（android.builtInKotlin 默认 true），
// 不再需要也不兼容 org.jetbrains.kotlin.android 插件。
// jvmTarget 由 compileOptions 推导，KGP 版本由 AGP 固定（当前 2.2.10）。

android {
    namespace = "dev.ling.agent"
    // androidx.core 1.19.1 要求 compileSdk ≥ 37
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ling.agent"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 引擎 jar 不做压缩：assets.openFd 要求未压缩，且避免每次释放时解压
    androidResources {
        noCompress += listOf("jar")
    }

    // UserService 需要跨进程接口
    buildFeatures {
        aidl = true
        // AGP 8 起 BuildConfig 默认不生成，这里显式打开（探针里用 BuildConfig.DEBUG）
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku：以 shell 身份启动采集引擎（不 root）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(project(":protocol"))
}
