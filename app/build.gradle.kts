plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.adjm.mp4edit"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.adjm.mp4edit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    val m3 = "1.5.1"
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-common:$m3")
    implementation("androidx.media3:media3-exoplayer:$m3")
    implementation("androidx.media3:media3-effect:$m3")
    implementation("androidx.media3:media3-transformer:$m3")
    implementation("androidx.media3:media3-ui:$m3")
}
