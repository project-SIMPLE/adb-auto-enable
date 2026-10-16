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
        versionCode = (project.findProperty("versionCode") as String? ?: "3").toInt()
        versionName = project.findProperty("versionName") as String? ?: "2.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore.jks")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jmdns:jmdns:3.5.8")
}
