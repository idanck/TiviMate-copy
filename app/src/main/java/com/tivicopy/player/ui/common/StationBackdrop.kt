package com.tivicopy.player.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.ConfigurationCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The screen a radio station gets instead of a black rectangle.
 *
 * An audio-only stream never produces a video frame, so the player surface behind stays the
 * colour it was cleared to. 0.6.3 already drew the station's logo and name over it, which said
 * *what* was playing but still left the other nine tenths of a television screen black — the
 * device report for this was simply "the radio screen is black". Everything here is about that
 * remaining area: a backdrop coloured from the station's own logo, the wall clock and date, and
 * a play/pause control that a remote can actually reach.
 *
 * The colour work at the top of the file is deliberately free of Android and Compose types —
 * it is arithmetic over ARGB integers, which is the part worth pinning in a unit test in an
 * environment that cannot render a pixel (`StationBackdropTest`).
 */

// --------------------------------------------------------------------- colour

/** Packs opaque [r]/[g]/[b] (each clamped to a byte) into an ARGB integer. */
internal fun argbOf(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/**
 * The one colour a logo is "made of", or null when it has no usable pixels at all.
 *
 * A plain average of every pixel is the wrong answer for a station logo: they are mostly
 * transparent margin and white or black lettering, and averaging those together lands on grey
 * for every station in the list — the opposite of the per-station identity this is for. So
 * fully transparent, near-black and near-white pixels are dropped outright, and what survives is
 * weighted by how colourful it is, which is what makes a small orange wordmark on a white plate
 * come out orange rather than off-white.
 */
internal fun accentFromPixels(pixels: IntArray): Int? {
    var weightedR = 0.0
    var weightedG = 0.0
    var weightedB = 0.0
    var total = 0.0
    for (pixel in pixels) {
        if (((pixel ushr 24) and 0xFF) < MIN_OPAQUE_ALPHA) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val brightest = maxOf(r, g, b)
        val darkest = minOf(r, g, b)
        if (brightest < MIN_PIXEL_LEVEL || darkest > MAX_PIXEL_LEVEL) continue
        val weight = 1.0 + (brightest - darkest) * COLOURFULNESS_WEIGHT
        weightedR += r * weight
        weightedG += g * weight
        weightedB += b * weight
        total += weight
    }
    if (total <= 0.0) return null
    return argbOf(
        (weightedR / total).roundToInt(),
        (weightedG / total).roundToInt(),
        (weightedB / total).roundToInt(),
    )
}

/**
 * The backdrop's base colour for a station: taken from its logo when the logo has a colour worth
 * taking, and otherwise derived from its name.
 *
 * The name fallback is not a placeholder for a failed load — a station whose logo is genuinely
 * black-and-white reaches it too — so it has to be stable and distinct rather than neutral: the
 * same hash-to-hue mapping the monogram tiles in [ChannelLogo] use, which gives every station its
 * own colour and gives it the same one every time.
 *
 * Whichever route it arrives by, the result is normalised to one lightness and a bounded
 * saturation. That is what keeps white text legible on every station in the playlist: an accent
 * taken as found would be near-white for one logo and near-black for the next.
 */
internal fun stationAccentColour(logoAccent: Int?, stationName: String): Int {
    val hsl = logoAccent?.let { rgbToHsl(it) }
    return if (hsl != null && hsl[1] >= MIN_USEFUL_SATURATION) {
        hslToArgb(hsl[0], hsl[1].coerceIn(MIN_ACCENT_SATURATION, MAX_ACCENT_SATURATION), ACCENT_LIGHTNESS)
    } else {
        hslToArgb(hueForName(stationName), FALLBACK_SATURATION, ACCENT_LIGHTNESS)
    }
}

/** The same colour as [accent] at a different lightness — how the gradient's ends are built. */
internal fun accentShade(accent: Int, lightness: Float): Int {
    val hsl = rgbToHsl(accent)
    return hslToArgb(hsl[0], hsl[1], lightness)
}

/** A stable hue in 0..360 for [name]; the same name always lands on the same colour. */
internal fun hueForName(name: String): Float = ((name.hashCode() and 0x7FFFFFFF) % 360).toFloat()

/** `[hue 0..360, saturation 0..1, lightness 0..1]` for an ARGB colour; alpha is ignored. */
internal fun rgbToHsl(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val brightest = maxOf(r, g, b)
    val darkest = minOf(r, g, b)
    val lightness = (brightest + darkest) / 2f
    val chroma = brightest - darkest
    // A grey has no hue to compute — and the division below would be by zero.
    if (chroma == 0f) return floatArrayOf(0f, 0f, lightness)
    val hue = when (brightest) {
        r -> 60f * (((g - b) / chroma) % 6f)
        g -> 60f * (((b - r) / chroma) + 2f)
        else -> 60f * (((r - g) / chroma) + 4f)
    }
    return floatArrayOf(
        (hue + 360f) % 360f,
        (chroma / (1f - abs(2f * lightness - 1f))).coerceIn(0f, 1f),
        lightness,
    )
}

/** The inverse of [rgbToHsl], always fully opaque. */
internal fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * l - 1f)) * s
    val second = chroma * (1f - abs((h / 60f) % 2f - 1f))
    val offset = l - chroma / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        h < 60f -> { r = chroma; g = second; b = 0f }
        h < 120f -> { r = second; g = chroma; b = 0f }
        h < 180f -> { r = 0f; g = chroma; b = second }
        h < 240f -> { r = 0f; g = second; b = chroma }
        h < 300f -> { r = second; g = 0f; b = chroma }
        else -> { r = chroma; g = 0f; b = second }
    }
    return argbOf(
        ((r + offset) * 255f).roundToInt(),
        ((g + offset) * 255f).roundToInt(),
        ((b + offset) * 255f).roundToInt(),
    )
}

/**
 * Station logos, reduced to the one colour their backdrop is built from.
 *
 * Bounded and shared across both shells, and a URL that cannot be fetched or decoded is
 * remembered as a failure so a station with a broken logo is not re-downloaded every time it
 * plays — the same shape as [com.tivicopy.player.auto.RadioArtwork]'s cache, for the same reason.
 * The download itself is Coil's, not a second HTTP path: the logo is almost always already in
 * Coil's memory or disk cache, because the list the station was picked from just drew it.
 */
object StationAccentCache {

    /** Cached in place of a colour for a logo that could not be fetched or decoded. */
    private const val FAILED = 0

    /** Station logos held at once; the bundled playlist is a couple of dozen stations. */
    private const val MAX_ENTRIES = 64

    /** The logo is decoded no larger than this: a colour average needs no detail. */
    private const val SAMPLE_PX = 48

    private val cache = object : LinkedHashMap<String, Int>(0, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > MAX_ENTRIES
    }

    @Synchronized
    private fun cached(url: String): Int? = cache[url]

    @Synchronized
    private fun store(url: String, colour: Int) {
        cache[url] = colour
    }

    /** The accent colour for the logo at [url], downloading and sampling it if it is not known. */
    suspend fun accentFor(context: Context, url: String): Int? {
        cached(url)?.let { return it.takeIf { colour -> colour != FAILED } }
        val extracted = withContext(Dispatchers.IO) { runCatching { sample(context, url) }.getOrNull() }
        store(url, extracted ?: FAILED)
        return extracted
    }

    private suspend fun sample(context: Context, url: String): Int? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(SAMPLE_PX)
            // The pixels have to be readable back: `getPixels` throws on a hardware bitmap.
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = result.drawable.toBitmap()
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return accentFromPixels(pixels)
    }
}

/**
 * Whether the backdrop should be on screen, given what the player is doing and whether it was on
 * screen a moment ago.
 *
 * The previous state is an input rather than a detail because the interesting cases are the ones
 * where the status alone cannot answer. A paused stream reports no video and no playback, which
 * is indistinguishable from "nothing has started yet" — and dropping to black the instant the
 * user pauses a station, taking the pause button with it, is exactly what the control this file
 * adds must not do. So a screen that has established itself as audio-only keeps the screen
 * through a pause or a stall, and only a picture, an error or a new stream takes it away.
 */
internal fun audioOnlyBackdropVisible(status: PlaybackStatus, wasVisible: Boolean): Boolean = when {
    status.error != null -> false
    // There is a picture to look at, so there is nothing for this to replace.
    status.width > 0 || status.height > 0 -> false
    status.playing -> true
    else -> wasVisible
}

// ------------------------------------------------------------------------ ui

/**
 * The audio-only Now Playing screen: the station's logo and name over a backdrop in its own
 * colour, the wall clock and date, and play/pause.
 *
 * @param playWhenReady the user's intent rather than [PlaybackStatus.playing], so the button
 *   still offers Pause while a station is rebuffering.
 * @param onTogglePlay null for a caller that offers play/pause elsewhere on the same screen —
 *   the phone's portrait player already has a transport row below the surface.
 * @param takeDpadFocus whether the button should claim D-pad focus. Only the ten-foot shell
 *   passes true, and only while nothing else on screen owns the arrows: a focusable control
 *   drawn under an open overlay would compete with that overlay's own focus.
 */
@Composable
fun AudioOnlyNowPlaying(
    channel: Channel?,
    title: String,
    playWhenReady: Boolean,
    modifier: Modifier = Modifier,
    onTogglePlay: (() -> Unit)? = null,
    takeDpadFocus: Boolean = false,
) {
    val accent = rememberStationAccent(channel)
    val top = remember(accent) { Color(accentShade(accent, BACKDROP_TOP_LIGHTNESS)) }
    val bottom = remember(accent) { Color(accentShade(accent, BACKDROP_BOTTOM_LIGHTNESS)) }
    val glow = remember(accent) { Color(accentShade(accent, BACKDROP_GLOW_LIGHTNESS)) }

    BoxWithConstraints(modifier = modifier.background(Brush.verticalGradient(listOf(top, bottom)))) {
        // Under a phone's 16:9 surface this has a fraction of the room a television gives it, so
        // the same composable is measured rather than duplicated: same content, smaller.
        val compact = maxHeight < COMPACT_HEIGHT
        val inset = if (compact) 12.dp else 32.dp

        // A pool of light behind the station, so the backdrop reads as lit from the middle rather
        // than as a flat rectangle of colour. Faded to the same colour at zero alpha, never to
        // `Color.Transparent`: transparent is transparent *black*, and interpolating towards it
        // leaves a dirty ring at the edge of the gradient.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.5f), glow.copy(alpha = 0f)))),
        )

        StationClock(
            compact = compact,
            modifier = Modifier.align(Alignment.TopEnd).padding(inset),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            modifier = Modifier
                .align(Alignment.Center)
                // Held back from the full width on a television so a long station name cannot
                // run under the clock in the corner.
                .fillMaxWidth(if (compact) 1f else 0.7f)
                .padding(horizontal = inset),
        ) {
            if (channel != null) {
                ChannelLogo(channel = channel, sizeDp = if (compact) 64 else 144)
            }
            Text(
                text = channel?.displayName ?: title,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = stringResource(R.string.playback_audio_only),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA),
                    textAlign = TextAlign.Center,
                )
            }
            if (onTogglePlay != null) {
                PlayPauseButton(
                    playWhenReady = playWhenReady,
                    onToggle = onTogglePlay,
                    takeDpadFocus = takeDpadFocus,
                    size = if (compact) 48.dp else 72.dp,
                )
            }
        }
    }
}

/**
 * The station's backdrop colour, resolved from its logo the first time that station is seen and
 * remembered for the rest of the session.
 *
 * The name-derived colour is shown while the logo is being sampled rather than a neutral grey:
 * it is the same colour the station's monogram tile already uses, so the screen opens in the
 * station's colour and at worst settles onto a better one.
 */
@Composable
private fun rememberStationAccent(channel: Channel?): Int {
    val context = LocalContext.current
    val name = channel?.displayName.orEmpty()
    val url = channel?.displayLogo?.takeIf { it.isNotBlank() }
    var logoAccent by remember(url) { mutableStateOf<Int?>(null) }
    LaunchedEffect(url) {
        logoAccent = url?.let { StationAccentCache.accentFor(context, it) }
    }
    return remember(logoAccent, name) { stationAccentColour(logoAccent, name) }
}

/** The wall clock and today's date, in white, over the station's backdrop. */
@Composable
private fun StationClock(compact: Boolean, modifier: Modifier = Modifier) {
    // The *configuration's* locale, not the platform default: the in-app language setting
    // (FR-UI-23) wraps this composition's context rather than changing the JVM default, so a
    // Hebrew interface would otherwise have printed an English day name.
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Woken on the minute rather than every second: the clock shows minutes, and a television
    // left on this screen for an evening should not recompose three thousand times an hour.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            text = TimeModel.ltr(TimeModel.formatClock(now)),
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.displayMedium
            },
            color = Color.White,
        )
        Text(
            text = TimeModel.formatFullDate(now, locale),
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA),
        )
    }
}

/**
 * Play/pause, as a control both a finger and a remote can work.
 *
 * The remote half is hand-rolled rather than a [androidx.compose.foundation.clickable], and the
 * reason is the *down* half of the OK press. `MainActivity.onKeyDown` starts tracking OK so that
 * holding it opens the playback menu, and a clickable consumes the down event before the activity
 * ever sees it — binding this button the obvious way would have taken the playback menu away from
 * every radio station. So only the up half is claimed, and only when the platform has not already
 * cancelled it, which is how it reports that a long press has been served.
 *
 * A short OK therefore toggles playback instead of opening the transport bar, which over a radio
 * station is the better trade: the bar's other controls are a guide, a quality readout and an
 * aspect ratio, none of which an audio stream has, and the playback menu is still one long press
 * away.
 */
@Composable
private fun PlayPauseButton(
    playWhenReady: Boolean,
    onToggle: () -> Unit,
    takeDpadFocus: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // Read through, so the tap detector below can be keyed on `Unit` — restarting it on every
    // recomposition, which keying it on the lambda would do, is what drops a press mid-gesture.
    val toggle by rememberUpdatedState(onToggle)

    // Re-requested whenever this becomes the focusable thing on screen again — closing an overlay
    // hands the arrows back, and the button has to be where they land. Losing the D-pad also
    // means losing the focus callback, so the highlight is cleared here rather than left latched.
    LaunchedEffect(takeDpadFocus) {
        if (!takeDpadFocus) {
            focused = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    val dpad = if (takeDpadFocus) {
        Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                val isOk = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    native.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (isOk && event.type == KeyEventType.KeyUp && !native.isCanceled) {
                    toggle()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interaction)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.18f))
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = if (focused) 1f else 0.55f),
                shape = CircleShape,
            )
            .then(dpad)
            // Deliberately not `clickable`: that would make the node focusable on the phone too,
            // and on the television it would claim the OK key press the comment above is about.
            .pointerInput(Unit) { detectTapGestures { toggle() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playWhenReady) R.string.action_pause else R.string.action_play,
            ),
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(size / 2),
        )
    }
}

/**
 * Below this the backdrop is a phone's video box rather than a screen, and lays out smaller.
 *
 * Set above a phone's landscape height on purpose: the full-size layout is a little over 380dp
 * tall, so anything shorter than this would have to clip it.
 */
private val COMPACT_HEIGHT: Dp = 400.dp

private const val MINUTE_MILLIS = 60_000L

/** How the secondary white lines are held back from the primary ones. */
private const val SECONDARY_TEXT_ALPHA = 0.75f

private const val BACKDROP_TOP_LIGHTNESS = 0.34f
private const val BACKDROP_BOTTOM_LIGHTNESS = 0.06f
private const val BACKDROP_GLOW_LIGHTNESS = 0.46f

/** A pixel this transparent is a logo's margin, not its colour. */
private const val MIN_OPAQUE_ALPHA = 128

/** Below this every channel is black lettering; above it, white plate. Neither is an accent. */
private const val MIN_PIXEL_LEVEL = 24
private const val MAX_PIXEL_LEVEL = 232

/** How much more a fully saturated pixel counts than a grey one: 1 + 255 * this. */
private const val COLOURFULNESS_WEIGHT = 0.05

/** A logo flatter than this has no colour of its own, and the name's hue is used instead. */
private const val MIN_USEFUL_SATURATION = 0.12f

private const val MIN_ACCENT_SATURATION = 0.35f
private const val MAX_ACCENT_SATURATION = 0.85f
private const val FALLBACK_SATURATION = 0.42f

/** One lightness for every station, so white text is legible over all of them. */
private const val ACCENT_LIGHTNESS = 0.30f
