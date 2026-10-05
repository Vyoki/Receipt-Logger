plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kitchenreceipts.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kitchenreceipts.app"
        minSdk = 26 // java.time, PdfRenderer and adaptive icons without desugaring
        targetSdk = 35
        // CI build number and commit, so every shared report says which build read the document.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0+" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // On-device AI reader (llama.cpp). arm64 for phones, x86_64 for the emulator used in tests.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The AI reader loads the best CPU variant for the phone from the native library folder at run time,
        // so native libraries must be extracted on install.
        jniLibs.useLegacyPackaging = true
    }
}

// llama.cpp, pinned: downloaded once into third_party/ (not committed).
val llamaCppTag = "b11242"
val llamaCppDir = rootProject.file("third_party/llama.cpp")
val fetchLlamaCpp by tasks.registering(Exec::class) {
    description = "Downloads llama.cpp $llamaCppTag for the on-device AI reader"
    onlyIf { !llamaCppDir.resolve("CMakeLists.txt").exists() }
    commandLine("git", "clone", "--depth", "1", "--branch", llamaCppTag, "https://github.com/ggml-org/llama.cpp", llamaCppDir.absolutePath)
}
tasks.named("preBuild") { dependsOn(fetchLlamaCpp) }

ksp {
    // Room writes a JSON snapshot of every schema version here; commit it with each migration.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Bundled on-device model: works offline from first launch, no Google Play download, no API key.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.room.testing)
}
