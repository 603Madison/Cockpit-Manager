import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release APKs are signed with one permanent key, so each release installs as an
// update over the last. Locally it comes from keystore.properties (git-ignored,
// next to settings.gradle.kts); in CI from the RELEASE_KEYSTORE_* environment
// variables. Without either, release builds are unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// Blank counts as unset: CI passes optional secrets that weren't added as empty strings.
fun signingValue(property: String, env: String): String? =
    (keystoreProps.getProperty(property) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val keystorePath = signingValue("storeFile", "RELEASE_KEYSTORE_FILE")

android {
    namespace = "app.cockpitmanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.cockpitmanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS") ?: "cockpitmanager"
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking stays off until a shrunk build has been tested on a device:
            // kotlinx.serialization and OkHttp both lean on reflection-adjacent metadata.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // F-Droid and other independent builders can't read the dependency list AGP
    // otherwise embeds, encrypted with Google's key, in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric renders the real Compose UI on the JVM for screenshot tests.
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric's file-descriptor shim needs this on JDK 17+.
                test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
