package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * The floating plate the interface sits on, and the one menu drawn on it.
 *
 * ADR-023 defined this plate for the television shell and gave it to four surfaces: the transport
 * bar, the channel filmstrip, the navigation capsule and the playback menu. Every *other* menu in
 * the app — the channel menu and its six sheets, the programme details, the four update prompts —
 * was a stock `AlertDialog`, which is an opaque `surfaceContainerHigh` plate with no edge that
 * ignores the viewer's overlay-opacity setting outright. Which is the exact complaint ADR-023
 * opened with about the playback menu, left standing in the twelve places it was not looking.
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
 * Every menu in the app, drawn on the same plate as every other floating surface.
 *
 * A thin wrapper over Material's [AlertDialog] rather than a dialog built from scratch: its
 * placement, its scrim, its back handling and its focus behaviour are all correct already, and
 * the three things that were wrong are its shape, its plate and its edge.
 *
 * - **Shape.** `extraLarge` from the app's own ramp, which is the panel corner.
 * - **Plate.** `surface` at the viewer's overlay opacity with [MENU_MIN_OPACITY] under it, so a
 *   menu over live video is the same translucent plate the playback menu is, rather than the
 *   opaque `surfaceContainerHigh` box Material would draw.
 * - **Edge.** The panel hairline, for the same reason every other panel has one.
 *
 * Tonal elevation is explicitly off. Material's default tints the container by elevation, which
 * composites over the alpha above and quietly undoes it.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val settings by ServiceLocator.settings.state.collectAsState()
    val shape = MaterialTheme.shapes.extraLarge
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(
            width = 1.dp,
            color = StudioTheme.colors.hairline,
            shape = shape,
        ),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surface.copy(
            alpha = settings.overlayOpacity.coerceAtLeast(MENU_MIN_OPACITY),
        ),
        tonalElevation = 0.dp,
        properties = properties,
    )
}
