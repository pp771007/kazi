plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 沒設密碼（本機沒配 gradle.properties / CI 沒給 secret）就不簽，避免 build 直接爆
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
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
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
