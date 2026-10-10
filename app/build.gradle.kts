import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// Load signing credentials from keystore.properties (never committed to VCS)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().also { props ->
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { props.load(it) }
}

android {
    namespace = "com.carlink"
    compileSdk = 36

//###############################################
//###############################################
//###############################################

    defaultConfig {
        // Carlink2 display app — Play only. Must match BridgeContract.DISPLAY_PACKAGE, which
        // is the only caller the sideloaded bridge (:bridge) will serve.
        applicationId = "com.enigy.carlink2"
        minSdk = 29
        targetSdk = 36
        versionCode = 205
        versionName = "1.0.0"

//###############################################
//###############################################
//###############################################

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
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
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // No distribution flavors in Carlink2. The old sideload/play split existed only to vary the
    // cluster icon ContentProvider authority (issue #6). That authority now lives in the
    // sideloaded bridge (:bridge), so this app ships one variant, to Play.
    //   NOTE: the icon hook only renders on gminfo3.7 (AAOS 12). On the GM VCU platform
    //   (VCUNH1, AAOS 14) GM's VMSPlugin renders the cluster glyph from the maneuver-type enum
    //   and masks app bitmaps — see documents/reference/gminfo/projection/cluster_navigation.md.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation for debug checks
        aidl = true         // INaviVideoSink / INaviVideoSource for ClusterHomeDisplay AltVideo (0x2C)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Suppress DiscouragedApi warning for scheduleAtFixedRate usage.
        // Tested alternatives (coroutines, scheduleWithFixedDelay) caused issues
        // with microphone timing - Timer.scheduleAtFixedRate works reliably.
        // See documents/revisions.txt [19], [21] for history.
        disable += "DiscouragedApi"
        disable += "Instantiatable"  // CarAppActivity from app-automotive AAR — false positive
        disable += "InvalidUsesTagAttribute"  // "navigation" is valid for Car App Library nav apps
    }
}

// Kotlin compiler: report deprecations and unchecked casts as warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
        allWarningsAsErrors.set(false) // report but don't fail — tighten later
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true) // report only on first run — fix incrementally
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    ignoreFailures = true // report only on first run
}

dependencies {
    // AIDL contract with the sideloaded USB bridge
    implementation(project(":bridge-api"))

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DocumentFile for SAF file operations (capture recording)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration — Media3 1.10.0 (latest stable, 2026-03-26).
    // media3-session supersedes legacy androidx.media:media (MediaSessionCompat, deprecated
    // in androidx.media 1.8.0-alpha01). GM AAOS observers use platform android.media.session.*
    // APIs which Media3 auto-registers under the hood for backwards compatibility, so the
    // GMCarMediaService → ClusterService → cluster pipeline keeps working.
    // media3-common provides Player / SimpleBasePlayer / MediaItem / MediaMetadata.
    // media3-exoplayer is intentionally NOT included: this app does not decode/play audio
    // locally — the connected phone plays over USB; we only mirror metadata + forward commands.
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Car App Library for AAOS cluster navigation (Templates Host)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-automotive:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

