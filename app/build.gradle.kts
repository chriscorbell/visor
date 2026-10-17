plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each release must carry a higher versionCode than the one installed, or the
// tablet refuses the update. CI passes its run number; local builds are 1.
val releaseNumber = System.getenv("VISOR_RELEASE")?.toInt() ?: 1

android {
    namespace = "com.chriscorbell.visor"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.chriscorbell.visor"
        // Android 12 is the first that lets an app update itself without a tap.
        minSdk = 31
        targetSdk = 36
        versionCode = releaseNumber
        versionName = "$releaseNumber"
        // The Beszel hub on minicore, on the LAN: the desk display isn't on the tailnet.
        buildConfigField("String", "HUB_URL", "\"http://10.0.0.20:8090\"")
        // camview's Relay, which already makes a 1080p stream for the desk display.
        buildConfigField("String", "FEED_URL", "\"http://10.0.0.20:3147/api/stream.mp4?src=desk\"")
        buildConfigField("String", "RELEASES_URL", "\"https://api.github.com/repos/chriscorbell/visor/releases/latest\"")
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        // The release workflow supplies the release key through these variables.
        // Without them, release builds use the machine's debug key.
        System.getenv("VISOR_ANDROID_KEYSTORE")?.let { keystore ->
            create("visorRelease") {
                storeFile = file(keystore)
                storePassword = System.getenv("VISOR_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VISOR_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("VISOR_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Installs beside the release app, so testing never breaks self-updates.
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig =
                signingConfigs.findByName("visorRelease") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
