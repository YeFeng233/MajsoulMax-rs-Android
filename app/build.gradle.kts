import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** ABIs we ship. Keep in sync with scripts/build-rust.sh and the CI workflow. */
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a")

val buildRust = (project.findProperty("majsoulmax.buildRust") as String?)?.toBoolean() ?: true

android {
    namespace = "moe.majsoulmax.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "moe.majsoulmax.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += shippedAbis
        }

        externalNativeBuild {
            cmake {
                // Only our own JNI shim is compiled here; hev-socks5-tunnel is
                // linked in as a prebuilt produced by scripts/build-tun2socks.sh.
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // A throwaway key so that `assembleRelease` produces an installable APK in
        // CI. Override with your own keystore for anything you actually publish.
        create("ci") {
            val ks = rootProject.file("ci-keystore.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD") ?: "majsoulmax"
                keyAlias = System.getenv("CI_KEY_ALIAS") ?: "ci"
                keyPassword = System.getenv("CI_KEY_PASSWORD") ?: "majsoulmax"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (rootProject.file("ci-keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The mihomo kernel is shipped as libmihomo.so and executed as a
            // child process, so it MUST be extracted to disk at install time.
            useLegacyPackaging = true
            // It is a Go PIE executable, not a library: running llvm-strip over
            // it is at best pointless and at worst corrupts the binary.
            keepDebugSymbols += setOf("**/libmihomo.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCY_LICENSES",
            )
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
            // Payloads produced out-of-band by scripts/fetch-mihomo.sh and
            // scripts/build-tun2socks.sh. The generated directory that Gradle
            // itself produces is registered further down, as a task provider.
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.splashscreen)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------
// Generated inputs.
//
// Both of the tasks below deliberately write into build/generated/... rather than
// into src/main/..., and are registered as *task-backed* source directories. That
// is what gives AGP's merge tasks a real dependency on them; a plain
// preBuild.dependsOn does not, because mergeAssets/mergeJniLibFolders do not run
// after preBuild, and Gradle 8 fails the build when a task consumes another
// task's output without a declared path to it.
// ---------------------------------------------------------------------------

val upstreamDir = rootProject.file("external/MajsoulMax-rs")

val stagedAssetsDir = layout.buildDirectory.dir("generated/upstreamAssets")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

val stageUpstreamAssets by tasks.registering(Copy::class) {
    description = "Copies upstream liqi_config + CA certificate into app assets."
    group = "majsoulmax"

    onlyIf {
        val present = upstreamDir.resolve("liqi_config/settings.json").exists()
        if (!present) {
            logger.warn(
                "MajsoulMax-rs submodule not initialised - run " +
                    "`git submodule update --init --recursive`. Assets will be missing.",
            )
        }
        present
    }

    into(stagedAssetsDir)

    from(upstreamDir.resolve("liqi_config")) {
        into("liqi_config")
        // lqc.lqbin and liqi.json are large; both are required at runtime.
        include("settings.json", "settings.mod.json", "liqi.json", "lqc.lqbin", "liqi.desc")
    }
    from(upstreamDir.resolve("src/ca")) {
        into("ca")
        include("hudsucker.cer")
    }
}

val cargoBuild by tasks.registering(Exec::class) {
    description = "Cross-compiles the Rust MITM core (libmajsoulmax.so) for every shipped ABI."
    group = "majsoulmax"

    val script = rootProject.file("scripts/build-rust.sh")
    workingDir = rootProject.projectDir

    if (OperatingSystem.current().isWindows) {
        commandLine("bash", script.absolutePath)
    } else {
        commandLine(script.absolutePath)
    }

    environment("JNI_LIBS_DIR", generatedJniLibsDir.get().asFile.absolutePath)
    environment("TARGET_ABIS", shippedAbis.joinToString(" "))

    // Narrowly scoped: `rust/` as a whole would include majsoul-jni/target, which
    // makes the task permanently out of date and fingerprints gigabytes.
    inputs.dir(rootProject.file("rust/majsoul-jni/src"))
    inputs.file(rootProject.file("rust/majsoul-jni/Cargo.toml"))
    inputs.file(script)
    if (upstreamDir.resolve("src").isDirectory) {
        inputs.dir(upstreamDir.resolve("src"))
    }
    outputs.dir(generatedJniLibsDir)

    onlyIf { buildRust }
}

android.sourceSets.getByName("main") {
    assets.srcDir(stageUpstreamAssets)
    jniLibs.srcDir(cargoBuild)
}

/**
 * Warns about missing native payloads at build time rather than letting the app
 * install and then fail at `System.loadLibrary`. Deliberately a warning: the UI is
 * meant to be buildable without a Rust or NDK toolchain, and each missing payload
 * also surfaces as a named pre-flight check on the Home screen.
 */
val verifyNativeLibs by tasks.registering {
    description = "Checks that every native payload the app needs is present."
    group = "majsoulmax"

    // Otherwise this can run before the core is built and warn about nothing.
    mustRunAfter(cargoBuild)

    val checkedDirs = listOf(
        layout.projectDirectory.dir("src/main/jniLibs").asFile,
        generatedJniLibsDir.get().asFile,
    )
    val abis = shippedAbis

    doLast {
        val missing = abis.flatMap { abi ->
            listOf("libmajsoulmax.so", "libmihomo.so", "libhev-socks5-tunnel.so")
                .filter { lib -> checkedDirs.none { it.resolve("$abi/$lib").exists() } }
                .map { "$abi/$it" }
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                buildString {
                    appendLine("=".repeat(72))
                    appendLine("Missing native payloads:")
                    missing.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("  libmajsoulmax.so        -> ./scripts/build-rust.sh")
                    appendLine("  libmihomo.so            -> ./scripts/fetch-mihomo.sh")
                    appendLine("  libhev-socks5-tunnel.so -> ./scripts/build-tun2socks.sh")
                    appendLine("The APK will build, but the tunnel will refuse to start.")
                    appendLine("=".repeat(72))
                },
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stageUpstreamAssets, cargoBuild, verifyNativeLibs)
}
