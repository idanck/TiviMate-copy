package com.idanck.shidur.ui.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.update.UpdateManifest
import com.idanck.shidur.data.update.UpdatedNotice
import kotlinx.coroutines.launch

/**
 * What "update" does, from the dialog and from Settings alike (FR-UPD-04, FR-UPD-09).
 *
 * The first time, on Android 13 and later, it asks for the notification permission before the
 * download starts: the app closes when the update installs, and the notification that it was
 * updated is how the user gets back in. That is the moment the reason is plain, which is why the
 * app does not ask on its first launch. Granted or refused, the update goes ahead — the
 * notification is a courtesy, not a condition.
 */
@Composable
fun rememberUpdateStarter(): (UpdateManifest) -> Unit {
    val context = LocalContext.current
    val updates = ServiceLocator.updates
    var waiting by remember { mutableStateOf<UpdateManifest?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val manifest = waiting
        waiting = null
        if (manifest != null) ServiceLocator.appScope.launch { updates.downloadAndInstall(manifest) }
    }
    return { manifest ->
        if (UpdatedNotice.shouldAskPermission(context)) {
            UpdatedNotice.markPermissionAsked(context)
            waiting = manifest
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ServiceLocator.appScope.launch { updates.downloadAndInstall(manifest) }
        }
    }
}
