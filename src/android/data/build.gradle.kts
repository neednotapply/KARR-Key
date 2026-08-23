plugins {
    id("com.android.library")
}

android {
    namespace = "com.karr.key.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    // api so android:app keeps resolving the persistence value types and codecs
    // (LocalOperation, LocalMapObservation, DeviceReadRecord, StringCipher, …)
    // it imports from the com.karr.key.data package, now in the shared module.
    api(project(":shared:persistence"))
    implementation(project(":android:ble"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
