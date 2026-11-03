plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miniqwerty.kb"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.miniqwerty.kb"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // No 32-bit ABIs: the engine typedefs Uint64 as `unsigned long`, which is 32-bit there.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        val storeFileProp = providers.gradleProperty("miniqwerty.storeFile").orNull
        if (storeFileProp != null) {
            create("release") {
                storeFile = file(storeFileProp)
                storePassword = providers.gradleProperty("miniqwerty.storePassword").get()
                keyAlias = providers.gradleProperty("miniqwerty.keyAlias").get()
                keyPassword = providers.gradleProperty("miniqwerty.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
