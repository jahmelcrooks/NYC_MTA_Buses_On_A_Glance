import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.dngro.transit"
    compileSdk = 37

    defaultConfig {
        // The Play Store ID. Permanent once uploaded, so never change it after release.
        // (The Kotlin package stays com.dngro.transit; the two don't have to match.)
        applicationId = "com.dngro.nycbuses"
        minSdk = 30
        targetSdk = 36
        versionCode = 110
        versionName = "1.10"

        // From local.properties (mta.api.key=...) or, on CI, the MTA_API_KEY environment variable.
        // Escaped so a stray quote or backslash in the key can't break the generated BuildConfig.
        val mtaKey = (localProperties.getProperty("mta.api.key") ?: System.getenv("MTA_API_KEY") ?: "").trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MTA_API_KEY", "\"$mtaKey\"")
    }

    // Release signing, only when the keystore details are provided: from keystore.properties
    // (never committed) or, on CI, environment variables. Without them, release builds are
    // unsigned and can't be installed or uploaded.
    val keystoreProperties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }
    fun signingValue(property: String, env: String): String? =
        keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")
    val releaseSigning = if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            // Kept off for now: the app was tested without shrinking, and R8 needs keep rules for
            // kotlinx.serialization and Retrofit before it can be turned on safely.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildToolsVersion = "36.0.0"
}

dependencies {
    implementation(libs.play.services.location)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.androidx.wear)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.tiles)
    implementation(libs.tiles.material)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.horologist.compose.tools)
    implementation(libs.horologist.tiles)
    implementation(libs.watchface.complications.data.source.ktx)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.tiles.tooling)
}
