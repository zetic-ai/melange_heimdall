import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load local.properties for API keys
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.zeticai.melangelm.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zeticai.melangelm.demo"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Keys injected at compile time — set in local.properties:
        //   OPENAI_API_KEY=sk-...
        //   ZETIC_PERSONAL_KEY=dev_...
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProps["OPENAI_API_KEY"] ?: ""}\"")
        buildConfigField("String", "ZETIC_PERSONAL_KEY", "\"${localProps["ZETIC_PERSONAL_KEY"] ?: "YOUR_MLANGE_KEY"}\"")
        buildConfigField("String", "OPENAI_BASE_URL", "\"${localProps["OPENAI_BASE_URL"] ?: "https://api.openai.com"}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${localProps["OPENAI_MODEL"] ?: "gpt-4o-mini"}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":proxy"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
