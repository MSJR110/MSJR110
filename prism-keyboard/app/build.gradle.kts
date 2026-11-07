plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.msjr.prism"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.msjr.prism"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // The signing key never lives in the repo. CI creates one once and keeps it in a private cache,
    // so every new build installs over the previous one. Locally, the debug key is used.
    val ksPath = System.getenv("PRISM_KEYSTORE")
    val ksPass = System.getenv("PRISM_KEYSTORE_PASSWORD")
    val hasReleaseKey = !ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty() && file(ksPath).exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("prism") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "prism"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("prism") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("dict")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}
