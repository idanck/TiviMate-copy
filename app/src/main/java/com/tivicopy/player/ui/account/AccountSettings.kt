package com.tivicopy.player.ui.account

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.account.AccountState
import com.tivicopy.player.ui.common.DialogButton
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioDialog
import com.tivicopy.player.ui.settings.SettingsRow
import com.tivicopy.player.ui.settings.SettingsSectionTitle
import com.tivicopy.player.ui.theme.StudioTheme
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
            GoogleSignInBlock(initialFocus = false)
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
