package com.idanck.shidur.data

import com.idanck.shidur.data.catchup.CatchupUrlBuilder
import com.idanck.shidur.data.model.CatchupMode
import com.idanck.shidur.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-CU-03: one test per template convention, because providers mix them freely. */
class CatchupUrlBuilderTest {

    private val start = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val end = start + 30 * 60_000L
    private val now = start + 2 * 60 * 60_000L

    private fun channel(
        mode: CatchupMode,
        url: String,
        source: String? = null,
        days: Int = 7,
    ) = Channel(
        id = 1,
        playlistId = 1,
        identity = "tvg:a",
        name = "A",
        urls = listOf(url),
        catchupMode = mode,
        catchupSource = source,
        catchupDays = days,
    )

    @Test
    fun `default mode appends the utc pair`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.DEFAULT, "http://host/live/u/p/1.ts"), start, end, now,
        )
        assertEquals("http://host/live/u/p/1.ts?utc=1700000000&lutc=${now / 1000}", url)
    }

    @Test
    fun `append mode keeps an existing query string`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.APPEND, "http://host/stream?token=abc"), start, end, now,
        )!!
        assertTrue(url.startsWith("http://host/stream?token=abc&"))
        assertTrue(url.contains("utc=1700000000"))
    }

    @Test
    fun `flussonic rewrites the manifest name`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.FLUSSONIC, "http://host/chan/index.m3u8"), start, end, now,
        )
        assertEquals("http://host/chan/1700000000-1800.m3u8", url)
    }

    @Test
    fun `flussonic handles an mpegts endpoint`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.FLUSSONIC, "http://host/chan/mpegts"), start, end, now,
        )
        assertEquals("http://host/chan/timeshift_abs-1700000000.ts", url)
    }

    @Test
    fun `xtream builds the timeshift endpoint with a minute duration`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.XC, "http://host:8080/live/user/pass/1234.ts"), start, end, now,
        )!!
        assertTrue(url.startsWith("http://host:8080/streaming/timeshift.php?"))
        assertTrue(url.contains("stream=1234"))
        assertTrue(url.contains("duration=30"))
    }

    @Test
    fun `an explicit template wins over the declared mode`() {
        val url = CatchupUrlBuilder.build(
            channel(
                CatchupMode.DEFAULT,
                "http://host/live/1.ts",
                source = "http://host/archive?start=\${start}&dur=\${duration}",
            ),
            start, end, now,
        )
        assertEquals("http://host/archive?start=1700000000&dur=1800", url)
    }

    @Test
    fun `template date tokens are formatted in UTC`() {
        val url = CatchupUrlBuilder.build(
            channel(CatchupMode.DEFAULT, "http://host/live/1.ts", source = "http://host/{utc:Y-m-d}/x.ts"),
            start, end, now,
        )
        assertEquals("http://host/2023-11-14/x.ts", url)
    }

    @Test
    fun `a channel with no archive yields nothing`() {
        assertNull(CatchupUrlBuilder.build(channel(CatchupMode.NONE, "http://host/live/1.ts"), start, end, now))
    }

    @Test
    fun `the archive window is enforced`() {
        val ch = channel(CatchupMode.DEFAULT, "http://host/live/1.ts", days = 1)
        assertTrue(CatchupUrlBuilder.isWithinWindow(ch, now - 60_000, now))
        assertFalse(CatchupUrlBuilder.isWithinWindow(ch, now - 3 * 24 * 60 * 60_000L, now))
        // A future programme is not archive material either.
        assertFalse(CatchupUrlBuilder.isWithinWindow(ch, now + 60_000, now))
        assertFalse(CatchupUrlBuilder.isWithinWindow(channel(CatchupMode.DEFAULT, "u", days = 0), now - 1, now))
    }
}
