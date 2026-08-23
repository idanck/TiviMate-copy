package com.tivicopy.player.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Station logos, re-rendered into a large square bitmap for the car's Now Playing screen.
 *
 * The problem this solves is not resolution but *shape and scale*. A station logo published for
 * the phone/TV list is a small image — often a wide banner with its own transparent margins — and
 * a head unit does whatever it likes with the bitmap a session hands it: one draws it untouched
 * in a square art slot, where a small logo reads as a stamp in the middle of an empty screen;
 * another scales it to *cover* a wider card and crops off whatever hangs over the edges.
 *
 * So the logo is painted centred on a [SIZE_PX] square, scaled to fit [SAFE_AREA_FRACTION] of
 * that square rather than all of it. The scaling makes the logo as large as a slot that shows the
 * square whole can display it, whatever aspect ratio it arrived in; the margin the safe area
 * leaves around it is what a slot that crops takes, instead of the logo itself.
 *
 * Only the *queue* items carry this ([RadioBrowseTree.categoryQueue]) — the browse lists keep
 * pointing at the original logo URL, which the car fetches itself at thumbnail size. That keeps
 * a browse response free of bitmap payloads, which is what would otherwise cross a Binder
 * transaction once per station in the list.
 *
 * Rendered bytes are cached in memory per logo URL (bounded, LRU, shared across browse and queue
 * builds), and a URL that fails to download or decode is remembered as a failure so a station
 * with a broken logo is not re-fetched on every tap. All methods here do network and bitmap work
 * and must be called off the main thread.
 */
object RadioArtwork {

    /** Side of the square the logo is painted onto. */
    const val SIZE_PX = 512

    /**
     * The share of the square's side the logo itself may take up; the rest is transparent margin.
     *
     * 0.6.2 painted the logo edge to edge, on the assumption — true of some head units, not of
     * the one this was then seen on — that the car never enlarges the artwork it is given. A car
     * that instead scales the square to fill a wide card showed the logo blown up with its name
     * cut off at the sides. At this fraction a square logo survives being cropped to a card as
     * tight as 16:10 in either direction, and a wide banner — short in the cropped direction once
     * it is letterboxed into the safe area — survives far tighter ones, while still covering most
     * of a slot that shows the square whole.
     */
    const val SAFE_AREA_FRACTION = 0.62f

    /** A logo bigger than this is not a logo; refuse it rather than decode it. */
    private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024

    /** Rendered logos held at once — the bundled playlist is a couple of dozen stations. */
    private const val MAX_ENTRIES = 48

    /** Longest a logo fetch may keep a tapped station waiting; see [requestProfile]. */
    private const val MAX_WAIT_SECONDS = 5

    /** Cached in place of a rendered logo for a URL that could not be downloaded or decoded. */
    private val FAILED = ByteArray(0)

    private val cache = object : LinkedHashMap<String, ByteArray>(0, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_ENTRIES
    }

    @Synchronized
    private fun cachedEntry(url: String): ByteArray? = cache[url]

    @Synchronized
    private fun store(url: String, bytes: ByteArray) {
        cache[url] = bytes
    }

    /**
     * The rendered square logo for [url] if one has already been built, without ever touching the
     * network — for the stations in a queue other than the one the user actually tapped, where
     * waiting on a download would delay playback starting for no benefit.
     */
    fun cached(url: String?): ByteArray? = url
        ?.takeIf { it.isNotBlank() }
        ?.let { cachedEntry(it) }
        ?.takeIf { it.isNotEmpty() }

    /** The rendered square logo for [url], downloading and rendering it if it is not cached yet. */
    fun square(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        cachedEntry(url)?.let { return it.takeIf { bytes -> bytes.isNotEmpty() } }
        val rendered = runCatching { download(url)?.let { render(it) } }.getOrNull()
        store(url, rendered ?: FAILED)
        return rendered
    }

    /** Best-effort warm-up so a tapped station's logo is already rendered by the time it plays. */
    fun prefetch(urls: List<String>) {
        urls.forEach { square(it) }
    }

    private fun download(url: String): ByteArray? {
        val profile = requestProfile()
        HttpFactory.client(profile).newCall(HttpFactory.request(url, profile)).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_SOURCE_BYTES) return null
            val bytes = body.bytes()
            return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_SOURCE_BYTES }
        }
    }

    /**
     * Paints [source] onto a [SIZE_PX] square, in the rectangle [logoBounds] places it in —
     * "contain" inside the safe area, not "cover", because a station logo is usually its name and
     * scaling it to fill the square would cut the name in half.
     *
     * Transparent everywhere the logo does not reach, so the car draws its own background rather
     * than a box in a colour that may not match — and so a car that crops the square eats that
     * transparency first.
     */
    private fun render(source: ByteArray): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
        return try {
            val bounds = logoBounds(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            try {
                Canvas(square).drawBitmap(
                    decoded,
                    /* src = */ null,
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
                ByteArrayOutputStream().use { out ->
                    if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                    out.toByteArray()
                }
            } finally {
                square.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    /**
     * The same request shaping [RadioMediaLibraryService] gives the player — logos are served from
     * the same host as the streams, so anything the streams need (user-agent, a deliberately
     * relaxed TLS setting) a logo fetch needs too — with the timeouts capped at [MAX_WAIT_SECONDS].
     *
     * The cap is the point: a station tapped before the warm-up reached its logo is fetched while
     * the queue is being built, so a slow logo host would hold up the music behind an artwork
     * download. Waiting a few seconds for a nicer Now Playing screen is worth it; waiting out the
     * stream timeouts, which are set for streams and can be half a minute, is not.
     *
     * Read per call rather than cached because the settings behind it are live.
     */
    private fun requestProfile(): RequestProfile {
        val settings = ServiceLocator.settings.state.value
        return RequestProfile(
            userAgent = settings.userAgent,
            connectTimeoutSeconds = settings.connectTimeoutSeconds.coerceAtMost(MAX_WAIT_SECONDS),
            readTimeoutSeconds = settings.readTimeoutSeconds.coerceAtMost(MAX_WAIT_SECONDS),
            retries = settings.retries,
            allowInsecureTls = settings.allowInsecureTls,
        )
    }
}

/** The rectangle [logoBounds] puts a logo in, in the square canvas's own pixels. */
internal data class LogoBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Where a [sourceWidth] x [sourceHeight] logo goes inside [RadioArtwork.SIZE_PX]'s square: scaled
 * by its longer side to fit [RadioArtwork.SAFE_AREA_FRACTION] of the square whole, keeping its
 * aspect ratio, and centred — so however the head unit then scales or crops that square, the
 * margin around the logo is what gives way first.
 *
 * Top-level and `internal` rather than a private method of [RadioArtwork]: this is the one piece
 * of the rendering that is pure arithmetic, and it is the piece worth pinning in a test without
 * decoding a bitmap to do it.
 */
internal fun logoBounds(sourceWidth: Int, sourceHeight: Int): LogoBounds {
    val safeSide = (RadioArtwork.SIZE_PX * RadioArtwork.SAFE_AREA_FRACTION).roundToInt().coerceAtLeast(1)
    val scale = safeSide.toFloat() / max(sourceWidth, sourceHeight).coerceAtLeast(1)
    val width = (sourceWidth * scale).roundToInt().coerceIn(1, safeSide)
    val height = (sourceHeight * scale).roundToInt().coerceIn(1, safeSide)
    return LogoBounds(
        left = (RadioArtwork.SIZE_PX - width) / 2,
        top = (RadioArtwork.SIZE_PX - height) / 2,
        width = width,
        height = height,
    )
}
