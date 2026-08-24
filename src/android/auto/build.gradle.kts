plugins {
    id("com.android.library")
}

android {
    namespace = "com.karr.key.auto"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The car Map reads java.time.Instant map-sighting timestamps from shared:persistence,
        // which needs desugaring below minSdk 26. Matches android:data.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    // api-exposes shared:transport (BleController, BleSessionState, exchanges).
    implementation(project(":android:ble"))
    // api-exposes shared:persistence (EncryptedDeviceKeyStore, LocalKeyRepository, stores).
    implementation(project(":android:data"))
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.car.app:app-testing:1.7.0")
}
