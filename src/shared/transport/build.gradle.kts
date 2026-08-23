plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Platform-free BLE transport contract and its supporting logic: the
// BleController interface, session/scan models, device classification, wire
// command/mode exchanges, and reconnect/freshness policy. No Android SDK on the
// classpath, so the Android app and the desktop host share one copy. Each host
// provides its own BleController implementation over the same contract.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
