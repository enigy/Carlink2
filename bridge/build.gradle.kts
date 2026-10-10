import java.util.Properties

plugins {
    id("com.android.application")
}

// Same signing credentials as :app (keystore.properties, never committed).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().also { props ->
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { props.load(it) }
}

// SHA-256 fingerprints of display-app signing certificates the bridge accepts, comma-separated
// (colons optional). Use the "App signing key certificate" fingerprint from Play Console →
// Test and release → App integrity. Put it in ~/.gradle/gradle.properties or pass
// -Pcarlink2.callerCerts=... . Empty = package-name check only.
val callerCerts = (findProperty("carlink2.callerCerts") as String?).orEmpty().trim()

android {
    namespace = "com.enigy.carlink2.bridge"
    compileSdk = 36

    defaultConfig {
        // The AAOS fixed USB handler package — see BridgeContract. Sideload only, never Play.
        applicationId = "android.car.usb.handler"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField("String", "CALLER_CERTS", "\"$callerCerts\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Small app; keeping it unshrunk keeps the AIDL stub and provider names obvious.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":bridge-api"))
}
