plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val carAppVersion = "1.7.0"

android {
    namespace = "pl.prywatny.dziennik.auto"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.prywatny.dziennik.auto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // mobile: phone app projected to the car by Android Auto.
    // automotive: the same car screens on Android Automotive OS (cars with Google built-in, emulator).
    flavorDimensions += "platform"
    productFlavors {
        create("mobile") {
            dimension = "platform"
            manifestPlaceholders["carCategory"] = "androidx.car.app.category.IOT"
        }
        create("automotive") {
            dimension = "platform"
            applicationIdSuffix = ".automotive"
            versionNameSuffix = "-automotive"
            minSdk = 29 // app-automotive
            // Automotive OS hosts list car apps of the categories they support; POI is supported everywhere.
            manifestPlaceholders["carCategory"] = "androidx.car.app.category.POI"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Self-built release: signed with the debug key so it installs with `adb install`.
            // Use your own key if you distribute the APK.
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets {
        // Synthetic (fake) fixtures shared with the webOS, Android TV and iOS apps.
        getByName("test").resources.srcDir("../fixtures")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.car.app:app:$carAppVersion")
    "automotiveImplementation"("androidx.car.app:app-automotive:$carAppVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.car.app:app-testing:$carAppVersion")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
