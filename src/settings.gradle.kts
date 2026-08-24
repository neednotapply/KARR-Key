pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KARR-Key"

// shared/* is platform-free Kotlin/JVM reused by every host; android/* is the
// Android app and the modules that touch the Android SDK. desktop/* is the
// Compose Desktop host and depends only on shared/*.
include(
    ":shared:core",
    ":shared:protocol",
    ":shared:transport",
    ":shared:persistence",
    ":android:app",
    ":android:ble",
    ":android:data",
    ":android:auto",
    ":desktop:app",
)
