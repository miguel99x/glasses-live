import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.glasseslive.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.glasseslive.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    // Developer Mode in the Meta AI app accepts "0" (no attestation).
    // Replace with IDs from the Wearables Developer Center for production.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Signed with the debug key so the release APK installs directly.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.rootencoder.rtmp)
}
