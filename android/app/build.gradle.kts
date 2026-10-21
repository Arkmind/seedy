import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties next to this project (git-ignored), or the SEEDY_KEYSTORE_* environment
// variables in CI. Without either, release builds come out unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

// The version comes from the release tag in CI (-PappVersion=0.2.0 or 0.2.0-beta.1); the code from it unless given.
// Pre-releases come before their release (0.2.0-alpha.N → 20001+N, -beta.N → 20031+N, -rc.N → 20061+N, 0.2.0 → 20099),
// so Google Play always gets a higher code than the build before.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: run {
    val m = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([A-Za-z]+)\.?(\d+)?)?""").find(appVersion)
    val (major, minor, patch) = (1..3).map { m?.groupValues?.get(it)?.toIntOrNull() ?: 0 }
    val n = (m?.groupValues?.get(5)?.toIntOrNull() ?: 0).coerceIn(0, 28)
    val stage = when (m?.groupValues?.get(4)?.lowercase().orEmpty()) {
        "" -> 99
        "beta" -> 31 + n
        "rc" -> 61 + n
        else -> 1 + n
    }
    ((major * 100 + minor) * 100 + patch) * 100 + stage
}

android {
    namespace = "io.github.arkmind.seedy"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.arkmind.seedy"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        val store = signingValue("storeFile", "SEEDY_KEYSTORE_FILE")
        if (store != null) {
            create("release") {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "SEEDY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SEEDY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SEEDY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
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
}

dependencies {
    implementation(project(":mobile"))
    implementation(project(":tv"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
}
