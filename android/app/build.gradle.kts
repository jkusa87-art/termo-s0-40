import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksFile = rootProject.file("keystore.properties")
val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { load(it) } }

android {
    namespace = "pl.termo.s040"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.termo.s040"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.5"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Wlasny klucz podpisu: plik android/keystore.properties (NIE wrzucac do repozytorium):
    //   storeFile=C:/sciezka/termo-release.jks
    //   storePassword=...
    //   keyAlias=termo
    //   keyPassword=...
    // Bez tego pliku wersja release jest podpisana kluczem testowym (debug).
    signingConfigs {
        if (ksFile.exists()) create("release") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
