import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.file.DirectoryProperty
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydhud.app"
    compileSdk = 34
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "com.bydhud.app"
        minSdk = 29
        targetSdk = 29
        versionCode = 128
        versionName = "3.7.2-beta.1"
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

    sourceSets.getByName("test").resources.srcDir("src/main/assets/hud-check")

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
    implementation("androidx.car.app:app:1.9.0-alpha01")
    implementation("androidx.core:core:1.13.1")
    implementation("org.smali:dexlib2:2.5.2")
    compileOnly("com.google.guava:guava:27.1-android")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("com.android:zipflinger:8.7.3")
    implementation("io.sentry:sentry-android-core:8.43.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Build the navigator extensions from public sources before packaging Java resources.
abstract class HudResources : JavaExec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val hudToolsClasspath = configurations.create("hudToolsClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(hudToolsClasspath.name, "org.smali:dexlib2:2.5.2")
    add(hudToolsClasspath.name, "com.google.guava:guava:27.1-android")
    add(hudToolsClasspath.name, "com.google.guava:failureaccess:1.0.1")
    add(hudToolsClasspath.name, "com.android:zipflinger:8.7.3")
}
val hudJavaTools = extensions.getByType<JavaToolchainService>()
val hudCompiler = hudJavaTools.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) }
val hudLauncher = hudJavaTools.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
val hudSources = rootProject.layout.projectDirectory.dir("navigator-extensions")
val hudSdk = androidComponents.sdkComponents.sdkDirectory
val hudAndroidJar = hudSdk.map { it.file("platforms/android-35/android.jar") }
val hudD8 = hudSdk.map { it.file("build-tools/35.0.0/lib/d8.jar") }
val compileHudTools by tasks.registering(JavaCompile::class) {
    source(rootProject.file("tools/PackNavigatorHudExtensions.java"))
    source(listOf("NavigatorHudProfile", "NavigatorMapPatcher", "WazeRenderPatch",
        "GmapsHudTransform", "WazeHudTransform", "WazeScenePatch").map {
        layout.projectDirectory.file("src/main/java/com/bydhud/app/$it.java")
    })
    classpath = hudToolsClasspath
    javaCompiler.set(hudCompiler)
    destinationDirectory.set(layout.buildDirectory.dir("hud-extensions/tools"))
    options.encoding = "UTF-8"
}
val hudHost = providers.systemProperty("os.name").map {
    when {
        it.startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
        it.startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
        it.startsWith("Linux", ignoreCase = true) -> "linux-x86_64"
        else -> throw GradleException("Unsupported Android NDK host: $it")
    }
}
val hudNdk = hudSdk.map { it.dir("ndk/${android.ndkVersion}") }
val hudClang = hudNdk.zip(hudHost) { ndk, host ->
    ndk.file("toolchains/llvm/prebuilt/$host/bin/clang++" + if (host.startsWith("windows")) ".exe" else "")
}
val hudNative = layout.buildDirectory.file("hud-extensions/native/libbydhud_hudmap.so")
val compileWazeHudNative by tasks.registering(Exec::class) {
    val source = hudSources.file("waze/waze_hud_style.cpp")
    inputs.file(source)
    inputs.file(hudNdk.map { it.file("source.properties") })
    inputs.file(hudClang)
    inputs.property("host", hudHost)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(hudNative)
    doFirst {
        val output = hudNative.get().asFile
        output.parentFile.mkdirs()
        temporaryDir.mkdirs()
        environment("TMP", temporaryDir.absolutePath)
        environment("TEMP", temporaryDir.absolutePath)
        commandLine(hudClang.get().asFile, "--target=aarch64-linux-android26", "-std=c++17",
            "-O2", "-shared", "-fPIC", "-fvisibility=hidden", "-static-libstdc++",
            "-fno-omit-frame-pointer", "-Wall", "-Wextra", "-Werror",
            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
            "-Wl,-Bsymbolic", "-Wl,--exclude-libs,ALL", source.asFile, "-o", output, "-ldl")
    }
}
for (family in listOf("waze", "gmaps")) {
    val title = family.replaceFirstChar { it.uppercaseChar() }
    val extensionSources = fileTree(hudSources.dir(family)) { include("**/*.java") }
    val sharedSources = fileTree("src/main/java/com/bydhud/mapcapture") { include("*.java") }
    val compileExtension = tasks.register<JavaCompile>("compile${title}HudExtension") {
        // A navigator-specific implementation replaces a shared class with the same filename.
        source(provider {
            val overrides = extensionSources.files.map { it.name }.toSet()
            sharedSources.files.filter { it.name !in overrides } + extensionSources.files
        })
        classpath = files(hudAndroidJar)
        javaCompiler.set(hudCompiler)
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
        destinationDirectory.set(layout.buildDirectory.dir("hud-extensions/$family/classes"))
        options.encoding = "UTF-8"
        options.debugOptions.debugLevel = "source,lines"
    }
    val extensionJar = tasks.register<Jar>("jar${title}HudExtension") {
        from(compileExtension.flatMap { it.destinationDirectory })
        archiveFileName.set("extension.jar")
        destinationDirectory.set(layout.buildDirectory.dir("hud-extensions/$family"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    val dexDirectory = layout.buildDirectory.dir("hud-extensions/$family/dex")
    val dexExtension = tasks.register<JavaExec>("dex${title}HudExtension") {
        classpath = files(hudD8)
        javaLauncher.set(hudLauncher)
        mainClass.set("com.android.tools.r8.D8")
        inputs.file(extensionJar.flatMap { it.archiveFile })
        inputs.file(hudAndroidJar)
        outputs.dir(dexDirectory)
        doFirst {
            dexDirectory.get().asFile.mkdirs()
            setArgs(listOf("--min-api", "28", "--lib", hudAndroidJar.get().asFile.absolutePath,
                "--output", dexDirectory.get().asFile.absolutePath,
                extensionJar.get().archiveFile.get().asFile.absolutePath))
        }
    }
    androidComponents.onVariants { variant ->
        val variantTitle = variant.name.replaceFirstChar { it.uppercaseChar() }
        val packExtension = tasks.register<HudResources>("generate${title}${variantTitle}HudResources") {
            dependsOn(dexExtension)
            if (family == "waze") dependsOn(compileWazeHudNative)
            classpath = hudToolsClasspath + files(compileHudTools.flatMap { it.destinationDirectory })
            javaLauncher.set(hudLauncher)
            mainClass.set("com.bydhud.app.PackNavigatorHudExtensions")
            val guards = hudSources.file("$family/guards.properties")
            inputs.file(guards)
            inputs.dir(dexDirectory)
            inputs.property("family", family)
            if (family == "waze") inputs.file(hudNative)
            doFirst {
                setArgs(listOf(family, guards.asFile.absolutePath,
                    dexDirectory.get().file("classes.dex").asFile.absolutePath,
                    outputDirectory.get().file("navigator-hud/$family.zip").asFile.absolutePath) +
                    if (family == "waze") listOf(hudNative.get().asFile.absolutePath) else emptyList())
            }
        }
        requireNotNull(variant.sources.resources).addGeneratedSourceDirectory(packExtension, HudResources::outputDirectory)
    }
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
