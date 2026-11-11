plugins { id("com.android.application") }

android {
    namespace = "ai.minash.gyeot.companion"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.minash.gyeot.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "ai.minash.gyeot.companion.PeerIntegrationRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":care-core"))
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
