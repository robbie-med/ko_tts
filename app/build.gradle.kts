import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing for GitHub/IzzyOnDroid builds. F-Droid signs its own builds and ignores this.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// One APK per ABI keeps each download small (versionCode = base*10 + ABI; the universal APK is base*10).
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)

android {
    namespace = "org.robbiemed.kotts"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.robbiemed.kotts"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Flip once the int8 files are uploaded to ModelManager.COMPACT_BASE.
        buildConfigField("boolean", "COMPACT_READY", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        // Keep native libs compressed in the APK: smaller download, extracted once at install.
        jniLibs.useLegacyPackaging = true
    }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK (F-Droid/IzzyOnDroid requirement).
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { out ->
            val abi = out.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val code = abiCodes[abi]
            out.versionCode.set((out.versionCode.orNull ?: 1) * 10 + (code ?: 0))
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
