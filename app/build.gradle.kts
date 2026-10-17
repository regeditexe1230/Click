plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yjc.click"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yjc.click"
        minSdk = 24
        targetSdk = 33
        versionCode = 24
        versionName = "5.3-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "click123"
            keyAlias = "click"
            keyPassword = "click123"
        }
    }

    lint {
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        // 本地调试也走 release 签名：Android Studio 点 Run 装出来的包和 Release 包是同一把钥匙，
        // 手机上可以互相覆盖安装，不会再出现「安装包签名与当前版本不一致」。
        // 只改签名，debuggable / 不混淆 / 调试符号等调试特性保持不变。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
