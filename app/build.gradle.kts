import org.gradle.api.GradleException

val picovoiceAccessKey = (project.findProperty("PICOVOICE_ACCESS_KEY") as? String)
    ?: throw GradleException("PICOVOICE_ACCESS_KEY property is not set. Add it to gradle.properties (or use -PPICOVOICE_ACCESS_KEY=...).")

val cartesiaApiKey = (project.findProperty("CARTESIA_API_KEY") as? String) ?: ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.frontieraudio.heartbeat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.frontieraudio.heartbeat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val sanitizedAccessKey = picovoiceAccessKey.replace("\"", "\\\"")
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$sanitizedAccessKey\"")
        val sanitizedCartesiaKey = cartesiaApiKey.replace("\"", "\\\"")
        buildConfigField("String", "CARTESIA_API_KEY", "\"$sanitizedCartesiaKey\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")
    implementation("ai.picovoice:eagle-android:1.0.1")

    // WebSocket client for cloud transcription
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // Location services for GPS
    implementation("com.google.android.gms:play-services-location:21.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
