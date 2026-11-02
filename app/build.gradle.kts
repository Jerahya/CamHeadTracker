import java.net.URL
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing from keystore.properties (not in the repository; see README).
// Without it the release build is unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// The licenses shown in the app: LICENSE and THIRD_PARTY_NOTICES.txt from the
// repository root, copied into generated assets.
val legalAssets = File(buildDir, "generated/legal")

android {
    namespace = "io.github.jerahya.camheadtracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jerahya.camheadtracker"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystoreProperties.isEmpty) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    sourceSets {
        getByName("main").assets.srcDir(legalAssets)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-camera2:1.2.3")
    implementation("androidx.camera:camera-lifecycle:1.2.3")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// face_landmarker.task (Apache 2.0) is downloaded, not committed
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
val modelFile = file("src/main/assets/face_landmarker.task")

val downloadModel by tasks.registering {
    outputs.file(modelFile)
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        URL(modelUrl).openStream().use { input ->
            modelFile.outputStream().use { input.copyTo(it) }
        }
    }
}

val copyLegal by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.txt"))
    into(legalAssets)
}

tasks.named("preBuild") {
    dependsOn(downloadModel, copyLegal)
}
