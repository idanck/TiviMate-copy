package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * The floating plate the interface sits on, and the one menu drawn on it.
 *
 * ADR-023 defined this plate for the television shell and gave it to four surfaces: the transport
 * bar, the channel filmstrip, the navigation capsule (since replaced by ADR-027's side menu, which
 * has a drawer surface of its own) and the playback menu. Every *other* menu in the app — the
 * channel menu and its six sheets, the programme details, the four update prompts — was a stock
 * `AlertDialog`, which is an opaque `surfaceContainerHigh` plate with no edge that ignores the
 * viewer's overlay-opacity setting outright. Which is the exact complaint ADR-023 opened with
 * about the playback menu, left standing in the twelve places it was not looking.
 *
 * A viewer long-pressing a channel and a viewer pressing OK on the transport bar are one press
 * apart, and they were looking at two different apps.
 *
 * So the plate lives here, in `common`, where both shells and every menu can draw it, and
 * `ui/tv/TvSurfaces.kt` keeps the ten-foot names that point at it.
 */

/*
 * The hairline around a panel (Studio `--st-hairline`).
 *
 * Load-bearing rather than decorative. A translucent plate over a dark scene has an edge; over a
 * bright one it does not, and the interface appears to bleed into the picture. A single low-alpha
 * white line reads on both without ever becoming a frame.
 */

/**
 * The floor a menu keeps under the viewer's overlay-opacity setting.
 *
 * A menu is several lines of text read one at a time — track names, channel actions, release
 * notes. At the setting's own minimum of 0.3 that is illegible over moving video, and turning
 * overlays down is not a request for that.
 */
const val MENU_MIN_OPACITY = 0.92f

/**
 * A floating plate: translucent, rounded, hairlined.
 *
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18). Every surface drawn over video
 *   obeys it, which is the whole point of the setting.
 */
@Composable
fun Modifier.panelSurface(
    opacity: Float,
    shape: Shape = RoundedCornerShape(LocalTvMetrics.current.panelCorner),
): Modifier = this
    .clip(shape)
    .background(MaterialTheme.colorScheme.surface.copy(alpha = opacity))
    .border(
        width = 1.dp,
        color = StudioTheme.colors.hairline,
        shape = shape,
    )

/**
 * Every menu and prompt in the app: Studio's `Dialog` (`Dialog.dc.html`, `PDialog.dc.html`).
 *
 * ADR-025 first gave every menu this plate by restyling Material's `AlertDialog`. Studio stage 7
 * went the rest of the way and draws the canvas's dialog itself, on Compose's bare [Dialog] —
 * which still owns the window, the dim behind it, back and focus containment, the parts Material
 * had right. What Material could not be talked into was the layout: an optional glyph in a
 * circle beside the title, the title at the canvas's size, the body in the secondary ink, and a
 * row of the app's own buttons that wraps rather than overflowing, starting at the reading
 * edge on a television and ending at it on a phone (where it sits under the thumb).
 *
 * The plate is unchanged from ADR-025: [panelSurface] at the viewer's overlay opacity with
 * [MENU_MIN_OPACITY] under it, rounded at the panel corner, hairlined.
 *
 * Plain [Text] in [content] is the dialog's body — the secondary ink at the canvas's body size —
 * so a one-line message needs no styling at the call site.
 *
 * @param title the dialog's name, also what TalkBack announces as the pane
 * @param icon the glyph in the circle beside the title; null for none
 * @param danger a destructive prompt: the glyph takes the error colour. Its confirm button should
 *   be [StudioButtonVariant.DANGER], and on a television focus should start on "cancel" (the
 *   canvas's rule), which the caller does by passing `initialFocus` to that [DialogButton].
 * @param buttons [DialogButton]s, the primary one first
 */
@Composable
fun StudioDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val settings by ServiceLocator.settings.state.collectAsState()
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    val gap = if (tv) 9.dp else 12.dp
    Dialog(
        onDismissRequest = onDismissRequest,
        // The width is the canvas's, not the platform's: 900px at 1080p, 342 on a phone.
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .widthIn(max = if (tv) 450.dp else 342.dp)
                    .fillMaxWidth()
                    // The panel corner by the theme rather than `LocalTvMetrics`: the update and
                    // PIN prompts are drawn above the television shell, outside the metrics it
                    // provides, and the theme knows the shell everywhere.
                    .panelSurface(
                        opacity = settings.overlayOpacity.coerceAtLeast(MENU_MIN_OPACITY),
                        shape = MaterialTheme.shapes.large,
                    )
                    .semantics { paneTitle = title }
                    .padding(
                        start = if (tv) 26.dp else 20.dp,
                        end = if (tv) 26.dp else 20.dp,
                        top = 22.dp,
                        bottom = if (tv) 22.dp else 18.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(if (tv) 32.dp else 40.dp)
                                .clip(CircleShape)
                                .background(studio.card),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (danger) studio.error else studio.ink,
                                modifier = Modifier.size(if (tv) 16.dp else 22.dp),
                            )
                        }
                    }
                    // 36px at 1080p, 20 on a phone: the h1 step, a touch larger on a television.
                    val h1 = type.h1
                    Text(
                        text = title,
                        style = if (tv) h1.copy(fontSize = h1.fontSize * (18f / 17f)) else h1,
                        color = studio.ink,
                    )
                }
                // Takes what is left of the window and scrolls inside it, so a long menu never
                // pushes the buttons off the screen. The caller's own `verticalScroll` does that.
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    // 24px at 1080p is the television's body step; 14 on a phone is its meta.
                    val body = if (tv) type.body.copy(fontWeight = FontWeight.Normal) else type.meta
                    CompositionLocalProvider(LocalContentColor provides studio.ink2) {
                        ProvideTextStyle(body) { content() }
                    }
                }
                DialogButtonRow(tv = tv, buttons = buttons)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogButtonRow(tv: Boolean, buttons: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = if (tv) 9.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (tv) Alignment.Start else Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons()
    }
}

/**
 * A button in a [StudioDialog]'s row: the canvas's medium button on a television and its large one
 * on a phone. A dialog's buttons are ghosts except the one it is for.
 *
 * @param initialFocus where the remote lands when the dialog opens — the primary action, or
 *   "cancel" in a destructive one. Only one button in a dialog should ask.
 */
@Composable
fun DialogButton(
    label: String,
    onClick: () -> Unit,
    variant: StudioButtonVariant = StudioButtonVariant.GHOST,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
) {
    val requester = if (initialFocus) remember { FocusRequester() } else null
    if (requester != null) LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    StudioButton(
        label = label,
        onClick = onClick,
        variant = variant,
        size = if (StudioTheme.television) StudioButtonSize.MEDIUM else StudioButtonSize.LARGE,
        enabled = enabled,
        focusRequester = requester,
    )
}
