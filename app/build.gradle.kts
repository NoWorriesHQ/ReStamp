plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself; only the Compose compiler plugin is added.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.noworrieshq.restamp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noworrieshq.restamp"
        // Android 10: scoped storage and ACCESS_MEDIA_LOCATION both start here.
        minSdk = 29
        targetSdk = 37 // Android 17
        // CI sets the code from the run number so each upload to Play is higher than the last.
        versionCode = System.getenv("RESTAMP_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // A fixed debug key, so each CI test build installs over the previous one. Every CI run
        // otherwise makes its own random debug key and Android refuses the update. This key is
        // only for test builds; the Play release gets its own private key in step 4.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The private upload key for Play. It never goes in the repo: CI gets it from secrets.
        System.getenv("RESTAMP_UPLOAD_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("RESTAMP_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("RESTAMP_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("RESTAMP_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    // Not a tracked input otherwise, so a cached result could stand in for a run over real photos.
    inputs.property("jpegCorpus", System.getenv("RESTAMP_JPEG_CORPUS").orEmpty())
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)
    implementation(libs.billing)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
