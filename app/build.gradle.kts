plugins {
    id("com.android.application") version "9.0.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}
android {
    namespace = "com.ushi.sennaar"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ushi.sennaar"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("releaseVersionName").getOrElse("0.1.0")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
