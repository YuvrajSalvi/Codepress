plugins {
    alias(libs.plugins.android.library)
}

// Pseudo-terminal helper based on libtermexec from Android-Terminal-Emulator (Steven Luo), Apache License 2.0.
android {
    namespace = "jackpal.androidterm"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    api(project(":emulatorview"))
}
