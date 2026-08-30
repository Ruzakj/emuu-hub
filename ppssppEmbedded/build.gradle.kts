plugins {
    id("com.android.library")
    id("com.google.protobuf")
}

android {
    namespace = "org.ppsspp.ppsspp"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "FLAVOR", "\"normal\"")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID=true",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DANDROID_CPP_FEATURES=",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/ppsspp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs("../third_party/ppsspp/android/src")
            res.srcDirs(
                "../third_party/ppsspp/android/res",
                "../third_party/ppsspp/android/normal/res",
                "../third_party/ppsspp/android/src/main/res"
            )
            assets.srcDirs("../third_party/ppsspp/assets")
            aidl.srcDirs("../third_party/ppsspp/android/src")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.protobuf:protobuf-javalite:4.33.5")
}
