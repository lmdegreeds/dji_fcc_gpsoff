import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, loaded from the un-committed keystore.properties at the repo
// root. Absent (a fresh clone, CI) → release falls back to the debug key below,
// so every checkout still builds; only a machine holding the key produces an APK
// that can update an installed release.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")
    ?.let { file(it).exists() } == true

android {
    namespace = "com.dji.fccgpsoff"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dji.fccgpsoff"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // The ABIs DJI smart controllers ship.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            // v1 (JAR signing) is for Android below 7.0, which minSdk 24 already
            // excludes — AGP drops it regardless of the flag, so asking for it only
            // bloats the APK with a signature nothing checks. v2 is what the RC
            // verifies; v3 additionally allows rotating this key later without
            // orphaning installed copies.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        // Debug is the diagnostic build (unsigned, no shrinking) used for
        // development on the RC.
        getByName("debug") {
            isMinifyEnabled = false
        }
        // Release shrinks with R8 and is signed with the real key when
        // keystore.properties is present; otherwise it falls back to the debug key
        // so the build never breaks on a machine without the secret. An APK signed
        // with the debug key CANNOT update one signed with the release key —
        // check the log line below before publishing anything.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    // extractNativeLibs="true" in the manifest requires legacy jniLib packaging.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // JVM unit tests touch DiagLog, which mirrors to android.util.Log; without
    // this, unmocked android.* stubs throw instead of returning defaults.
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Say which key a release build will carry, so "signed with the debug key" is
// never discovered after the APK has already been handed out.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") }) {
        logger.lifecycle(
            if (hasReleaseKey) "release signing: keystore.properties → ${keystoreProps.getProperty("keyAlias")}"
            else "release signing: NO keystore.properties — falling back to the DEBUG key (do not publish this APK)"
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath (Android stubs it out) so the JSON
    // parsers — ParamCatalog.load, ProfileRunner.parse — can be tested off-device.
    testImplementation("org.json:json:20240303")
}
