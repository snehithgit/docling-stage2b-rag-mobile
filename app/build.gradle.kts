plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.shiprag.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shiprag.mobile"
        minSdk = 26
        targetSdk = 35
        // ShipRAG targets modern ARM64 Android phones; avoid packaging x86 ONNX libraries.
        ndk { abiFilters.add("arm64-v8a") }
        // Newer GitHub runs always have higher version codes for Android's update checks.
        // On a local build, use a fixed value above every previous temporary debug build.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 10000 + runNumber
        versionName = "0.3.$runNumber"
    }
    // No permanent private key is committed to this public repository.
    // GitHub decrypts an encrypted keystore with its private Actions secret.
    val stableFile = System.getenv("SHIPRAG_KEYSTORE_FILE")
    val stablePassword = System.getenv("SHIPRAG_KEYSTORE_PASSWORD")
    signingConfigs {
        if (!stableFile.isNullOrBlank() && !stablePassword.isNullOrBlank()) {
            create("shipragStable") {
                storeFile = file(stableFile)
                storePassword = stablePassword
                keyAlias = "shiprag"
                keyPassword = stablePassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("shipragStable")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended-android:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}