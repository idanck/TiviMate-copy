package com.tivicopy.player.ui.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.panelSurface

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
 *   rounded at the shell's panel corner, translucent at the viewer's own overlay opacity (FR-UI-18) so
 *   the picture behind it is never quite gone, and outlined by a hairline that is what stops a
 *   translucent plate from dissolving into a bright frame of video.
 * - **A card** ([tvCard]) — one focusable thing on a panel. Rounded at the card corner, and
 *   carrying the three states that matter across a room: resting, playing, focused.
 *
 * Both are `surface` rather than `background`: a plate that floats over video is raised, and the
 * ramp in `ui/theme/Theme.kt` already says what raised looks like.
 */

/*
 * Both corners are Studio's (`--st-r-panel`, `--st-r-card`) and come from `LocalTvMetrics`, so
 * this file and the channel list's rows are cut from the same numbers. The plate itself lives in
 * `ui/common/Panel.kt`, so the menus in both shells can be drawn on the same one.
 */

/** The card corner as a shape, for a card's focus lift to cast its shadow in. */
@Composable
internal fun tvCardShape(): Shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner)

/**
 * A floating plate: translucent, rounded, hairlined.
 *
 * [com.tivicopy.player.ui.common.panelSurface] under the ten-foot name the rest of this shell
 * calls it by.
 *
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18). Every surface this shell draws
 *   over video obeys it, which is the whole point of the setting.
 */
@Composable
internal fun Modifier.tvGlassPanel(
    opacity: Float,
    shape: Shape = RoundedCornerShape(LocalTvMetrics.current.panelCorner),
): Modifier = this.panelSurface(opacity, shape)

/**
 * One focusable card, in the three states a ten-foot interface has to tell apart from across a
 * room: resting, playing, focused.
 *
 * The states themselves are [com.tivicopy.player.ui.common.focusSurface] — the app's one focus
 * treatment, shared with every row, chip, cell and tile in both shells. This is only the card's
 * two choices on top of it: the card corner, and [FocusFill.RAISE], because a card floats over
 * moving video and cannot answer with a translucent tint the way a row on a settled surface can.
 * [playing] is the channel on screen, and takes the tally.
 *
 * Does not include the focus lift; that is [com.tivicopy.player.ui.common.focusLift], applied
 * before this so the growth happens outside the clip.
 */
@Composable
internal fun Modifier.tvCard(
    focused: Boolean,
    playing: Boolean = false,
    shape: Shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner),
): Modifier = this.focusSurface(
    focused = focused,
    shape = shape,
    selected = playing,
    fill = FocusFill.RAISE,
)
