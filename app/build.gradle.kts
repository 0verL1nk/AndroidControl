import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// ── Release 签名 ────────────────────────────────────────────────
// 本地：tools/gen-release-keystore.sh 生成 .secrets/keystore.properties
// CI：  用 ANDROID_KEYSTORE_* 环境变量（或把同样的 properties 写进 .secrets/）
val keystorePropsFile = rootProject.file(".secrets/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val hasFileKeystore = keystorePropsFile.isFile &&
    keystoreProps.getProperty("storeFile")?.let { rootProject.file(".secrets/$it").isFile } == true
val hasEnvKeystore = !envKeystorePath.isNullOrBlank()
val hasReleaseSigning = hasFileKeystore || hasEnvKeystore

// 唯一的 App：同一个安装包既能"被协助"，也能"协助别人"。

android {
    namespace = "dev.ling.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ling.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (hasEnvKeystore) {
                    storeFile = file(envKeystorePath!!)
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                } else {
                    storeFile = rootProject.file(".secrets/" + keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            // 开发包也走 R8：体积小一半以上，安装更快，也能提前暴露混淆问题
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // 引擎 jar 不做压缩：assets.openFd 要求未压缩，且避免每次释放时解压
    androidResources {
        noCompress += listOf("jar")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 不允许静默产出未签名的 release 包：构建到 release 任务时明确失败
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.path == ":app:assembleRelease" || it.path == ":app:bundleRelease"
    }
    if (wantsRelease && !hasReleaseSigning) {
        throw GradleException(
            "release 签名缺失：先运行 tools/gen-release-keystore.sh，" +
                "或在 CI 中设置 ANDROID_KEYSTORE_* 环境变量",
        )
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
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":agent"))
    implementation(project(":controller"))
    implementation(project(":design"))
    implementation(project(":protocol"))
}
