plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Platform-free persistence value types and codecs: operation/map/device-read
// records, the READ-snapshot JSON codec, the WiGLE CSV codec, map-history
// dedup, the cipher/secure-value contracts, and the device-key store. No Android
// SDK on the classpath, so the Android app and the desktop host share one copy;
// each host supplies its own concrete stores over these types.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    // MapHistoryCsv maps stored map records to/from the BLE device classification
    // (CompatibleKarrDevice / KarrCandidateTier), which lives in shared:transport.
    implementation(project(":shared:transport"))
    testImplementation("junit:junit:4.13.2")
}
