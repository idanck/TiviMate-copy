package com.idanck.shidur.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.youtube.YtChannelHit
import com.idanck.shidur.data.youtube.YtLinkStatus
import com.idanck.shidur.ui.common.DialogButton
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioDialog
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.StudioSheet
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.launch

/**
 * The connected YouTube account (ADR-069; `canvas/TvYouTubeAccount`, `PYouTubeAccount`,
 * `TvSettingsAccount`, `PSettingsAccount`): who is connected, whether likes reach YouTube, and
 * connecting again, replacing it or disconnecting. The connection is the Shidur account's, kept on
 * the server, so every device of the account sees the same one.
 */

/** The slate behind a channel's initial while its picture loads, or when it has none. */
private val AvatarSlate = Color(0xFF4A5878)
private val AvatarInk = Color(0xFFF4F6FB)

/** The connected channel's picture; its initial on a slate disc without one; the person glyph with no channel. */
@Composable
internal fun YouTubeAvatar(channel: YtChannelHit?, size: Dp, modifier: Modifier = Modifier) {
    val initial = channel?.title?.trim()?.firstOrNull()?.uppercase()
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(if (channel != null) AvatarSlate else StudioTheme.colors.card),
        contentAlignment = Alignment.Center,
    ) {
        when {
            initial != null -> Text(
                initial,
                style = StudioTheme.type.body.copy(fontWeight = FontWeight.ExtraBold),
                color = AvatarInk,
            )
            else -> Icon(
                imageVector = StudioIcons.Account,
                contentDescription = null,
                tint = if (channel != null) AvatarInk else StudioTheme.colors.ink2,
                modifier = Modifier.size(size * 0.55f),
            )
        }
        channel?.thumb?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** The name to show for the connection: the channel's, or "YouTube account" when it has none. */
@Composable
internal fun youTubeAccountName(link: YtLinkStatus): String =
    link.channel?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yt_account_title)

/**
 * The account window: a dialog on a television, a sheet on a phone. A connection without the
 * scope for likes (made before ADR-069) leads with "חיבור מחדש"; otherwise the remote starts on
 * "סגירה". [onConnectAgain] runs the shell's own way of connecting — the new account replaces the
 * old — and [onDisconnected] follows a disconnection that went through.
 */
@Composable
internal fun YouTubeAccountWindow(
    link: YtLinkStatus,
    onDismiss: () -> Unit,
    onConnectAgain: () -> Unit,
    onDisconnected: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        DisconnectYouTubeDialog(onDismiss = { confirm = false }, onDone = onDisconnected)
        return
    }
    val readOnly = !link.canRate
    val title = stringResource(R.string.yt_account_title)
    val buttons: @Composable () -> Unit = {
        if (readOnly) {
            DialogButton(
                label = stringResource(R.string.yt_account_reconnect),
                onClick = onConnectAgain,
                variant = StudioButtonVariant.PRIMARY,
                icon = StudioIcons.Link,
                initialFocus = true,
            )
            DialogButton(label = stringResource(R.string.yt_account_disconnect), onClick = { confirm = true }, variant = StudioButtonVariant.DANGER)
            DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss)
        } else {
            DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss, variant = StudioButtonVariant.SECONDARY, initialFocus = true)
            DialogButton(label = stringResource(R.string.yt_account_switch), onClick = onConnectAgain, variant = StudioButtonVariant.SECONDARY, icon = StudioIcons.Swap)
            DialogButton(label = stringResource(R.string.yt_account_disconnect), onClick = { confirm = true }, variant = StudioButtonVariant.DANGER)
        }
    }
    if (StudioTheme.television) {
        StudioDialog(onDismissRequest = onDismiss, title = title, buttons = buttons) { AccountBody(link) }
    } else {
        StudioSheet(onDismissRequest = onDismiss, title = title, buttons = buttons) { AccountBody(link) }
    }
}

@Composable
private fun AccountBody(link: YtLinkStatus) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    val readOnly = !link.canRate
    val statusColour = if (readOnly) studio.current else studio.ok
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 14.dp)) {
        YouTubeAvatar(link.channel, size = if (tv) 60.dp else 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.yt_account_connected_as), style = type.caption, color = studio.ink2)
            Text(
                youTubeAccountName(link),
                style = if (tv) type.h1 else type.titleSmall,
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(if (tv) 6.dp else 8.dp).clip(CircleShape).background(statusColour))
                Text(
                    stringResource(if (readOnly) R.string.yt_account_status_readonly else R.string.yt_account_status_ok),
                    style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColour,
                )
            }
        }
    }
    Text(stringResource(if (readOnly) R.string.yt_account_body_readonly else R.string.yt_account_body_ok), style = type.body, color = studio.ink2)
    Text(stringResource(R.string.yt_account_all_devices), style = type.meta, color = studio.ink3)
}

/**
 * Disconnecting asks first, with the remote on "ביטול". It revokes Shidur's access at Google and
 * forgets the token on the server (`ytUnlink`), for every device of the Shidur account.
 */
@Composable
internal fun DisconnectYouTubeDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val repo = ServiceLocator.youtube
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    StudioDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.yt_disconnect_title),
        icon = StudioIcons.Alert,
        danger = true,
        buttons = {
            DialogButton(
                label = stringResource(if (busy) R.string.yt_disconnecting else R.string.yt_account_disconnect),
                variant = StudioButtonVariant.DANGER,
                enabled = !busy,
                onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        repo.unlink()
                            .onSuccess { onDone() }
                            .onFailure { failed = true }
                        busy = false
                    }
                },
            )
            DialogButton(label = stringResource(R.string.action_cancel), onClick = onDismiss, enabled = !busy, initialFocus = true)
        },
    ) {
        Text(stringResource(R.string.yt_disconnect_body))
        Text(stringResource(R.string.yt_disconnect_note), style = StudioTheme.type.meta, color = StudioTheme.colors.ink3)
        if (failed) Text(stringResource(R.string.yt_disconnect_failed), style = StudioTheme.type.meta, color = StudioTheme.colors.error)
    }
}
