plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

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
