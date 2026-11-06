plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号由 CI 注入：-PappVersionName=1.1.0 -PappVersionCode=123
val appVersionName: String = providers.gradleProperty("appVersionName").orNull ?: "1.2.1"
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 10201

// 发布签名：由 GitHub Actions 从 Secrets 注入 keystore；未配置时回退到 debug keystore（仅用于侧载测试）。
val releaseKeystore: File? = System.getenv("PRIVPROBE_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }

if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    check(releaseKeystore != null) { "Release signing keystore is required" }
    check(!System.getenv("PRIVPROBE_KEYSTORE_PASSWORD").isNullOrBlank()) { "Keystore password is required" }
    check(!System.getenv("PRIVPROBE_KEY_PASSWORD").isNullOrBlank()) { "Key password is required" }
}

android {
    namespace = "dev.popsicle.privprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.popsicle.privprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("PRIVPROBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PRIVPROBE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("PRIVPROBE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // CI 注入 keystore 时使用发布签名；未注入（本地调试）时回退到 debug keystore。
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
        // 采集器原生二进制需要可执行，不能压缩
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress += listOf("bin")
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 不引入 debug ui-tooling：其 PreviewActivity 会 exported=true，增加攻击面。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
