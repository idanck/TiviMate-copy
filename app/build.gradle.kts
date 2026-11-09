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

/**
 * The bridge (ADR-060): the last build of the old package, `com.tivicopy.player`, built from this
 * same code with `-Pshidur.bridge=true`. It updates the installs that still carry the old package
 * through `latest.json`, offers to install the new app and hands it the data. Its versionCode is
 * its own, `bridgeVersionCode`, and is only ever raised to publish a fixed bridge.
 */
val isBridge = (findProperty("shidur.bridge") as String?)?.trim()?.toBoolean() == true
val bridgeVersionCode = (versionProps.getProperty("bridgeVersionCode") ?: "0").trim().toInt()

/** The package every install of this app had until ADR-060, and the bridge still has. */
val legacyPackage = "com.tivicopy.player"
val shidurPackage = "com.idanck.shidur"

/** Where each package reads its updates (ADR-012, ADR-060). `latest.json` holds the bridge. */
val updatesHost = "https://updates.idanck.com"
val legacyManifestUrl = "$updatesHost/latest.json"
val shidurManifestUrl = "$updatesHost/shidur/latest.json"

/**
 * The signature permission guarding the bridge's data (ADR-060), declared by both packages. Debug
 * builds carry another name: they are signed with each machine's debug key, and two packages with
 * different keys may not declare the same permission.
 */
val migrationPermission = "$shidurPackage.permission.MIGRATE"

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

/**
 * One Android client of the Firebase project (ADR-059), as `google-services.json` describes it.
 * The Google Services Gradle plugin is not used: it fails a build that has no such file, and this
 * repository is public, so the file never enters it. CI writes it from a secret and names it in
 * FIREBASE_ANDROID_CONFIG_PATH; a local build may drop one at `app/google-services.json`, which
 * `.gitignore` keeps out. Without it every field below is empty and the app starts with no
 * accounts at all.
 */
data class FirebaseClient(
    val apiKey: String,
    val appId: String,
    val projectId: String,
    val senderId: String,
    val storageBucket: String,
    val webClientId: String,
)

val firebaseConfigFile: File? = (
    System.getenv("FIREBASE_ANDROID_CONFIG_PATH")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: file("google-services.json")
    ).takeIf { it.isFile }

/** The client registered for [packageName] in [config], or null when there is none. */
fun firebaseClientFor(config: File?, packageName: String): FirebaseClient? {
    if (config == null) return null
    return runCatching {
        val root = groovy.json.JsonSlurper().parse(config) as Map<*, *>
        val info = root["project_info"] as Map<*, *>
        val client = (root["client"] as List<*>).map { it as Map<*, *> }.firstOrNull { c ->
            val androidInfo = (c["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>
            androidInfo["package_name"] == packageName
        } ?: return@runCatching null
        // The web client (type 3) is the audience Google signs the ID token for; Firebase Auth
        // accepts only a token issued to it.
        val services = client["services"] as? Map<*, *>
        val invite = services?.get("appinvite_service") as? Map<*, *>
        val oauth = (client["oauth_client"] as? List<*>).orEmpty() +
            (invite?.get("other_platform_oauth_client") as? List<*>).orEmpty()
        val webClient = oauth.map { it as Map<*, *> }
            .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
            ?.get("client_id") as? String
        FirebaseClient(
            apiKey = ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"] as String,
            appId = (client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as String,
            projectId = info["project_id"] as String,
            senderId = info["project_number"]?.toString().orEmpty(),
            storageBucket = (info["storage_bucket"] as? String).orEmpty(),
            webClientId = webClient.orEmpty(),
        )
    }.onFailure { System.err.println("w: google-services.json could not be read: ${it.message}") }.getOrNull()
}

/** Release keystore, when the environment supplies a readable one. */
val releaseKeystore: File? = System.getenv("RELEASE_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.idanck.shidur"
    compileSdk = 35

    defaultConfig {
        applicationId = if (isBridge) legacyPackage else shidurPackage
        minSdk = 23
        targetSdk = 35
        versionCode = if (isBridge) bridgeVersionCode else appVersionCode
        versionName = if (isBridge) "$appVersionName-bridge" else appVersionName

        // ADR-060. The bridge reads `latest.json`, which holds the bridge alone from now on, so a
        // fixed bridge can still reach it; the new app reads a manifest of its own.
        buildConfigField("boolean", "BRIDGE", isBridge.toString())
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${if (isBridge) legacyManifestUrl else shidurManifestUrl}\"")
        buildConfigField("String", "SHIDUR_MANIFEST_URL", "\"$shidurManifestUrl\"")
        buildConfigField("String", "LEGACY_PACKAGE", "\"$legacyPackage\"")
        buildConfigField("String", "SHIDUR_PACKAGE", "\"$shidurPackage\"")
        manifestPlaceholders["bridgeProvider"] = isBridge.toString()

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
            buildConfigField("String", "MIGRATION_PERMISSION", "\"$migrationPermission.DEBUG\"")
            manifestPlaceholders["migrationPermission"] = "$migrationPermission.DEBUG"
        }
        getByName("release") {
            buildConfigField("String", "MIGRATION_PERMISSION", "\"$migrationPermission\"")
            manifestPlaceholders["migrationPermission"] = migrationPermission
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
        // ADR-059: the debug build is `.debug` and is a client of its own in the Firebase project.
        val firebase = firebaseClientFor(firebaseConfigFile, variant.applicationId)
        if (firebase == null && firebaseConfigFile != null) {
            System.err.println("w: no Firebase client for ${variant.applicationId}; accounts are off in this build")
        }
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase?.apiKey.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase?.appId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase?.projectId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebase?.senderId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${firebase?.storageBucket.orEmpty()}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${firebase?.webClientId.orEmpty()}\"")
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val name = if (isBridge) "shidur-bridge" else "shidur"
            out.outputFileName = "$name-${if (isBridge) "$appVersionName-bridge" else appVersionName}-${variant.buildType.name}-${gitSha}.apk"
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

    // Accounts (ADR-059).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    // The QR code a television shows for signing in from a phone: the encoder only, drawn in
    // Compose (ui/account/QrCode.kt).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
