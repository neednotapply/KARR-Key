plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Compose Multiplatform (Desktop) for the desktop/* host. The Compose
    // compiler itself is supplied by kotlin.plugin.compose above; this plugin
    // adds the desktop runtime artifacts and jpackage-based packaging. 1.11.1 is
    // the current stable release and targets the latest Kotlin.
    id("org.jetbrains.compose") version "1.11.1" apply false
}
