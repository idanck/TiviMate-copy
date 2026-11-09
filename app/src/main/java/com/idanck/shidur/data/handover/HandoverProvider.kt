package com.idanck.shidur.data.handover

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.idanck.shidur.BuildConfig
import com.idanck.shidur.BuildInfo
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.prefs.SecureStore
import java.io.File
import java.io.FileNotFoundException

/**
 * The bridge's data, for the new app only (ADR-060).
 *
 * Enabled only in the bridge (`bridgeProvider` in `app/build.gradle.kts`). The manifest guards it
 * with a `signature` permission both packages declare, so only a package signed with this app's
 * key can reach it; every entry point here checks again, by package name and signature
 * ([HandoverAccess]), since `call()` is not covered by the manifest's permission on every
 * Android version.
 *
 * It serves one file, [Handover.PATH_SNAPSHOT], and takes one call, [Handover.METHOD_IMPORTED].
 * Nothing here deletes anything of the user's: the data goes when the user removes this package.
 */
class HandoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun requireCaller(): Context {
        val context = context ?: throw SecurityException("no context")
        if (!Handover.isBridge) throw SecurityException("not the bridge")
        val allowed = HandoverAccess.allows(
            caller = callingPackage,
            ownPackage = context.packageName,
            holdsPermission = context.checkCallingPermission(BuildConfig.MIGRATION_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
            signatureMatches = { caller ->
                context.packageManager.checkSignatures(caller, context.packageName) == PackageManager.SIGNATURE_MATCH
            },
        )
        if (!allowed) throw SecurityException("only the new Shidur may read this")
        return context
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireCaller()
        if (mode != "r" || uri.lastPathSegment != Handover.PATH_SNAPSHOT) throw FileNotFoundException(uri.toString())
        ServiceLocator.init(context)
        val dir = File(context.cacheDir, "handover-out").apply { mkdirs() }
        // Anything an earlier request left; a descriptor already handed out keeps its file.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "snapshot-${System.currentTimeMillis()}.db")
        try {
            // Opened, so the file exists and holds the current schema.
            ServiceLocator.database.writableDatabase
            HandoverSnapshot.write(
                live = context.getDatabasePath(Database.NAME),
                prefs = ServiceLocator.settings.exportForHandover(),
                decrypt = SecureStore::decrypt,
                target = target,
                sourceVersionCode = BuildInfo.versionCode,
            )
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            throw FileNotFoundException("snapshot failed: ${t.message}")
        } finally {
            // The open descriptor keeps it readable. The name goes at once: the file holds the
            // credentials in the clear.
            target.delete()
            File(target.path + "-journal").delete()
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = requireCaller()
        if (method != Handover.METHOD_IMPORTED) return null
        ServiceLocator.init(context)
        BridgeHandover.markHandedOver()
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
