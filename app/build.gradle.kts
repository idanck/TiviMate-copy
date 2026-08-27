import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val appVersionCode = (versionProps.getProperty("versionCode") ?: "1").trim().toInt()
val appVersionName = listOf(
    versionProps.getProperty("versionMajor") ?: "0",
    versionProps.getProperty("versionMinor") ?: "0",
    versionProps.getProperty("versionPatch") ?: "0",
).joinToString(".") { it.trim() }

/** Short git SHA, injected by CI via GIT_SHA; falls back to a local `git` call, then "local". */
val gitSha: String = (System.getenv("GIT_SHA")?.takeIf { it.isNotBlank() } ?: runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotBlank() }
}.getOrNull() ?: "local").take(12)

/** Build timestamp; CI passes a stable value so reproducible-ish builds stay comparable. */
val buildStamp: String = System.getenv("BUILD_STAMP")?.takeIf { it.isNotBlank() }
    ?: DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

/** Release keystore, when the environment supplies a readable one. */
val releaseKeystore: File? = System.getenv("RELEASE_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.tivicopy.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tivicopy.player"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // `iw` is Hebrew, and it has to be spelled that way. Android asks its resource table for
        // the language that `ResourcesImpl.adjustLanguageTag` produces, which maps `he` back to
        // the retired `iw` on every release since Lollipop — so `values-iw` is the folder that
        // gets found, and a filter naming only `he` would strip the whole translation out of the
        // APK. `he` stays in the list because this filter is matched against the qualifier as
        // written: if a future build tool starts normalising the folder name the other way, the
        // translation survives either spelling.
        resourceConfigurations += listOf("en", "iw", "he")
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A release keystore is optional. The config is only created when one is actually present,
    // so an empty, unusable signing config never reaches the packaging task; without a keystore
    // the release output is signed with the standard debug key and installs directly.
    signingConfigs {
        releaseKeystore?.let { store ->
            create("release") {
                storeFile = store
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    applicationVariants.all {
        val variant = this
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        buildConfigField("String", "BUILD_FLAVOUR", "\"${variant.buildType.name}\"")
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "shidur-${appVersionName}-${variant.buildType.name}-${gitSha}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            // Most of the ExoPlayer surface in Media3 is annotated @UnstableApi; the whole
            // module opts in once instead of sprinkling @OptIn over every player call site.
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tvprovider)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    // Installs `assets/dexopt/baseline.prof` — which now includes this app's own UI, see
    // `src/main/baseline-prof.txt` — into ART's profile on the first launch after each update,
    // so the next background dexopt compiles the screens ahead of time instead of leaving them
    // to the JIT. It arrives transitively with Compose; named here so the profile does not
    // silently stop being installed if that ever changes (ADR-021).
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
