package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.common.animatedColour

/**
 * The two shapes every ten-foot surface is drawn from, and the modifiers that draw them.
 *
 * ADR-020 built the home screen and ADR-022 made its navigation a floating capsule, but each
 * surface in this shell had arrived with its own idea of what a surface looks like: the transport
 * bar and the channel filmstrip were edge-to-edge bands of `background` at the viewer's overlay
 * opacity, the playback menu was a square-cornered `surface` plate at a hard-coded 0.97, the home
 * tiles were 12dp cards and the filmstrip's were 10dp — with the *same* three-state colour and
 * border logic copied into both. Screens that were built at different times looked it, which is
 * exactly the complaint this file exists to answer.
 *
 * So there are two things here and nothing else:
 *
 * - **A panel** ([tvGlassPanel]) — a floating plate the interface sits on. Inset from the edges,
 *   rounded at [TV_PANEL_CORNER], translucent at the viewer's own overlay opacity (FR-UI-18) so
 *   the picture behind it is never quite gone, and outlined by a hairline that is what stops a
 *   translucent plate from dissolving into a bright frame of video.
 * - **A card** ([tvCard]) — one focusable thing on a panel. Rounded at [TV_CARD_CORNER], and
 *   carrying the three states that matter across a room: resting, playing, focused.
 *
 * Both are `surface` rather than `background`: a plate that floats over video is raised, and the
 * ramp in `ui/theme/Theme.kt` already says what raised looks like.
 */

/** The corner of a floating panel: the transport bar, the filmstrip, the navigation capsule. */
internal val TV_PANEL_CORNER = 28.dp

/** The corner of one card on a panel: a home tile, a filmstrip tile. */
internal val TV_CARD_CORNER = 14.dp

/**
 * The hairline around a panel.
 *
 * Load-bearing rather than decorative. A translucent plate over a dark scene has an edge; over a
 * bright one it does not, and the interface appears to bleed into the picture. A single low-alpha
 * line off `onSurface` reads on both without ever becoming a frame.
 */
private const val PANEL_BORDER_ALPHA = 0.12f

/** How much of the picture a resting card still lets through. */
private const val CARD_RESTING_ALPHA = 0.65f

/** The ring on the card that is playing but not focused: present, and clearly not the focus. */
private const val CARD_PLAYING_RING_ALPHA = 0.45f

/**
 * A floating plate: translucent, rounded, hairlined.
 *
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18). Every surface this shell draws
 *   over video obeys it, which is the whole point of the setting.
 */
@Composable
internal fun Modifier.tvGlassPanel(
    opacity: Float,
    shape: Shape = RoundedCornerShape(TV_PANEL_CORNER),
): Modifier = this
    .clip(shape)
    .background(MaterialTheme.colorScheme.surface.copy(alpha = opacity))
    .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = PANEL_BORDER_ALPHA),
        shape = shape,
    )

/**
 * One focusable card, in the three states a ten-foot interface has to tell apart from across a
 * room: resting, playing, focused.
 *
 * The ring is thicker than the app's usual one — it is read at distance, against moving video
 * rather than a settled surface (NFR-UX-02) — and both colours cross rather than cut
 * (`ui/common/Motion.kt`). Resting is the same colour at zero alpha and never `Color.Transparent`,
 * which is transparent *black*: a bright ring interpolating towards it darkens on its way out.
 *
 * Does not include the focus lift; that is [com.tivicopy.player.ui.common.focusLift], applied
 * before this so the growth happens outside the clip.
 */
@Composable
internal fun Modifier.tvCard(
    focused: Boolean,
    playing: Boolean = false,
    shape: Shape = RoundedCornerShape(TV_CARD_CORNER),
): Modifier = this
    .clip(shape)
    .background(
        animatedColour(
            when {
                focused -> MaterialTheme.colorScheme.surfaceVariant
                playing -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surface.copy(alpha = CARD_RESTING_ALPHA)
            },
        ),
    )
    .border(
        width = if (focused) 3.dp else 1.dp,
        color = animatedColour(
            when {
                focused -> MaterialTheme.colorScheme.primary
                playing -> MaterialTheme.colorScheme.primary.copy(alpha = CARD_PLAYING_RING_ALPHA)
                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0f)
            },
        ),
        shape = shape,
    )
