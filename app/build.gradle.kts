plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.karr.master"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.karr.master"
        minSdk = 23
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.2"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":ble"))
    implementation(project(":data"))
    implementation(project(":feature:customer"))
    implementation(project(":feature:dealer"))
    implementation(project(":feature:installer"))

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}

val verifyOfflineManifest by tasks.registering {
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    inputs.file(manifest)
    doLast {
        val manifestText = manifest.asFile.readText()
        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        ).forEach { permission ->
            val declaration = Regex(
                """<uses-permission\s+[^>]*android:name="$permission"[^>]*/>""",
            ).find(manifestText)?.value
            check(declaration?.contains("""tools:node="remove"""") == true) {
                "$permission must have an explicit manifest-merger removal rule."
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOfflineManifest)
}
