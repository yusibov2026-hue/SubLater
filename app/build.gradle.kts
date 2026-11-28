import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun releaseSignProp(propKey: String, envKey: String): String {
    return (keystoreProps.getProperty(propKey) ?: System.getenv(envKey) ?: "").trim()
}

android {
    namespace = "com.fromsub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fromsub.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 89
        versionName = "1.0.89"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val ksPath = releaseSignProp("storeFile", "SUBLYTER_KEYSTORE_PATH")
        val ksPass = releaseSignProp("storePassword", "SUBLYTER_KEYSTORE_PASSWORD")
        val ksAlias = releaseSignProp("keyAlias", "SUBLYTER_KEY_ALIAS")
        val ksKeyPass = releaseSignProp("keyPassword", "SUBLYTER_KEY_PASSWORD")
        if (ksPath.isNotEmpty() && rootProject.file(ksPath).exists() && ksPass.isNotEmpty() && ksAlias.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass.ifEmpty { ksPass }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-extractor:$media3Version")
    // HLS (m3u8) и DASH (mpd): Nuvio/Stremio часто отдают именно такие потоки
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
