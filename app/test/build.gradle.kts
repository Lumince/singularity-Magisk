plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.veygax.eventhorizon.magisk.test"

    defaultConfig {
        applicationId = "com.veygax.eventhorizon.magisk.test"
        versionCode = 1
        versionName = "1.0"
        proguardFile("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
        }
    }
}

setupTestApk()

dependencies {
    implementation(libs.test.runner)
    implementation(libs.test.rules)
    implementation(libs.test.junit)
    implementation(libs.test.uiautomator)
}
