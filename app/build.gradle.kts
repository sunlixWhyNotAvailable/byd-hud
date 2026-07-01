plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydhud.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bydhud.app"
        minSdk = 29
        targetSdk = 29
        //keeps android upgrade ordering ahead of the previous 1.0.1 field build.
        versionCode = 50
        versionName = "1.2.1"
        buildConfigField(
            "String",
            "UPDATE_RELEASE_API_URL",
            "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-hud/releases/latest\""
        )
        buildConfigField("String", "UPDATE_USER_AGENT", "\"BYD-HUD-UpdateCheck\"")
        buildConfigField("boolean", "WAZE_FRAME_CAPTURE_BETA", "true")
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core:1.13.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

//keeps a stable local release-candidate APK outside app/build so Gradle clean does not remove it.
val copyDebugApkToBuildOutputs by tasks.registering(Copy::class) {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("build_outputs"))
    rename { "byd-hud-v${android.defaultConfig.versionName}.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApkToBuildOutputs)
}
