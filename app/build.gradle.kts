plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pianke"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.pianke"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    androidResources { noCompress += "bin" }

    // The golden inputs and expectations live in tests/engine/golden; the instrumented test reads them as assets.
    sourceSets.getByName("androidTest").assets.directories.add("../tests/engine/golden")

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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
