package com.idanck.shidur

import android.content.Context
import android.content.pm.PackageManager

/**
 * The build's identity, surfaced in Settings -> About and in Diagnostics.
 *
 * Version name and code come from `version.properties` at the repository root; the git SHA
 * and build stamp are injected by the build so any two installs can be told apart even when
 * the version number has not been bumped.
 */
object BuildInfo {

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE
    val gitSha: String get() = BuildConfig.GIT_SHA
    val buildStamp: String get() = BuildConfig.BUILD_STAMP
    val buildType: String get() = BuildConfig.BUILD_FLAVOUR
    val applicationId: String get() = BuildConfig.APPLICATION_ID

    /** `0.1.0 (1) · a1b2c3d4` — the one-line form shown in the UI. */
    val short: String get() = "$versionName ($versionCode) · $gitSha"

    /** Multi-line form for the About screen and the diagnostic report. */
    fun full(context: Context): String = buildString {
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Build: $buildType · $gitSha")
        appendLine("Built: $buildStamp")
        appendLine("Package: $applicationId")
        appendLine("Installed: ${installedVersion(context)}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /**
     * Reads the version the package manager reports. It should always equal [versionName]; a
     * mismatch means the running process is older than the installed APK, which is exactly the
     * confusion this screen exists to resolve.
     */
    private fun installedVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName} ($code)"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }
}
