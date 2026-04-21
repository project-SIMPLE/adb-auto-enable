plugins {
    id("com.android.application")
}

android {
    namespace = "eu.project_simple.adbautoenable"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.project_simple.adbautoenable"
        minSdk = 21
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String? ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "dev"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
}
