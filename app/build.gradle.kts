plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.aba.cpx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aba.cpx"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

/**
 * ✅ APK 파일명 변경 (AGP 8.12.3 호환)
 * 결과:
 * cpx_1.0_release.apk
 * cpx_1.0_debug.apk
 */
androidComponents {
    onVariants { variant ->
        val appName = "cpx"

        // ✅ 제일 안전: defaultConfig에서 가져오기 (variant.versionName 미노출 이슈 회피)
        val versionName = android.defaultConfig.versionName ?: "0.0"
        val buildType = variant.buildType // "debug" / "release"

        variant.outputs.forEach { output ->
            // ✅ AGP 8.8+에서 널리 쓰이는 방식 (outputFileName 직접 변경)
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName = "${appName}_${versionName}_${buildType}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.analytics)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}