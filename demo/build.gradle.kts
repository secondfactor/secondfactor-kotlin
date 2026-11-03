import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val backendUrl = providers.gradleProperty("demo.backendUrl").get()
val returnUrl = URI(providers.gradleProperty("demo.returnUrl").get())

android {
    namespace = "ai.secondfactor.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.secondfactor.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        // The manifest's App Link intent filter is built from the return URL,
        // so the app always claims exactly the URL the server sends users to.
        manifestPlaceholders["returnScheme"] = returnUrl.scheme
        manifestPlaceholders["returnHost"] = returnUrl.host
        manifestPlaceholders["returnPath"] = returnUrl.path
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The demo compiles the SDK straight from the file customers download, so
    // it always shows exactly what they get.
    sourceSets["main"].kotlin.directories.add("../secondfactor")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
