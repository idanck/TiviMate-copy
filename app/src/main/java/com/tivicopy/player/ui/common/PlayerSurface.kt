package com.tivicopy.player.ui.common

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.player.PlayerEngine

/**
 * Hosts the Media3 [PlayerView].
 *
 * The stock controller is switched off: every control in this app is drawn by Compose so the
 * same overlay code serves the D-pad and the touch shells. [PlayerView] is still used rather
 * than a bare `SurfaceView` because it brings subtitle rendering and the resize modes that
 * FR-PLB-13 needs.
 */
@Composable
fun PlayerSurface(
    engine: PlayerEngine,
    aspectMode: AspectMode,
    subtitleScale: Float,
    modifier: Modifier = Modifier,
) {
    // Collected, not fetched: when the engine replaces its instance the view must re-attach,
    // and only a state read makes AndroidView's update block run again.
    val exo by engine.currentPlayer.collectAsState()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // `PlayerView` is a focusable `FrameLayout`, and Compose hands focus across the
                // interop boundary — so on a remote the video itself became a D-pad stop, and
                // once focus landed there nothing on screen showed where it had gone. It is
                // decoration, never a control: every control in this app is drawn by Compose.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view ->
            view.player = exo ?: engine.exoPlayer()
            // Always the screen, at its size: the guide's docked preview, which ignored the aspect
            // choice and dropped subtitles at 288dp across, is gone (ADR-051).
            view.resizeMode = aspectMode.toResizeMode()
            view.subtitleView?.apply {
                setFractionalTextSize(
                    androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale,
                )
            }
        },
        onRelease = { view -> view.player = null },
    )

    DisposableEffect(Unit) {
        onDispose { engine.refreshDiagnostics() }
    }
}

/**
 * Maps the user's choice onto Media3's resize modes.
 *
 * `AspectRatioFrameLayout` offers four modes, so the seven user-facing options collapse onto
 * them: fixed ratios and "original" have no distinct mode of their own and are rendered with
 * the closest honest equivalent rather than silently doing nothing.
 */
fun AspectMode.toResizeMode(): Int = when (this) {
    AspectMode.FIT, AspectMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.FILL, AspectMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
}
