import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read local properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "ch.sorawit.bleremotecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.sorawit.bleremotecontrol"
        minSdk = 26              // required for adaptive icons & CameraX
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.10"

        // Get BLE device name from environment variable, or local.properties, or use default
        val bleDeviceName = System.getenv("BLE_DEVICE_NAME") ?: localProperties.getProperty("ble.deviceName") ?: "BtBridge"
        buildConfigField("String", "BLE_DEVICE_NAME", "\"$bleDeviceName\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true            // enables Jetpack Compose
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            // --- Local Build ---
            // storeFile = file("keystore.jks")
            // storePassword = "yourPassword"
            // keyAlias = "mykey"
            // keyPassword = "yourAliasPassword"

            // --- GitHub Actions ---
            val ks = System.getenv("STORE_FILE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!System.getenv("STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("debug") { /* optional */ }
    }
}

dependencies {

    // ---- Base / AndroidX ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.annotation:annotation:1.8.2")

    // ---- Material Design 3 (Compose & XML) ----
    implementation("com.google.android.material:material:1.12.0")

    // ---- Jetpack Compose ----
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ---- CameraX (for QR scan) ----
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    // ---- ML Kit Barcode Scanner ----
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ---- ZXing (generate QR code) ----
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ---- Secure Storage (HMAC Key) ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ---- Test dependencies ----
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
