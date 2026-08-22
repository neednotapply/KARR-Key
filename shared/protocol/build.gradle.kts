plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Platform-free: the wire protocol and authentication stack use only java.* and
// javax.crypto.*, keeping them reusable from a desktop host.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
