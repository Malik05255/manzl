plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.manzl.movietranslator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.manzl.movietranslator"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // FFmpegKit and the temporary local Whisper rollback both package the same C++ runtime.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose.android)

    // Cloud-first audio path. The audio-only build provides Opus resampling/encoding.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")

    // Temporary rollback dependencies; the active user flow is cloud-first.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("com.cloudflare.realtimekit.android-vad:silero:2.0.10-cf.4")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
