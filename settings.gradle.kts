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

rootProject.name = "KarrMaster"

include(
    ":app",
    ":core",
    ":protocol",
    ":ble",
    ":data",
    ":feature:customer",
    ":feature:dealer",
    ":feature:installer",
)
