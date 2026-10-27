plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lumen.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumen.editor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += "tflite" }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// On-device segmentation models (Apache-2.0, published by Google MediaPipe). Run once:
//   ./gradlew :app:downloadModels
tasks.register("downloadModels") {
    val dir = layout.projectDirectory.dir("src/main/assets/models")
    val models = mapOf(
        "deeplab_v3.tflite" to
            "https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/1/deeplab_v3.tflite",
        "selfie_multiclass_256x256.tflite" to
            "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
    )
    outputs.dir(dir)
    doLast {
        dir.asFile.mkdirs()
        models.forEach { (name, url) ->
            val f = dir.file(name).asFile
            if (!f.exists() || f.length() == 0L) {
                println("Downloading $name")
                uri(url).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
