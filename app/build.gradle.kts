plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pianke"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.pianke"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    androidResources { noCompress += "bin" }

    lint {
        // A phone-only camera app: the CAMERA permission need not come with a ChromeOS uses-feature.
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
    }

    // The golden inputs and expectations live in tests/engine/golden; the instrumented test reads them as assets.
    sourceSets.getByName("androidTest").assets.directories.add("../tests/engine/golden")

    testOptions {
        // PendingStore logs through android.util.Log; the JVM unit test gets no-op logging.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
