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
            }
        },
        update = { view ->
            view.player = exo ?: engine.exoPlayer()
            view.resizeMode = aspectMode.toResizeMode()
            view.subtitleView?.setFractionalTextSize(
                androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale,
            )
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
