package com.idanck.shidur.data.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.idanck.shidur.App
import com.idanck.shidur.BuildInfo
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.ui.MainActivity

/**
 * "Shidur was updated — open it" after an in-app update (FR-UPD-09, ADR-062).
 *
 * Replacing the package kills the process, and Android will not let the new one open its own
 * screen from the background (`MY_PACKAGE_REPLACED` is not one of the exemptions), so the app
 * cannot come back by itself. What it can do is say so: [expect] marks the install just before
 * the session is committed, and [onPackageReplaced] — run by the new build — turns that mark into
 * a notification whose tap opens the app.
 *
 * Only an update this app installed itself, and only shortly after it asked for one: a sideload
 * by hand (`adb install`, a file manager) replaces the package too, and should stay quiet. The
 * mark lives in a file of its own rather than in [com.idanck.shidur.data.prefs.Settings], so it
 * is never backed up, restored or handed over (ADR-060).
 *
 * Temporary by design: once the app is on Google Play, an immediate in-app update there is
 * expected to restart the app by itself. If that is confirmed on a device, this class, its call
 * sites and [App.CHANNEL_UPDATES] go (ADR-062, "Follow-up").
 */
object UpdatedNotice {

    private const val PREFS = "update_notice"
    private const val KEY_VERSION = "expected_version_code"
    private const val KEY_AT = "expected_at_utc"
    private const val KEY_ASKED = "asked_notifications"
    private const val NOTIFICATION_ID = 7_301

    /** How long after the commit a replacement still counts as the one this app asked for. */
    const val WINDOW_MS = UpdateManager.INSTALL_HANDOFF_TIMEOUT_MS

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Marks the install about to be committed.
     *
     * `commit()`, not `apply()`: a self-update kills this process within moments of the session
     * committing, and an asynchronous write can die with it.
     */
    fun expect(context: Context, versionCode: Int, nowUtc: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putInt(KEY_VERSION, versionCode)
            .putLong(KEY_AT, nowUtc)
            .commit()
    }

    /** The install did not happen after all — refused, failed or abandoned. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_VERSION).remove(KEY_AT).apply()
    }

    /**
     * Whether the replacement that just happened is the update this app asked for. Consumes the
     * mark either way, so one update can never announce itself twice.
     */
    fun consume(
        context: Context,
        installedVersionCode: Int = BuildInfo.versionCode,
        nowUtc: Long = System.currentTimeMillis(),
    ): Boolean {
        val prefs = prefs(context)
        val expected = prefs.getInt(KEY_VERSION, -1)
        val at = prefs.getLong(KEY_AT, 0L)
        prefs.edit().remove(KEY_VERSION).remove(KEY_AT).commit()
        if (expected < 0) return false
        if (installedVersionCode < expected) return false
        return nowUtc - at in 0..WINDOW_MS
    }

    /** Called by the new build on `MY_PACKAGE_REPLACED`. */
    fun onPackageReplaced(context: Context) {
        if (consume(context)) post(context)
    }

    /** The app is open, so the invitation to open it has nothing left to say. */
    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Whether pressing "update" should first ask for the notification permission.
     *
     * Asked once only: a refusal is the user's answer, and a second refusal would make the
     * platform stop showing the dialog anyway. Below Android 13 nothing needs asking.
     */
    fun shouldAskPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return !granted && !prefs(context).getBoolean(KEY_ASKED, false)
    }

    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun post(context: Context) {
        val strings = ServiceLocator.locale
        // Explicit, not the launcher's intent: a television's launcher entry carries the
        // LEANBACK category, which `getLaunchIntentForPackage` does not look for.
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification = NotificationCompat.Builder(context, App.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(strings.getString(R.string.update_done_title, BuildInfo.versionName))
            .setContentText(strings.getString(R.string.update_done_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was refused; the update itself is already in place.
        }
    }
}
