plugins { id("com.android.application") version "9.2.1" }
android {
    namespace = "local.chessbridge"
    compileSdk = 36
    packaging.jniLibs.useLegacyPackaging = true
    defaultConfig {
        applicationId = "local.chessbridge"
        minSdk = 34
        targetSdk = 36
        ndk.abiFilters += "arm64-v8a"
        versionCode = 5
        versionName = "1.4"
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
