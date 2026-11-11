plugins {
    alias(libs.plugins.android.library)
}

// Terminal emulator view from Android-Terminal-Emulator (Jack Palevich, Steven Luo), Apache License 2.0.
android {
    namespace = "jackpal.androidterm.emulatorview"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}
