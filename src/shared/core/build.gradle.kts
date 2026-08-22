plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Platform-free domain and protocol support: no Android SDK on the classpath,
// so the same code can back the Android app and a desktop host.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
