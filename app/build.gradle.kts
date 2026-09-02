plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ric.emuhub"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.ric.emuhub"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("EMUHUB_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("EMUHUB_VERSION_NAME") ?: "0.3.0"

        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("stableRelease") {
            val storePath = System.getenv("EMUHUB_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) storeFile = file(storePath)
            storePassword = System.getenv("EMUHUB_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("EMUHUB_KEY_ALIAS")
            keyPassword = System.getenv("EMUHUB_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")

    implementation(files("libs/jlmod-runtime.aar"))
    implementation(files("libs/jlmod-dexlib.aar"))
    implementation("androidx.databinding:viewbinding:8.7.3")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.arch.core:core-common:2.2.0")
    implementation("androidx.collection:collection-ktx:1.3.0")
    implementation("androidx.concurrent:concurrent-futures:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-rxjava2:2.6.1")
    implementation("androidx.transition:transition:1.4.1")
    implementation("com.google.code.gson:gson:2.9.1")
    implementation("com.google.oboe:oboe:1.7.0")
    implementation("ch.acra:acra-http:5.11.3")
    implementation("com.github.yukuku:ambilwarna:2.0.1")
    implementation("com.github.penn5:donations:3.6.0")
    implementation("com.github.nikita36078:mobile-ffmpeg:v4.3.2-compact")
    implementation("com.github.woesss:filepicker:4.4.0")
    implementation("com.github.nikita36078:pngj:2.2.3")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.ow2.asm:asm:9.6")
}
