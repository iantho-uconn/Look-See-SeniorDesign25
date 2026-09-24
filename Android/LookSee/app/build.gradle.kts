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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
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

    val amplifyVersion = "2.40.0"
    implementation("com.amplifyframework:aws-api:$amplifyVersion")
    implementation("com.amplifyframework:aws-datastore:$amplifyVersion")
    implementation("com.amplifyframework:aws-auth-cognito:$amplifyVersion")
    implementation("com.amplifyframework:aws-storage-s3:$amplifyVersion")
    implementation("com.amplifyframework:core-kotlin:$amplifyVersion")

    implementation("androidx.compose.material:material-icons-extended")

    val cameraXVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")

    implementation("com.google.maps.android:maps-compose:8.4.0")
    implementation("com.google.maps.android:maps-compose-utils:8.4.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    val media3Version = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.14.0")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // 🚀 FIXED: Both dependencies are perfectly synced to TensorFlow 2.16.1.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    implementation("com.stripe:stripe-android:23.17.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.sentry:sentry-android:8.54.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}