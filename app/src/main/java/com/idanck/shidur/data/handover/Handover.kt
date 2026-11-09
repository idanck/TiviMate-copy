package com.idanck.shidur.data.handover

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.idanck.shidur.BuildConfig

/**
 * The move from the old package, `com.tivicopy.player`, to `com.idanck.shidur` (ADR-060).
 *
 * Android treats the two as different apps, so the data cannot simply stay where it was. The
 * last build of the old package, the bridge, publishes it through [HandoverProvider], guarded by a
 * signature permission; the new app reads it once on its first start ([HandoverImporter]).
 *
 * Debug builds pair with each other: `com.tivicopy.player.debug` hands over to
 * `com.idanck.shidur.debug`.
 */
object Handover {

    private const val DEBUG_SUFFIX = ".debug"

    /** The URI path of the one file the bridge serves. */
    const val PATH_SNAPSHOT = "snapshot"

    /** A `call()` from the new app: the data is in, the bridge may say so. */
    const val METHOD_IMPORTED = "imported"

    /** Whether this build is the bridge, the old package's last build. */
    val isBridge: Boolean get() = BuildConfig.BRIDGE

    private fun suffix(ownPackage: String): String =
        if (ownPackage.endsWith(DEBUG_SUFFIX)) DEBUG_SUFFIX else ""

    /** The old package, the one this build takes the data from. */
    fun legacyPackage(ownPackage: String): String = BuildConfig.LEGACY_PACKAGE + suffix(ownPackage)

    /** The new package, the one the bridge hands over to and installs. */
    fun shidurPackage(ownPackage: String): String = BuildConfig.SHIDUR_PACKAGE + suffix(ownPackage)

    fun authority(legacyPackage: String): String = "$legacyPackage.handover"

    fun snapshotUri(legacyPackage: String): Uri =
        Uri.parse("content://${authority(legacyPackage)}/$PATH_SNAPSHOT")

    /**
     * Whether [other] is installed and signed with exactly this app's key. The provider's
     * permission already says so; this is checked as well so that a package that only borrows
     * the name is never asked for anything.
     */
    fun isOurs(context: Context, other: String): Boolean = try {
        context.packageManager.getPackageInfo(other, 0)
        context.packageManager.checkSignatures(other, context.packageName) == PackageManager.SIGNATURE_MATCH
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isInstalled(context: Context, other: String): Boolean = try {
        context.packageManager.getPackageInfo(other, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Who the bridge's provider answers (ADR-060). Kept free of Android so the refusal can be tested
 * on its own: the platform's own permission check stands in front of it, and this is the second
 * lock, on the caller's package name and signature.
 */
internal object HandoverAccess {

    /**
     * True only for the new package that pairs with [ownPackage], holding the permission, and
     * signed with the same key.
     */
    fun allows(
        caller: String?,
        ownPackage: String,
        holdsPermission: Boolean,
        signatureMatches: (String) -> Boolean,
    ): Boolean {
        if (caller == null || !holdsPermission) return false
        if (caller != Handover.shidurPackage(ownPackage)) return false
        return signatureMatches(caller)
    }
}
