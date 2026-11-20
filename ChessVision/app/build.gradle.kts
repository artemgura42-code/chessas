plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.chessvision"
    compileSdk = 34
    defaultConfig { applicationId = "app.chessvision"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" } }
    // libstockfish.so must be extracted to disk so it can be executed
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
