plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dirtunnel.core.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.runtime)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    // 仅使用图标字形，不引入任何 Material 组件体系（TECH.md 四：放弃 Material3 整套组件）
    api(libs.compose.material.icons.core)
    api(libs.compose.material.icons.extended)
    api(libs.haze)
    api(libs.lifecycle.runtime.compose)
    api(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
}
