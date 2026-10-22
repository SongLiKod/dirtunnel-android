plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dirtunnel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dirtunnel.app"
        minSdk = 26
        targetSdk = 35
        // ===== 唯一版本源：gradle.properties 的 VERSION_NAME；versionCode 自动计算 =====
        // 1.2.3 → 1*10000 + 2*100 + 3 = 10203（semver 递增 ⇒ versionCode 严格递增）
        val verName = providers.gradleProperty("VERSION_NAME").orNull ?: "1.0.0"
        val verParts = verName.substringBefore('-').split('.')
        val verCode = (verParts.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 +
            (verParts.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
            (verParts.getOrNull(2)?.toIntOrNull() ?: 0)
        versionCode = verCode
        versionName = verName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI 目标：arm64-v8a（主力）、armeabi-v7a（兼容旧32位设备）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 与 manifest android:extractNativeLibs=true 对齐：
            // .so 释放到 nativeLibraryDir，BinaryManager 才能做存在性/md5 校验
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:storage"))
    implementation(project(":core:engine"))
    implementation(project(":core:network"))
    implementation(project(":feature:directory"))
    implementation(project(":feature:service"))
    implementation(project(":feature:tunnel"))
    implementation(project(":feature:log"))
    implementation(project(":feature:setting"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)
}
