import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The desktop host reuses the platform-free shared/* modules and re-implements
// the UI with Compose Desktop. It must never depend on android/*; anything that
// needs a platform API takes it as an interface the desktop host installs, the
// same contract the Android app follows.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:transport"))
    implementation(project(":shared:persistence"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    // Swing dispatcher backs Dispatchers.Main on the JVM desktop, so shared
    // coroutine code that hops to the main thread behaves as it does on Android.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // JSON for the desktop file-based stores and the NHTSA VIN decode. Safe here
    // (this is not an Android module, so there is no bundled-org.json clash).
    implementation("org.json:json:20250517")
    // Slippy OpenStreetMap view (Swing) embedded in the desktop Map screen via
    // Compose SwingPanel — the desktop analogue of osmdroid on Android.
    implementation("org.jxmapviewer:jxmapviewer2:2.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// Vector drawables and brand marks are shared with the Android app (copied into
// composeResources). Generate a stable, public Res class the ported screens use.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.karr.key.desktop.resources"
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "com.karr.key.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "KARR Key"
            packageVersion = "1.0.6"
            description = "KARR Key desktop research host"
            vendor = "KARR Key"
            // Bundles app-resources/common/* (the bleak BLE helper) into the app
            // image; at runtime it's at System.getProperty("compose.application.resources.dir").
            appResourcesRootDir.set(project.file("app-resources"))
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "KARR Key"
                shortcut = true
            }
        }
    }
}
