package com.tivicopy.player.ui.common

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.tivicopy.player.ui.theme.StudioTheme

/*
 * Merhav's materials (ADR-046): the glass the interface floats on, its lit edge, and how deep it
 * sits. The canvas draws them as CSS classes (`st-glass-thin`, `st-glass`, `st-glass-thick`,
 * `st-rim`, `--st-depth-1..3`); these are the same three things for both shells.
 *
 * ## Blur, and why it is rarely real
 *
 * On the canvas the glass blurs what is behind it. On a television box that is the most expensive
 * thing a frame can ask for, and in Compose it is not even available for what matters most: a
 * `RenderEffect` blurs a layer's *own* content, and the video under the interface is a separate
 * surface no layer of ours can read. So the app blurs only where it is cheap and real — behind a
 * dialog's window, on Android 12 and up, where the compositor blurs the whole screen behind it
 * ([rememberWindowBlurBehind]) — and everywhere else a panel takes the canvas's fallback,
 * `--st-glass-fallback`: the same smoky tint, nearly opaque, with no change of layout. Thin glass
 * keeps its tint: it sits on a panel or on the ground, not straight on the picture.
 *
 * Drawers are never glass and never blurred ([drawerSurface]).
 */

/** The three thicknesses of glass. */
enum class GlassMaterial {
    /** `st-glass-thin`: rows, tiles, chips and buttons at rest. No depth of its own. */
    THIN,

    /** `st-glass`: a panel. */
    REGULAR,

    /** `st-glass-thick`, `st-panel`: a dialog, a sheet, a big panel. */
    THICK,
}

/** The canvas's three shadows (`--st-depth-1..3`), and none. */
enum class Depth { NONE, ONE, TWO, THREE }

/**
 * Whether the window this is drawn in really blurs what is behind it. False everywhere except
 * inside a dialog whose blur took ([rememberWindowBlurBehind]); glass drawn where it is true
 * keeps its translucent tint instead of the fallback.
 */
val LocalGlassBlur = staticCompositionLocalOf { false }

/** The depth each material casts unless asked otherwise: thin none, regular two, thick three. */
private val GlassMaterial.defaultDepth: Depth
    get() = when (this) {
        GlassMaterial.THIN -> Depth.NONE
        GlassMaterial.REGULAR -> Depth.TWO
        GlassMaterial.THICK -> Depth.THREE
    }

/**
 * A plate of glass: its depth, its tint, and its lit edge.
 *
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18), for a plate over the picture:
 *   without real blur it sets how opaque the fallback is. Null leaves the canvas's own values.
 * @param edge whether to draw the lit edge; off where the plate's own content draws one
 */
@Composable
fun Modifier.glass(
    material: GlassMaterial,
    shape: Shape,
    depth: Depth = material.defaultDepth,
    opacity: Float? = null,
    edge: Boolean = true,
): Modifier {
    val studio = StudioTheme.colors
    val blurred = LocalGlassBlur.current
    val tint = when (material) {
        GlassMaterial.THIN -> studio.card
        GlassMaterial.REGULAR, GlassMaterial.THICK -> when {
            blurred -> if (material == GlassMaterial.THICK) studio.panel else studio.surface
            opacity != null -> studio.glassFallback.copy(alpha = opacity)
            else -> studio.glassFallback
        }
    }
    return this
        .depth(depth, shape)
        .clip(shape)
        .background(tint)
        .then(if (edge) Modifier.litEdge(shape) else Modifier)
}

/**
 * The lit edge (`--st-edge`, `st-rim`): light catching the top of the glass and a faint line all
 * the way round, which is what separates a dark plate from a dark picture without drawing a frame.
 * One stroke whose colour fades from the top highlight to the line a third of the way down.
 */
@Composable
fun Modifier.litEdge(shape: Shape, width: Dp = 1.dp): Modifier {
    val studio = StudioTheme.colors
    val brush = remember(studio.edgeHighlight, studio.edgeLine) {
        Brush.verticalGradient(
            0f to studio.edgeHighlight,
            EDGE_FADE to studio.edgeLine,
            1f to studio.edgeLine,
        )
    }
    return this.border(width, brush, shape)
}

/** How far down the lit edge has faded to its line. */
private const val EDGE_FADE = 0.3f

/**
 * One of the canvas's three shadows, under [shape]. Shallower on a phone, where the canvas's
 * `--st-depth-2` is `0 14px 34px` rather than `0 24px 60px` at 1080p.
 */
@Composable
fun Modifier.depth(level: Depth, shape: Shape): Modifier {
    if (level == Depth.NONE) return this
    val tv = StudioTheme.television
    val elevation = when (level) {
        Depth.NONE -> 0.dp
        Depth.ONE -> 4.dp
        Depth.TWO -> if (tv) 12.dp else 8.dp
        Depth.THREE -> if (tv) 20.dp else 14.dp
    }
    return this.shadow(elevation = elevation, shape = shape, clip = false)
}

/**
 * A drawer: the side menu, the playback menu, the channel menu and the other panel menus that run
 * the full height of the screen against one edge (ADR-046).
 *
 * Opaque `--st-drawer` for the two thirds next to the screen's edge, then a fade to 75% over the
 * last third towards the content (`--st-drawer-start`, `--st-drawer-end`), so the picture is still
 * there beside the list without reaching through it. Never blurred. A 1dp line of the lit edge runs
 * down the side that faces the content, and past it a soft shadow falls onto the content.
 *
 * @param atStart the drawer sits on the start edge (the right in Hebrew); false for the end edge
 * @param presence how much of the drawer is drawn, 0 to 1, read when it is drawn — for a drawer
 *   that crosses in from another surface (the side menu's closed rail)
 * @param shadow how far the shadow reaches onto the content
 * @param reach how wide the drawer is drawn, in pixels, from the screen's edge, given the element's
 *   own width; read when it is drawn. The side menu lays itself out as the drawer once and grows the
 *   drawer it draws, so its opening costs no layout a frame. Defaults to the element's whole width.
 */
@Composable
fun Modifier.drawerSurface(
    atStart: Boolean,
    presence: () -> Float = { 1f },
    shadow: Dp = DRAWER_SHADOW,
    reach: (Float) -> Float = { it },
): Modifier {
    val studio = StudioTheme.colors
    val solid = studio.drawer
    val faded = studio.drawer.copy(alpha = DRAWER_FADED_ALPHA)
    val line = studio.edgeLine
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The start edge is the left in a left-to-right layout and the right in Hebrew.
    val onLeft = atStart != rtl
    val shadowPx = with(LocalDensity.current) { shadow.toPx() }
    return this.drawBehind {
        val amount = presence().coerceIn(0f, 1f)
        if (amount <= 0f) return@drawBehind
        val width = reach(size.width).coerceIn(0f, size.width)
        if (width <= 0f) return@drawBehind
        // The drawer runs from the screen's edge to [width] in from it.
        val from = if (onLeft) 0f else size.width - width
        val brush = if (onLeft) {
            Brush.horizontalGradient(0f to solid, DRAWER_SOLID to solid, 1f to faded, startX = from, endX = from + width)
        } else {
            Brush.horizontalGradient(0f to faded, 1f - DRAWER_SOLID to solid, 1f to solid, startX = from, endX = from + width)
        }
        drawRect(brush = brush, topLeft = Offset(from, 0f), size = Size(width, size.height), alpha = amount)
        // The edge facing the content, and the shadow beyond it.
        val inner = if (onLeft) from + width else from
        drawLine(
            color = line,
            start = Offset(if (onLeft) inner - 0.5f else inner + 0.5f, 0f),
            end = Offset(if (onLeft) inner - 0.5f else inner + 0.5f, size.height),
            strokeWidth = 1.dp.toPx(),
            alpha = amount,
        )
        if (shadowPx > 0f) {
            val dark = Color.Black.copy(alpha = DRAWER_SHADOW_ALPHA)
            val clear = Color.Black.copy(alpha = 0f)
            drawRect(
                brush = if (onLeft) {
                    Brush.horizontalGradient(listOf(dark, clear), startX = inner, endX = inner + shadowPx)
                } else {
                    Brush.horizontalGradient(listOf(clear, dark), startX = inner - shadowPx, endX = inner)
                },
                topLeft = Offset(if (onLeft) inner else inner - shadowPx, 0f),
                size = Size(shadowPx, size.height),
                alpha = amount,
            )
        }
    }
}

/** The share of a drawer, from the screen's edge, that is solid. */
private const val DRAWER_SOLID = 2f / 3f

/** How opaque a drawer is where it meets the content. */
private const val DRAWER_FADED_ALPHA = 0.75f

/** The drawer's shadow onto the content (`-40px 0 100px rgba(0, 0, 0, 0.55)`, softened). */
private val DRAWER_SHADOW = 40.dp
private const val DRAWER_SHADOW_ALPHA = 0.4f

/**
 * Blurs everything behind the dialog this is called from, where that is cheap and real: Android 12
 * and up, on a device that has cross-window blur on (it is off in battery saver and on many
 * low-power television boxes). The compositor does the work, so the picture behind a dialog —
 * video included, which no layer of ours can read — goes soft at no cost to this app's frames.
 *
 * Returns whether the blur took, which is what decides whether the dialog's glass keeps its
 * translucent tint ([LocalGlassBlur]) or takes the fallback. Outside a dialog it does nothing and
 * returns false.
 *
 * @param radius how far the blur spreads (`--st-blur-thick`, 64px at 1080p)
 */
@Composable
fun rememberWindowBlurBehind(radius: Dp = WINDOW_BLUR_RADIUS): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return false
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }
    val enabled = remember(window) { window.windowManager.isCrossWindowBlurEnabled }
    DisposableEffect(window, enabled, radiusPx) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = radiusPx }
        }
        onDispose { }
    }
    return enabled
}

/** `--st-blur-thick`: 64px at 1080p. */
private val WINDOW_BLUR_RADIUS = 32.dp

/**
 * Takes away the platform's own dim behind the dialog this is called from, so the dialog can lay
 * the canvas's (`--st-dim-open`) over the screen itself, where it draws a full-screen backdrop. A
 * drawer is dimmed only outside itself, which the platform's whole-window dim cannot do. Outside a
 * dialog it does nothing.
 */
@Composable
fun ClearWindowDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect { window.setDimAmount(0f) }
}
