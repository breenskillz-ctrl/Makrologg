plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "no.makrologg.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "no.makrologg.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // The signing key is never stored in the repo. GitHub Actions decodes it from
    // repository secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD) and passes it in here.
    val storePath = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (storePath != null) {
            create("fixed") {
                storeFile = file(storePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "makrologg"
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (storePath != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
