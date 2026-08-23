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
 * the phone/TV list is a small image — often a wide banner with its own transparent margins —
 * and Android Auto draws whatever bitmap the session hands it centred in a square art slot
 * without enlarging it, so the logo ends up as a stamp in the middle of a mostly empty screen.
 * Painting the logo onto a [SIZE_PX]-square canvas at the largest scale that still fits makes the
 * logo itself as large as the slot allows, whatever aspect ratio it arrived in.
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
     * Paints [source] centred on a [SIZE_PX] square at the largest scale that fits it whole —
     * "contain", not "cover", because a station logo is usually its name and cropping it to fill
     * the square would cut the name in half. Transparent everywhere the logo does not reach, so
     * the car draws its own background rather than a box in a colour that may not match.
     */
    private fun render(source: ByteArray): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
        return try {
            val scale = SIZE_PX.toFloat() / max(decoded.width, decoded.height).coerceAtLeast(1)
            val width = (decoded.width * scale).roundToInt().coerceIn(1, SIZE_PX)
            val height = (decoded.height * scale).roundToInt().coerceIn(1, SIZE_PX)
            val left = (SIZE_PX - width) / 2
            val top = (SIZE_PX - height) / 2
            val square = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            try {
                Canvas(square).drawBitmap(
                    decoded,
                    /* src = */ null,
                    Rect(left, top, left + width, top + height),
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
