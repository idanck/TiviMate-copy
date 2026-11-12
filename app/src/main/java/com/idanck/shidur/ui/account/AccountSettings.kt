package com.idanck.shidur.ui.account

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.account.AccountState
import com.idanck.shidur.ui.common.DialogButton
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioDialog
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.mobile.rememberPhoneYouTubeConnect
import com.idanck.shidur.ui.tv.ConnectYouTubeDialog
import com.idanck.shidur.ui.settings.SettingsRow
import com.idanck.shidur.ui.settings.SettingsSectionTitle
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.launch

/**
 * Settings → Account (ADR-059): who is signed in, signing out, and deleting the account — the
 * in-app route Google Play requires beside the web page.
 */
@Composable
fun AccountSection() {
    val account = ServiceLocator.account
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    SettingsSectionTitle(stringResource(R.string.settings_account), topDivider = false)

    when (val current = state) {
        AccountState.Unavailable -> SettingsRow(
            title = stringResource(R.string.account_unavailable),
            subtitle = stringResource(R.string.account_unavailable_sub),
        )

        AccountState.SignedOut -> {
            SettingsRow(
                title = stringResource(R.string.account_signed_out),
                subtitle = stringResource(R.string.account_signed_out_sub),
            )
            if (StudioTheme.television) TvAccountSignIn() else GoogleSignInBlock(initialFocus = false)
            SettingsSectionTitle(stringResource(R.string.nav_youtube))
            Text(
                text = stringResource(R.string.yt_settings_signed_out),
                style = StudioTheme.type.meta,
                color = StudioTheme.colors.ink2,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        is AccountState.SignedIn -> {
            SettingsRow(
                title = current.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_signed_in),
                subtitle = current.email ?: stringResource(R.string.account_signed_in_paired),
            )
            SettingsRow(
                title = stringResource(R.string.account_sign_out),
                subtitle = stringResource(R.string.account_sign_out_sub),
                onClick = { scope.launch { account.signOut() } },
            )
            SettingsRow(
                title = stringResource(if (deleting) R.string.account_deleting else R.string.account_delete),
                subtitle = stringResource(R.string.account_delete_sub),
                danger = true,
                onClick = { if (!deleting) confirmDelete = true },
            )
            YouTubeAccountRows()
        }
    }

    problem?.let {
        Text(
            text = stringResource(it),
            style = StudioTheme.type.meta,
            color = StudioTheme.colors.error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    if (confirmDelete) {
        StudioDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.account_delete_confirm_title),
            danger = true,
            buttons = {
                DialogButton(
                    label = stringResource(R.string.account_delete),
                    variant = StudioButtonVariant.DANGER,
                    onClick = {
                        confirmDelete = false
                        deleting = true
                        problem = null
                        scope.launch {
                            account.deleteAccount().onFailure { problem = R.string.account_error_delete }
                            deleting = false
                        }
                    },
                )
                DialogButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = { confirmDelete = false },
                    initialFocus = true,
                )
            },
        ) {
            Text(
                text = stringResource(R.string.account_delete_confirm_body),
                style = StudioTheme.type.body,
                color = StudioTheme.colors.ink2,
            )
        }
    }
}

/**
 * Settings › חשבון › YouTube (ADR-069; `canvas/TvSettingsAccount`, `PSettingsAccount`): the
 * connected channel and whether likes reach YouTube, then connecting again or replacing it, and
 * disconnecting. With none connected, the way to connect — the code on a television, Google's
 * account picker on a phone.
 */
@Composable
private fun YouTubeAccountRows() {
    val repo = ServiceLocator.youtube
    val link by repo.link.collectAsState()
    val scope = rememberCoroutineScope()
    val tv = StudioTheme.television
    var connecting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.refreshLink() }
    val phoneConnect = if (tv) null else rememberPhoneYouTubeConnect { repo.refreshSubscriptions(signedIn = true, phone = true) }
    val connect: () -> Unit = { if (tv) connecting = true else phoneConnect?.start?.invoke() }

    SettingsSectionTitle(stringResource(R.string.nav_youtube))
    val current = link ?: return
    if (current.linked) {
        val readOnly = !current.canRate
        SettingsRow(
            title = youTubeAccountName(current),
            subtitle = stringResource(if (readOnly) R.string.yt_settings_sub_readonly else R.string.yt_settings_sub_ok),
            icon = StudioIcons.Account,
            value = stringResource(if (readOnly) R.string.yt_settings_value_readonly else R.string.yt_settings_value_ok),
        )
        SettingsRow(
            title = stringResource(if (readOnly) R.string.yt_account_reconnect else R.string.yt_account_switch),
            subtitle = stringResource(if (readOnly) R.string.yt_settings_reconnect_sub else R.string.yt_settings_switch_sub),
            icon = if (readOnly) StudioIcons.Link else StudioIcons.Swap,
            chevron = true,
            onClick = connect,
        )
        SettingsRow(
            title = stringResource(R.string.yt_account_disconnect),
            subtitle = stringResource(R.string.yt_settings_disconnect_sub),
            danger = true,
            onClick = { confirm = true },
        )
    } else if (if (tv) current.tvReady else current.phoneReady) {
        SettingsRow(
            title = stringResource(R.string.yt_settings_connect),
            subtitle = stringResource(R.string.yt_settings_connect_sub),
            icon = StudioIcons.Plus,
            chevron = true,
            onClick = connect,
        )
    } else {
        SettingsRow(title = stringResource(R.string.yt_settings_unavailable))
    }
    phoneConnect?.failed?.let {
        Text(it, style = StudioTheme.type.meta, color = StudioTheme.colors.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }

    if (connecting) {
        ConnectYouTubeDialog(
            repo = repo,
            onDismiss = { connecting = false },
            onLinked = {
                connecting = false
                scope.launch { repo.refreshLink() }
            },
        )
    }
    if (confirm) {
        DisconnectYouTubeDialog(
            onDismiss = { confirm = false },
            onDone = {
                confirm = false
                scope.launch { repo.refreshLink() }
            },
        )
    }
}
