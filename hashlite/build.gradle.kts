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
        versionCode = 2
        versionName = "1.0.1"

        vectorDrawables {
            useSupportLibrary = true
        }

        // 诊断日志用：标记"这个构建是否本应携带 native 库"（见 AndroidManifest 的 meta-data）
        manifestPlaceholders["hashliteNative"] = "false"
    }

    // 共享调试签名：debug.keystore 不进仓库（公开仓库零密钥）。
    //  - 本机开发者：把 keystore 放到 hashlite/debug.keystore（该文件已被 .gitignore 忽略）；
    //  - CI：构建前从 Actions Secret（DEBUG_KEYSTORE_B64）恢复同一把 key；
    //  - 找不到文件时自动回退为 AGP 默认 debug 签名（fork / 无 secret 也能正常构建，只是签名不同）。
    val hasSharedKeystore = file("debug.keystore").exists()

    // 正式版签名：release.keystore 同样不进仓库（*.keystore / *.jks 均被 .gitignore 挡）。
    //  - 本机：把 release.keystore 放 hashlite/（可用 HASHLITE_RELEASE_KEYSTORE 指定别的路径），
    //    并导出 HASHLITE_RELEASE_STORE_PASSWORD / HASHLITE_RELEASE_KEY_ALIAS / HASHLITE_RELEASE_KEY_PASSWORD；
    //  - CI（release.yml）：从 Actions Secrets 恢复 keystore + 注入同样三个环境变量；
    //  - 任一条件缺失 → release 构建不加签名（产出 unsigned 包；fork / 无 secret 也能编过，
    //    正式发布 workflow 会显式检查并卡住）。
    val releaseStorePassword: String? = System.getenv("HASHLITE_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias: String? = System.getenv("HASHLITE_RELEASE_KEY_ALIAS")
    val releaseKeyPassword: String? = System.getenv("HASHLITE_RELEASE_KEY_PASSWORD")
    val releaseKeystorePath = System.getenv("HASHLITE_RELEASE_KEYSTORE").takeUnless { it.isNullOrBlank() }
        ?: "release.keystore"
    val releaseKeystore = file(releaseKeystorePath)
    val canSignRelease = releaseKeystore.exists() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasSharedKeystore) {
            create("sharedDebug") {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "hashdebug"
                keyPassword = "android"
            }
        }
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasSharedKeystore) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
        release {
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // Lint：兼容性硬门槛（NewApi 等）保持 error 并让失败阻断构建（默认即此，显式写出防漂移）。
    // 以下检查与项目的既定决策不匹配，明确关闭；其余检查（含全部兼容性类）保持启用。
    lint {
        abortOnError = true
        disable += setOf(
            // 依赖按"最少 + 钉版"维护，升级与否人工评估：
            "GradleDependency",
            "NewerVersionAvailable",
            // 只发布 arm64 APK，不提供 x86 / ChromeOS 变体：
            "ChromeOsAbiSupport",
            // targetSdk 35 为有意选择（跟随最新稳定平台适配节奏）：
            "OldTargetApi",
            // APK 分发（非 AAB）：语言跟随应用内设置，不涉及 Play 语言拆分：
            "AppBundleLocaleChanges",
        )
    }

    // ---------------------------------------------------------------- native（可选件）
    // libkeccak.so 的汇编是 aarch64 专属，而 NDK 的**宿主工具链只有 x86_64**——
    // 本机（ARM64 proot）根本执行不了 NDK 编译器，所以本地默认不编 native
    // （App 会自动回退 BouncyCastle，功能不受影响），由 CI（x86_64 runner）加
    // -Phashlite.native=true 出 .so。细节见 hashlite/src/main/cpp/README.md。
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
            // 带 native 的构建：诊断日志据此判断"库加载失败"是否异常
            manifestPlaceholders["hashliteNative"] = "true"
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