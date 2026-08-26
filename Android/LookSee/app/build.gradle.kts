plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "looksee.angelll.com"
    compileSdk = 37

    defaultConfig {
        applicationId = "looksee.angelll.com"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 🚀 FIXED: Removed duplicate platform(libs.androidx.compose.bom) declarations
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --------------------------------------------------------
    // Look-See Custom Packages
    // --------------------------------------------------------

    // AWS Amplify
    implementation("com.amplifyframework:aws-api:2.40.0")
    implementation("com.amplifyframework:aws-datastore:2.40.0")
    implementation("com.amplifyframework:aws-auth-cognito:2.40.0")
    implementation("com.amplifyframework:aws-storage-s3:2.40.0")
    implementation("com.amplifyframework:core-kotlin:2.40.0")

    // Material Icons
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    val cameraXVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")

    // Google Maps
    implementation("com.google.maps.android:maps-compose:8.4.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Video Playback
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-transformer:1.11.0")
}