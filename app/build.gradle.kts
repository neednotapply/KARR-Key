plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.karr.key"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.karr.key"
        minSdk = 23
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystorePath = providers.environmentVariable("KARR_RELEASE_KEYSTORE").orNull
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("KARR_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KARR_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KARR_RELEASE_KEY_PASSWORD").get()
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName
                .set("KARR-Key.apk")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":ble"))
    implementation(project(":data"))

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigation3:navigation3-ui:1.1.4")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    androidTestImplementation("androidx.test.espresso:espresso-accessibility:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyOfflineManifest by tasks.registering {
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    inputs.file(manifest)
    doLast {
        val manifestText = manifest.asFile.readText()
        listOf(
            "android.permission.INTERNET" to
                "Map requires INTERNET for viewed OpenStreetMap tiles.",
            "android.permission.ACCESS_NETWORK_STATE" to
                "The map renderer requires ACCESS_NETWORK_STATE before requesting map tiles.",
        ).forEach { (permission, message) ->
            val declaration = Regex(
                """<uses-permission\s+[^>]*android:name="$permission"[^>]*/>""",
            ).find(manifestText)?.value
            check(declaration != null && !declaration.contains("""tools:node="remove""")) {
                message
            }
        }

        val bluetoothScanDeclaration = Regex(
            """<uses-permission\s+[^>]*android:name="android.permission.BLUETOOTH_SCAN"[^>]*/>""",
        ).find(manifestText)?.value
        check(
            bluetoothScanDeclaration?.contains(
                """android:usesPermissionFlags="neverForLocation""",
            ) == true,
        ) {
            "BLUETOOTH_SCAN must assert neverForLocation."
        }

        listOf(
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        ).forEach { permission ->
            check(
                Regex(
                    """<uses-permission\s+[^>]*android:name="$permission"[^>]*/>""",
                ).containsMatchIn(manifestText),
            ) {
                "$permission must be explicit because Map requests foreground location."
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOfflineManifest)
}
