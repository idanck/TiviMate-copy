package com.idanck.shidur.player.timeshift

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeshiftSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private var session: TimeshiftSession? = null

    @After fun stop() {
        session?.stop()
    }

    /** One connection's worth of bytes per call, then the end of the stream. */
    private class Upstream(private val connections: List<() -> ByteArray>) : DataSource.Factory {
        val opened = AtomicInteger()

        override fun createDataSource(): DataSource = object : BaseDataSource(true) {
            private var data = ByteArray(0)
            private var pos = 0
            private var uri: Uri? = null

            override fun open(dataSpec: DataSpec): Long {
                uri = dataSpec.uri
                val n = opened.getAndIncrement()
                data = connections.getOrNull(n)?.invoke() ?: throw IOException("no more connections")
                return C.LENGTH_UNSET.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= data.size) return C.RESULT_END_OF_INPUT
                val n = minOf(length, data.size - pos, 4096)
                System.arraycopy(data, pos, buffer, offset, n)
                pos += n
                return n
            }

            override fun getUri(): Uri? = uri

            override fun close() {
                uri = null
            }
        }
    }

    private fun stream(fromFrame: Int, frames: Int): ByteArray {
        val ts = TsStream()
        ts.tables()
        repeat(frames) { f -> ts.video(pts = (fromFrame + f) * 3600L, key = (fromFrame + f) % 25 == 0) }
        return ts.bytes()
    }

    private fun start(upstream: Upstream): TimeshiftSession =
        TimeshiftSession(
            dir = folder.newFolder("live"),
            uri = Uri.parse("http://provider.example/live/1.ts"),
            upstreamFactory = upstream,
            maxDurationUs = 60_000_000,
            maxBytes = Long.MAX_VALUE,
        ).also {
            session = it
            it.start()
        }

    @Test
    fun servesAPlaylistOfTheSegmentsItCut() {
        val s = start(Upstream(listOf({ stream(0, 250) })))
        s.awaitPlayable(5_000)
        // Wait for the connection to run out and be given up on.
        Thread.sleep(2_500)
        val playlist = String(s.window.playlist().toByteArray())
        assertTrue(playlist, playlist.contains("#EXTINF:2.000000,"))
        assertTrue(s.window.size >= 4)
        assertTrue(TimeshiftSession.find(s.id) === s)
        assertEquals("timeshift", s.playlistUri.scheme)
    }

    @Test
    fun aStreamThatIsNotTransportStreamIsUnavailable() {
        val s = start(Upstream(listOf({ "#EXTM3U\n#EXT-X-VERSION:3\n".repeat(100).toByteArray() })))
        try {
            s.awaitPlayable(5_000)
            fail("a playlist is not a transport stream")
        } catch (e: TimeshiftUnavailableException) {
            assertTrue(e.message!!.contains("MPEG-TS"))
        }
    }

    @Test
    fun aDroppedConnectionIsOpenedAgainAndMarked() {
        val upstream = Upstream(listOf({ stream(0, 120) }, { stream(500, 120) }))
        val s = start(upstream)
        s.awaitPlayable(5_000)
        val deadline = System.currentTimeMillis() + 5_000
        while (upstream.opened.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(500)
        assertEquals(2, upstream.opened.get().coerceAtMost(2))
        assertTrue(s.window.snapshot().any { it.discontinuity })
    }

    @Test
    fun anAnswerFromTheServerFailsAtOnce() {
        val answer = HttpDataSource.InvalidResponseCodeException(
            404, "Not Found", null, emptyMap(), DataSpec(Uri.parse("http://provider.example/x")), ByteArray(0),
        )
        val upstream = Upstream(listOf({ throw answer }, { stream(0, 100) }))
        val s = start(upstream)
        try {
            s.awaitPlayable(5_000)
            fail("a 404 is not playable")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(404, e.responseCode)
        }
        assertEquals(1, upstream.opened.get())
    }

    @Test
    fun stoppingLetsGoOfTheFiles() {
        val s = start(Upstream(listOf({ stream(0, 150) })))
        s.awaitPlayable(5_000)
        val files = s.window.snapshot().map { it.file }
        s.stop()
        val deadline = System.currentTimeMillis() + 3_000
        while (files.any { it.exists() } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(files.none { it.exists() })
        assertEquals(null, TimeshiftSession.find(s.id))
    }
}
