package com.tivicopy.player.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tivicopy.player.ServiceLocator

/** Starts the recorder when an exact alarm fires. */
class RecordingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(RecordingService.EXTRA_RECORDING_ID, 0L)
        if (id == 0L) return
        ServiceLocator.init(context)
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RECORDING_ID, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.tivicopy.player.record.ALARM"
    }
}

/**
 * Re-arms every pending schedule after a reboot (FR-REC-15) and, when the user asked for it,
 * brings the app back up on a TV that has just powered on (FR-SET-02).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        ServiceLocator.init(context)
        ServiceLocator.recordings.rearmAll()

        if (ServiceLocator.settings.state.value.autoStartOnBoot) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch?.let(context::startActivity)
        }
    }
}
