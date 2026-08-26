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
        versionCode = 92
        versionName = "2.4.0-beta.10"
        buildConfigField(
            "String",
            "UPDATE_RELEASE_API_URL",
            "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-hud/releases/latest\""
        )
        buildConfigField(
            "String",
            "UPDATE_RELEASES_API_URL",
            "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-hud/releases\""
        )
        buildConfigField("String", "UPDATE_USER_AGENT", "\"BYD-HUD-UpdateCheck\"")
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"https://4f2ef57219c6c022c2155b02c14ae05c@o4511845885149184.ingest.de.sentry.io/4511845917982800\""
        )
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        create("performance") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.car.app:app:1.9.0-alpha01")
    implementation("androidx.core:core:1.13.1")
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.guava:guava:27.1-android")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("com.android:zipflinger:8.7.3")
    implementation("io.sentry:sentry-android-core:8.43.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val copyPerformanceApkToBuildOutputs by tasks.registering(Copy::class) {
    dependsOn("packagePerformance")
    from(layout.buildDirectory.file("outputs/apk/performance/app-performance.apk"))
    into(rootProject.layout.projectDirectory.dir("build_outputs"))
    rename { "byd-hud-v${android.defaultConfig.versionName}.apk" }
}

tasks.matching { it.name == "assemblePerformance" }.configureEach {
    finalizedBy(copyPerformanceApkToBuildOutputs)
}
