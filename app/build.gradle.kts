import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

fun runhookSetting(name: String): String? {
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
    return providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
}

android {
    namespace = "com.uy_li.runhook"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    ndkVersion = "27.2.12479018"
    buildFeatures {
        prefab = true
    }

    defaultConfig {
        applicationId = "com.uy_li.runhook"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val keystorePath = runhookSetting("RUNHOOK_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                val path = File(keystorePath)
                storeFile = if (path.isAbsolute) path else rootProject.file(keystorePath)
                storePassword = runhookSetting("RUNHOOK_KEYSTORE_PASSWORD")
                keyAlias = runhookSetting("RUNHOOK_KEY_ALIAS") ?: "fbly"
                keyPassword = runhookSetting("RUNHOOK_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    packaging {
        jniLibs {
            // prefab 和 CMake 都会带上同一份 libshadowhook.so
            pickFirsts += listOf("**/libshadowhook.so", "**/libshadowhook_nothing.so")
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // 2.0.1 要求 compileSdk 37，当前 AGP 9.1 只支持到 36。1.1.1 的 API 与钩子行为足够覆盖 API 24–35。
    implementation("com.bytedance.android:shadowhook:1.1.1")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}