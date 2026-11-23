plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tocacolor"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tocacolor"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    // Firma fija: así cada versión nueva se instala encima de la anterior.
    signingConfigs {
        create("fija") {
            storeFile = file("playus.keystore")
            storePassword = "playus123"
            keyAlias = "playus"
            keyPassword = "playus123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fija") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Reconocimiento de texto de Google (funciona sin internet, dentro del móvil)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
