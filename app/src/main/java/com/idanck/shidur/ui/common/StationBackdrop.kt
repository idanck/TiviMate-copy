package com.idanck.shidur.ui.common

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.ConfigurationCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.idanck.shidur.R
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.player.PlaybackStatus
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.theme.StudioTheme
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
 * plays — the same shape as [com.idanck.shidur.auto.RadioArtwork]'s cache, for the same reason.
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
/**
 * The channel an audio-only stream belongs to, for the backdrop to name and take its colour from.
 *
 * Read from [NowPlayingInfo] rather than `AppUiState.currentChannel`, which deliberately survives
 * an on-demand session: a film started straight after a radio station would otherwise be drawn as
 * that station, logo, name and all.
 *
 * Deliberately answers for catch-up as well as live — a catch-up programme belongs to a channel
 * and should be named after it. So "which channel is this" and "is this live" are two questions
 * with two answers: whether it is live is [com.idanck.shidur.ui.common.isLive], and a station's
 * stream and yesterday's programme off the same station are indistinguishable to everything else
 * on this screen.
 */
internal fun NowPlayingInfo?.audioOnlyChannel(): Channel? = when (this) {
    is NowPlayingInfo.Live -> channel
    is NowPlayingInfo.Catchup -> channel
    is NowPlayingInfo.OnDemand, is NowPlayingInfo.RecordingFile, is NowPlayingInfo.Podcast, null -> null
}

internal fun audioOnlyBackdropVisible(status: PlaybackStatus, wasVisible: Boolean): Boolean = when {
    status.error != null -> false
    // There is a picture to look at, so there is nothing for this to replace.
    status.width > 0 || status.height > 0 -> false
    status.playing -> true
    else -> wasVisible
}

// ------------------------------------------------------------------------ ui

/**
 * The audio-only Now Playing screen — Studio's radio screen (`docs/design/studio/canvas/Radio.dc.html`,
 * ADR-030).
 *
 * On a television: Studio's shifting background ([studioAmbient]), the live badge and the clock
 * across the top, the station's disc haloed in light, and beside it what is playing: the song as a
 * broadcast would set it (the display weight), the artist, play/pause and the station as a favourite. Once
 * the viewer has been away for the idle time, the screensaver takes this screen like any other
 * that is not full-screen video, and carries the task list under its clock (ADR-040).
 *
 * Under a phone's surface the same content is laid out small.
 *
 * @param song the song the station says is on ([PlaybackStatus.streamTitle]), or null when it
 *   says nothing — most stations, and every catch-up.
 * @param playWhenReady the user's intent rather than [PlaybackStatus.playing], so the button
 *   still offers Pause while a station is rebuffering.
 * @param onTogglePlay null for a caller that offers play/pause elsewhere on the same screen —
 *   the phone's portrait player already has a transport row below the surface.
 * @param takeDpadFocus whether the button should claim D-pad focus. Only the ten-foot shell
 *   passes true, and only while nothing else on screen owns the arrows: a focusable control
 *   drawn under an open overlay would compete with that overlay's own focus.
 * @param onFavourite toggles the station as a favourite; offered only where the remote can reach it
 */
@Composable
fun AudioOnlyNowPlaying(
    channel: Channel?,
    title: String,
    playWhenReady: Boolean,
    modifier: Modifier = Modifier,
    song: String? = null,
    onTogglePlay: (() -> Unit)? = null,
    takeDpadFocus: Boolean = false,
    /**
     * Whether this stream is arriving live — see [isLive]. Off by default, so a caller that
     * has not thought about it gets no claim rather than a wrong one.
     */
    live: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    /**
     * The stations along the bottom (`canvas/Radio.dc.html`), supplied by the shell, which knows
     * the station list; null where the remote cannot reach it.
     */
    stationStrip: (@Composable (Modifier) -> Unit)? = null,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    BoxWithConstraints(modifier = modifier.studioAmbient()) {
        // Under a phone's 16:9 surface this has a fraction of the room a television gives it, so
        // the same composable is measured rather than duplicated: same content, smaller.
        val compact = maxHeight < COMPACT_HEIGHT
        // What this screen is *of*: the station when there is one, and otherwise whatever the
        // stream calls itself.
        val headline = channel?.displayName ?: title
        val parts = song?.let(::splitSong)
        // The second line when the station names no song: what is playing, when that is something
        // other than the station itself — catch-up off a radio station, where the title is the
        // programme's — and otherwise the audio-only explanation.
        val programme = title.takeIf { it.isNotBlank() && it != headline }

        if (compact) {
            // A floating window is smaller again than a phone's video box — a couple of hundred dp
            // wide and well under half that tall — so there the disc shrinks and the name keeps to
            // one line, or the song would fall out of the bottom of the window.
            val tiny = maxHeight < TINY_HEIGHT
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (tiny) 4.dp else 8.dp),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                if (channel != null) StationDisc(channel = channel, size = if (tiny) TINY_DISC_SIZE else 64.dp)
                Text(
                    text = headline,
                    style = type.rowLabel,
                    color = studio.ink,
                    textAlign = TextAlign.Center,
                    maxLines = if (tiny) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song != null) SongLine(song = song, compact = true)
                if (onTogglePlay != null) {
                    PlayPauseButton(
                        playWhenReady = playWhenReady,
                        onToggle = onTogglePlay,
                        takeDpadFocus = takeDpadFocus,
                        compact = true,
                    )
                }
            }
            return@BoxWithConstraints
        }

        // Across the top: the badge and what this is on one side, the clock on the other.
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(start = SAFE_X, top = SAFE_Y),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Both conditions are load-bearing. This screen deliberately survives a pause — that
            // is what keeps the pause button reachable (`audioOnlyBackdropVisible`) — so a badge
            // tied to nothing but the backdrop would go on claiming "live" over a stopped stream;
            // and it is drawn for catch-up as well as live, where the claim is simply false.
            if (live && playWhenReady) LiveBadge()
            Text(
                text = stringResource(R.string.radio_label),
                style = type.meta.copy(fontSize = type.meta.fontSize * RADIO_LABEL_SCALE),
                color = studio.ink2,
            )
        }
        StationClock(modifier = Modifier.align(Alignment.TopEnd).padding(end = SAFE_X, top = SAFE_Y))

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = DISC_START, top = DISC_TOP, end = SAFE_X),
            horizontalArrangement = Arrangement.spacedBy(DISC_GAP),
        ) {
            if (channel != null) StationDisc(channel = channel, size = DISC_SIZE, halo = true)
            Column(
                modifier = Modifier.padding(top = NOW_TOP).widthIn(max = NOW_WIDTH),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = stringResource(R.string.radio_now_playing_on, headline),
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The song as a broadcast would set it; the station's own name when it names none.
                Text(
                    text = parts?.title ?: headline,
                    style = type.hero.copy(
                        fontSize = type.hero.fontSize * SONG_SCALE,
                        lineHeight = type.hero.fontSize * SONG_SCALE * 1.05f,
                        textDirection = TextDirection.Content,
                    ),
                    color = studio.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val second = parts?.artist ?: if (song == null) {
                    programme ?: stringResource(R.string.playback_audio_only)
                } else {
                    null
                }
                if (second != null) {
                    Text(
                        text = second,
                        style = type.h1.copy(
                            fontSize = type.h1.fontSize * ARTIST_SCALE,
                            fontWeight = FontWeight.Medium,
                            textDirection = TextDirection.Content,
                        ),
                        color = studio.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The sound itself, measured from the player's own output (ADR-031).
                Equalizer(
                    playing = playWhenReady,
                    description = stringResource(R.string.radio_equalizer),
                    modifier = Modifier.padding(top = 5.dp).width(NOW_WIDTH).height(EQUALIZER_HEIGHT),
                )
                if (onTogglePlay != null) {
                    Row(
                        modifier = Modifier.padding(top = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayPauseButton(
                            playWhenReady = playWhenReady,
                            onToggle = onTogglePlay,
                            takeDpadFocus = takeDpadFocus,
                            compact = false,
                        )
                        // Only while the remote can reach it: a focusable control under an open
                        // overlay would compete with that overlay's own focus.
                        if (onFavourite != null && channel != null && takeDpadFocus) {
                            StudioButton(
                                label = stringResource(
                                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                                ),
                                icon = if (channel.favourite) StudioIcons.StarOn else StudioIcons.Star,
                                iconOnly = true,
                                size = StudioButtonSize.MEDIUM,
                                // A favourite station is amber, filled and ringed (ADR-058).
                                active = channel.favourite,
                                onClick = onFavourite,
                            )
                        }
                    }
                }
            }
        }

        if (stationStrip != null) {
            stationStrip(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = SAFE_Y))
        }
    }
}

/**
 * The station disc (`canvas/StationDisc.dc.html`): the station's mark on a disc of thin glass with
 * its lit edge — and, for the station on air on its own screen, ringed in the halo's light. A disc
 * is content: under the remote it is lit glass, and on air in a list a wash of the tally (the ring
 * round it is the caller's).
 */
@Composable
internal fun StationDisc(
    channel: Channel,
    size: Dp,
    modifier: Modifier = Modifier,
    halo: Boolean = false,
    focused: Boolean = false,
    onAir: Boolean = false,
) {
    val studio = StudioTheme.colors
    val plate = animatedColour(
        when {
            focused -> studio.cardFocus
            onAir -> studio.onScreen
            else -> studio.card
        },
    )
    val haloWidth = if (halo) size * DISC_HALO_FRACTION else 0.dp
    Box(
        modifier = modifier
            .size(size + haloWidth * 2)
            .then(if (halo) Modifier.clip(CircleShape).background(studio.halo) else Modifier)
            .padding(haloWidth),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(plate)
                .litEdge(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // The mark is inset rather than filling the disc: a round plate has no room at its
            // edges for a wordmark's first and last letters.
            ChannelLogo(
                channel = channel,
                sizeDp = (size.value * DISC_LOGO_FRACTION).roundToInt(),
                circular = true,
            )
        }
    }
}

/**
 * A song as `Artist - Title`, which is how stations announce one (`StreamTitle`), split into its
 * two halves. A song that does not follow the convention is all title.
 */
internal data class SongParts(val artist: String?, val title: String)

internal fun splitSong(song: String): SongParts {
    val at = song.indexOf(" - ")
    if (at <= 0 || at + 3 >= song.length) return SongParts(null, song.trim())
    val artist = song.substring(0, at).trim()
    val title = song.substring(at + 3).trim()
    return if (artist.isEmpty() || title.isEmpty()) SongParts(null, song.trim()) else SongParts(artist, title)
}

/**
 * The song the station says is on, behind a note so it does not read as a second station name.
 *
 * [TextDirection.Content]: a Hebrew song under an English interface, or the other way round, has
 * to lay itself out by what it says rather than by the language of the menus.
 */
@Composable
internal fun SongLine(song: String, compact: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        modifier = modifier,
    ) {
        Icon(
            imageVector = StudioIcons.Music,
            contentDescription = null,
            // The second ink is itself translucent: fade it from its own alpha, not from opaque.
            tint = StudioTheme.colors.ink2.let { it.copy(alpha = it.alpha * alpha) },
            modifier = Modifier.size(if (compact) 16.dp else 24.dp),
        )
        Text(
            text = song,
            style = (
                if (compact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleLarge
                }
                ).copy(textDirection = TextDirection.Content),
            color = StudioTheme.colors.ink.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
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
internal fun rememberStationAccent(channel: Channel?): Int {
    val context = LocalContext.current
    val name = channel?.displayName.orEmpty()
    val url = channel?.displayLogo?.takeIf { it.isNotBlank() }
    var logoAccent by remember(url) { mutableStateOf<Int?>(null) }
    LaunchedEffect(url) {
        logoAccent = url?.let { StationAccentCache.accentFor(context, it) }
    }
    return remember(logoAccent, name) { stationAccentColour(logoAccent, name) }
}

/** The wall clock and today's date, Studio's top-bar clock (`canvas/Radio.dc.html`). */
@Composable
private fun StationClock(modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
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
            style = type.h1.copy(fontSize = type.h1.fontSize * CLOCK_SCALE, fontWeight = FontWeight.SemiBold),
            color = studio.ink,
        )
        Text(
            text = TimeModel.formatShortDate(now, locale),
            style = type.caption,
            color = studio.ink2,
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
    /** A round icon under a phone's surface; Studio's medium button, with its label, otherwise. */
    compact: Boolean,
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

    val label = stringResource(if (playWhenReady) R.string.action_pause else R.string.action_play)
    // Studio's button (`canvas/Button.dc.html`): a control with a hairline at rest, the cue when
    // the remote is on it. Drawn through the shared treatment rather than a copy of it.
    val shape = if (compact) CircleShape else RoundedCornerShape(LocalTvMetrics.current.controlCorner)
    val content = filledContentColour(focused)
    Row(
        modifier = modifier
            .height(if (compact) 48.dp else 32.dp)
            .then(if (compact) Modifier.width(48.dp) else Modifier)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.FILLED)
            .then(dpad)
            // Deliberately not `clickable`: that would make the node focusable on the phone too,
            // and on the television it would claim the OK key press the comment above is about.
            // What `clickable` also brings, and a bare tap detector does not, is the semantics a
            // screen reader dispatches its double-tap to — so those are declared by hand. In
            // phone landscape this is the only play/pause control on screen.
            .pointerInput(Unit) { detectTapGestures { toggle() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { toggle(); true }
            }
            .padding(horizontal = if (compact) 0.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (playWhenReady) StudioIcons.Pause else StudioIcons.Play,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(if (compact) 24.dp else 13.dp),
        )
        if (!compact) {
            val action = StudioTheme.type.action
            Text(
                text = label,
                style = action.copy(
                    fontSize = action.fontSize * BUTTON_LABEL_SCALE,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                ),
                color = content,
                maxLines = 1,
            )
        }
    }
}

/**
 * Below this the backdrop is a phone's video box rather than a screen, and lays out smaller.
 *
 * Set above a phone's landscape height on purpose: the full-size layout is a little over 380dp
 * tall, so anything shorter than this would have to clip it.
 */
private val COMPACT_HEIGHT: Dp = 400.dp

/**
 * Below this the backdrop is a floating window rather than a video box: a phone's portrait video
 * box is about 220dp tall, a floating window well under 150.
 */
private val TINY_HEIGHT: Dp = 180.dp

/** The station's disc in a floating window. */
private val TINY_DISC_SIZE: Dp = 40.dp

private const val MINUTE_MILLIS = 60_000L

/*
 * The radio screen's measurements, from `canvas/Radio.dc.html` and `StationDisc.dc.html` (1080p
 * pixels halved).
 */
private val SAFE_X = 48.dp
private val SAFE_Y = 24.dp
private val DISC_SIZE = 220.dp
private val DISC_START = 110.dp
private val DISC_TOP = 85.dp
private val DISC_GAP = 36.dp
private val NOW_TOP = 28.dp
private val NOW_WIDTH = 500.dp
private val EQUALIZER_HEIGHT = 48.dp

/** The halo ring is 18px around a 440px disc. */
private const val DISC_HALO_FRACTION = 18f / 440f

/** How much of a disc the station's mark takes, leaving its rim clear. */
private const val DISC_LOGO_FRACTION = 0.72f

/** Type against the ramp: the song 150px (hero 140), the artist 40px (h1 34), the label 24px (meta 22). */
private const val SONG_SCALE = 150f / 140f
private const val ARTIST_SCALE = 40f / 34f
private const val RADIO_LABEL_SCALE = 24f / 22f

/** The clock is 36px against h1's 34; the medium button's label 24px against the action's 30. */
private const val CLOCK_SCALE = 36f / 34f
private const val BUTTON_LABEL_SCALE = 24f / 30f

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
