import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nsheremet.circa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nsheremet.circa"
        minSdk = 31          // BLUETOOTH_CONNECT requires API 31
        targetSdk = 35
        versionCode = 45
        versionName = "4.2.2"
    }

    // Release signing: reads keystore.properties (gitignored) if present, else unsigned.
    val ksProps = rootProject.file("keystore.properties")
    if (ksProps.exists()) {
        val p = Properties().apply { ksProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksProps.exists()) signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.haze)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
