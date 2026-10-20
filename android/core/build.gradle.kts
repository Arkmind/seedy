plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.arkmind.seedy.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.jellyfin.core)
    // The SDK logs through kotlin-logging, which needs SLF4J's API on Android (no provider: its log calls do nothing).
    implementation(libs.slf4j.api)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
