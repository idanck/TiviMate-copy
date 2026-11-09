package com.idanck.shidur.data.handover

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.prefs.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The bridge's half of the move (ADR-060): whether the new app has said it holds the data, and the
 * two ways out once it has.
 */
object BridgeHandover {

    private val _handedOver = MutableStateFlow(false)

    /** True once the new app has reported its import; the bridge then only points at it. */
    val handedOver: StateFlow<Boolean> = _handedOver

    fun load(settings: Settings) {
        _handedOver.value = Handover.isBridge && settings.handedOverUtc() > 0
    }

    /** From [HandoverProvider], on the new app's word. */
    fun markHandedOver() {
        ServiceLocator.settings.setHandedOver(System.currentTimeMillis())
        _handedOver.value = true
        // The new app arms the same scheduled recordings; two packages recording one programme
        // would fill the disk twice. The recordings themselves are left as they are.
        runCatching { ServiceLocator.recordings.disarmAll() }
    }

    fun shidurPackage(context: Context): String = Handover.shidurPackage(context.packageName)

    fun isShidurInstalled(context: Context): Boolean = Handover.isInstalled(context, shidurPackage(context))

    /** Opens the new app; false when there is nothing to open. */
    fun openShidur(context: Context): Boolean {
        val pm = context.packageManager
        val target = shidurPackage(context)
        val intent = pm.getLaunchIntentForPackage(target) ?: pm.getLeanbackLaunchIntentForPackage(target)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** The platform's own "remove this app?" for the bridge. The data goes with it, and only then. */
    fun uninstallSelf(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
