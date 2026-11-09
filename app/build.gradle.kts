plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "jp.a09848281.bluetooth_explorer"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.a09848281.bluetooth_explorer"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
