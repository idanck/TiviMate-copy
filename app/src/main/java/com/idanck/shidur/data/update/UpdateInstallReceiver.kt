package com.idanck.shidur.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.AppError

/**
 * Where the platform installer reports back to (FR-UPD-04).
 *
 * The session commits asynchronously, so the outcome — including the confirmation dialog the
 * platform may want to show first — arrives here rather than at the call site.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        ServiceLocator.init(context)

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The user pressed "update" a moment ago, so the app is foreground and this
                // activity start is permitted; the flag is needed because a receiver has no task.
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    // The platform asked for confirmation and then described none. Reporting it
                    // is the only way out: the state that says an install is under way would
                    // otherwise stand until the process ends, suppressing every later check.
                    ServiceLocator.updates.onInstallFailed(
                        AppError(AppError.Kind.Unknown, "no confirmation intent"),
                    )
                } else {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { ServiceLocator.updates.onInstallFailed(AppError.of(it)) }
                }
            }

            // The process is replaced on success; nothing here would live long enough to run.
            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> ServiceLocator.updates.onInstallFailed(
                AppError(kindOf(status), intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun kindOf(status: Int): AppError.Kind = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> AppError.Kind.Cancelled
        PackageInstaller.STATUS_FAILURE_STORAGE -> AppError.Kind.InsufficientStorage
        PackageInstaller.STATUS_FAILURE_BLOCKED -> AppError.Kind.Forbidden
        // The platform's own "signing certificate doesn't match the installed app" status —
        // verifySignature() catches this ahead of time, but a race or an older-API path could
        // still reach the installer with it, and it deserves the same actionable message.
        PackageInstaller.STATUS_FAILURE_CONFLICT -> AppError.Kind.SignatureMismatch
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_INVALID,
        -> AppError.Kind.UnsupportedFormat
        else -> AppError.Kind.Unknown
    }

    companion object {
        const val ACTION_STATUS = "com.idanck.shidur.UPDATE_STATUS"
    }
}
