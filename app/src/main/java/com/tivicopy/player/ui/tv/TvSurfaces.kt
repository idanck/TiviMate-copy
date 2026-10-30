package com.tivicopy.player.ui.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.GlassMaterial
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.drawerSurface
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.panelSurface

/**
 * The surfaces every ten-foot screen is drawn from, by the names this shell calls them.
 *
 * ADR-020 built the home screen and ADR-022 made its navigation a floating capsule (since
 * replaced by ADR-027's side menu), but each surface in this shell had arrived with its own idea
 * of what a surface looks like: the transport bar and the channel filmstrip were edge-to-edge
 * bands of `background` at the viewer's overlay opacity, the playback menu was a square-cornered
 * `surface` plate at a hard-coded 0.97, the home tiles were 12dp cards and the filmstrip's were
 * 10dp — with the *same* three-state colour and border logic copied into both. Screens that were
 * built at different times looked it, which is exactly the complaint this file exists to answer.
 *
 * In Merhav (ADR-046) there are three things here, all made of the shared materials in
 * `ui/common/Glass.kt`:
 *
 * - **A panel** ([tvGlassPanel]) — glass floating over the picture: rounded at the shell's panel
 *   corner, a lit edge instead of a frame, a shadow under it, and — since nothing behind it is
 *   really blurred — the canvas's near-opaque fallback tint at the viewer's own overlay opacity
 *   (FR-UI-18).
 * - **A card** ([tvCard]) — one piece of content on the picture: thin glass at rest, lit glass in
 *   a white ring when focus is on it, a wash of the tally while it is on screen.
 * - **A drawer** ([tvDrawer]) — a menu the full height of the screen against one edge, solid next
 *   to the edge and fading to 75% towards the content, never glass and never blurred.
 */

/** The card corner as a shape, for a card's focus lift to cast its shadow in. */
@Composable
internal fun tvCardShape(): Shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner)

/**
 * A floating panel: glass, rounded, lit along its edge, lifted.
 *
 * [com.tivicopy.player.ui.common.panelSurface] under the ten-foot name the rest of this shell
 * calls it by.
 *
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18). Every surface this shell draws
 *   over video obeys it, which is the whole point of the setting.
 * @param material regular glass for a panel, thick for a dialog or a big panel
 */
@Composable
internal fun Modifier.tvGlassPanel(
    opacity: Float,
    shape: Shape = RoundedCornerShape(LocalTvMetrics.current.panelCorner),
    material: GlassMaterial = GlassMaterial.REGULAR,
): Modifier = this.panelSurface(opacity, shape, material)

/**
 * One focusable card, in the three states a ten-foot interface has to tell apart from across a
 * room: resting, playing, focused.
 *
 * The states themselves are [com.tivicopy.player.ui.common.focusSurface] — the app's focus
 * treatment, shared with every row, chip, cell and tile in both shells. This is only the card's
 * two choices on top of it: the card corner, and [FocusFill.RAISE], the content tile's treatment
 * (thin glass at rest, lit glass in a white ring under focus). [playing] is the channel on screen,
 * and takes the tally.
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

/**
 * A drawer against the start edge (the side menu) or the end edge (the playback and channel
 * menus): [com.tivicopy.player.ui.common.drawerSurface] under this shell's name.
 */
@Composable
internal fun Modifier.tvDrawer(atStart: Boolean): Modifier = this.drawerSurface(atStart = atStart)
