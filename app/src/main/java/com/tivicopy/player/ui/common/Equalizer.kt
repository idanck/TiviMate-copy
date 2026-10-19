package com.tivicopy.player.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tivicopy.player.player.AudioSpectrum
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * Studio's equaliser (`docs/design/studio/canvas/Equalizer.dc.html`, ADR-031): bars that move with
 * the sound actually playing, measured by [AudioSpectrum] from the player's own output.
 *
 * Composing this is what switches the measurement on, and leaving composition switches it off, so
 * the analysis costs nothing on any screen that is not showing it. While playback is paused the
 * bars rest at the canvas's floor rather than at the last thing heard.
 *
 * The bars are drawn in a [Canvas] that reads the spectrum in its draw lambda, so a step of the
 * bars redraws this node and recomposes nothing.
 */
@Composable
fun Equalizer(playing: Boolean, description: String, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        AudioSpectrum.startListening()
        onDispose { AudioSpectrum.stopListening() }
    }
    val bands by AudioSpectrum.bands.collectAsState()
    val colour = StudioTheme.colors.equalizerBar
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val values = bands
        val count = values.size
        if (count == 0) return@Canvas
        // The canvas's spacing: a gap of 28% of a bar's share of the width.
        val share = size.width / count
        val gap = share * 0.28f
        val barWidth = share - gap
        val radius = CornerRadius(1.5.dp.toPx())
        for (i in 0 until count) {
            val level = if (playing) values[i].coerceIn(BAR_FLOOR, 1f) else BAR_FLOOR
            val height = size.height * level
            // Laid out from the start edge, as the canvas's flex row is in the reading direction.
            val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                size.width - (i + 1) * share + gap / 2
            } else {
                i * share + gap / 2
            }
            drawRoundRect(
                color = colour,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}

/** The height a bar rests at with nothing playing: the canvas's 0.06. */
private const val BAR_FLOOR = 0.06f
