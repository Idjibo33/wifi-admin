plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.wifiadmin.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.wifiadmin.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LibreSpeed endpoint: empty = disabled. Never a public server by default.
        buildConfigField("String", "SPEEDTEST_BASE_URL", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The protocol lives in router-core: the app must not duplicate it.
    implementation(project(":router-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.4")
    androidTestImplementation("androidx.compose.ui:ui-test-android:1.10.4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.7.0")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.0")
    // `ui-test-manifest` declares `androidx.activity.ComponentActivity`, the
    // activity the Compose test rule launches. It must be merged into the
    // **target** APK manifest (the app under test), hence debugImplementation.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.4")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.mockwebserver)
}
