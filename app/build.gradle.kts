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

    defaultConfig {
        applicationId = "com.uy_li.runhook"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}