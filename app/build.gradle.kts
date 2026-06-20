plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "tw.pp.kazi"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.pp.kazi"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        // CI 用 tag 名稱蓋過：./gradlew assembleDebug -PversionName=0.2.0
        // 沒帶就 fallback 成 "0.0.0-local"，一看就知道是本機開發 build，不是正式版
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-local"
    }

    signingConfigs {
        create("release") {
            val ksPath = (project.findProperty("KAZI_KEYSTORE_FILE") as String?)
                ?: System.getenv("KAZI_KEYSTORE_FILE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = (project.findProperty("KAZI_KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("KAZI_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("KAZI_KEY_ALIAS") as String?)
                    ?: System.getenv("KAZI_KEY_ALIAS")
                keyPassword = (project.findProperty("KAZI_KEY_PASSWORD") as String?)
                    ?: System.getenv("KAZI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 開啟：Compose / kotlinx 大量 generic + lambda 經 inline / devirtualize 後
            // 在低階 TV CPU 通常拿 10-20% 提升；shrinkResources 連帶把沒用到的資源砍掉
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore（CI 給 secret）就用正式簽；本機沒給就退回 debug 簽，
            // 這樣本機 release / Baseline Profile 產生用的 nonMinifiedRelease 變體仍可安裝。
            val releaseSigning = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    // ProfileInstaller:讓打包進 APK 的 Baseline Profile 在裝機/首次啟動時被 ART 套用(預編譯熱路徑)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // 由 :baselineprofile 模組產生的設定檔會被收進 release APK
    baselineProfile(project(":baselineprofile"))
}
