plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.xiaokun19.hashlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.xiaokun19.hashlite"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 仓库自带的调试签名（口令是 Android 惯例的 "android"，只是 debug key）。
    // 目的：**CI 出的 APK 能直接覆盖本机编译的 APK**，省掉"签名不匹配 → 先卸载"的来回。
    signingConfigs {
        create("sharedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "hashdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ---------------------------------------------------------------- native（可选件）
    // libkeccak.so 的汇编是 aarch64 专属，而 NDK 的**宿主工具链只有 x86_64**——
    // 本机（ARM64 proot）根本执行不了 NDK 编译器，所以本地默认不编 native
    // （App 会自动回退 BouncyCastle，功能不受影响），由 CI（x86_64 runner）加
    val nativeEnabled = (project.findProperty("hashlite.native") as String?)?.toBoolean() == true
    if (nativeEnabled) {
        ndkVersion = "28.2.13676358"

        defaultConfig {
            ndk {
                // 只打 arm64-v8a：汇编只在 aarch64 上有意义
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=none")
                }
            }
        }

        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
}

// 纯净版原本刻意不引 BouncyCastle；现在要支持 SHA-3 与国密 SM3，而 Android 平台
// 没有这两类算法的实现（BoringSSL 连 API 都没导出），只能自带。代价：APK 大约 +3MB。
dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.bcprov.jdk18on)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}