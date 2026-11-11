package com.idanck.shidur.player.timeshift

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsSegmenterTest {

    /** What the sink saw: one entry per segment. */
    private class Recorder : TsSegmenter.Sink {
        class Seg(val discontinuity: Boolean) {
            val bytes = ByteArrayOutputStream()
            var durationUs = -1L
        }

        val segments = ArrayList<Seg>()

        override fun begin(discontinuity: Boolean) {
            segments += Seg(discontinuity)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            segments.last().bytes.write(bytes, offset, length)
        }

        override fun end(durationUs: Long) {
            segments.last().durationUs = durationUs
        }

        val closed get() = segments.filter { it.durationUs >= 0 }
    }

    @Test
    fun cutsAtTheFirstKeyFrameAfterTheTarget() {
        val ts = TsStream()
        ts.tables()
        // 25 fps, a key frame every second, ten seconds.
        repeat(250) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 2_000_000).feed(ts.bytes())

        val closed = sink.closed
        assertEquals(4, closed.size)
        closed.forEach { assertEquals(2_000_000L, it.durationUs) }
        assertFalse(sink.segments.any { it.discontinuity })
    }

    @Test
    fun everySegmentOpensWithThePatAndPmtThenItsKeyFrame() {
        val ts = TsStream()
        ts.tables()
        repeat(100) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 1_000_000).feed(ts.bytes())

        sink.closed.forEach { seg ->
            val b = seg.bytes.toByteArray()
            assertEquals(0, pid(b, 0))
            assertEquals(TsStream.PMT_PID, pid(b, 188))
            assertEquals(TsStream.VIDEO_PID, pid(b, 376))
            assertTrue("starts on a random-access point", b[376 + 5].toInt() and 0x40 != 0)
        }
    }

    @Test
    fun theSameSegmentsWhateverTheChunks() {
        val ts = TsStream()
        ts.tables()
        repeat(150) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0) }
        val bytes = ts.bytes()

        val whole = Recorder()
        TsSegmenter(whole).feed(bytes)
        val pieces = Recorder()
        val segmenter = TsSegmenter(pieces)
        var i = 0
        var size = 1
        while (i < bytes.size) {
            val n = minOf(size, bytes.size - i)
            segmenter.feed(bytes, i, n)
            i += n
            size = size % 997 + 7
        }
        assertEquals(whole.closed.size, pieces.closed.size)
        whole.closed.zip(pieces.closed).forEach { (a, b) ->
            assertEquals(a.durationUs, b.durationUs)
            assertArrayEquals(a.bytes.toByteArray(), b.bytes.toByteArray())
        }
    }

    @Test
    fun nothingIsWrittenBeforeTheTablesAndAKeyFrame() {
        val ts = TsStream()
        repeat(10) { frame -> ts.video(pts = frame * 3600L, key = true) } // before any PMT
        ts.tables()
        repeat(10) { frame -> ts.video(pts = (10 + frame) * 3600L, key = false) }
        ts.video(pts = 20 * 3600L, key = true)
        val sink = Recorder()
        TsSegmenter(sink).feed(ts.bytes())
        assertEquals(1, sink.segments.size)
        assertFalse(sink.segments[0].discontinuity)
    }

    @Test
    fun aClockThatJumpsBackIsADiscontinuity() {
        val ts = TsStream()
        ts.tables()
        repeat(75) { frame -> ts.video(pts = 900_000L + frame * 3600L, key = frame % 25 == 0) }
        repeat(75) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 2_000_000).feed(ts.bytes())

        val flags = sink.segments.map { it.discontinuity }
        assertEquals(listOf(false, false, true, false), flags)
        // The segment cut short by the jump ends on its own last frame, plus one frame.
        assertEquals(1_000_000L, sink.segments[1].durationUs)
    }

    @Test
    fun aReconnectIsADiscontinuityAndWaitsForFreshTables() {
        val ts = TsStream()
        ts.tables()
        repeat(60) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0) }
        val sink = Recorder()
        val segmenter = TsSegmenter(sink, targetUs = 2_000_000)
        segmenter.feed(ts.bytes())
        segmenter.reconnect()

        val after = TsStream()
        repeat(5) { frame -> after.video(pts = 5_000_000L + frame * 3600L, key = true) } // no tables yet
        after.tables()
        repeat(60) { frame -> after.video(pts = 6_000_000L + frame * 3600L, key = frame % 25 == 0) }
        segmenter.feed(after.bytes())

        val begun = sink.segments.map { it.discontinuity }
        assertEquals(false, begun[0])
        assertTrue("the first segment after the reconnect is flagged", begun[2])
        // The packets that came before the new tables were not written anywhere.
        val third = sink.segments[2].bytes.toByteArray()
        assertEquals(0, pid(third, 0))
    }

    @Test
    fun rareKeyFramesStillMakeSegments() {
        val ts = TsStream()
        ts.tables()
        // A key frame every twenty seconds.
        repeat(1000) { frame -> ts.video(pts = frame * 3600L, key = frame % 500 == 0) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 2_000_000, maxUs = 6_000_000).feed(ts.bytes())
        assertTrue(sink.closed.size >= 6)
        // Cut at six seconds on any picture, and on the key frame when one comes along.
        sink.closed.forEach { assertTrue(it.durationUs in 2_000_000L..6_000_000L) }
        assertTrue(sink.closed.count { it.durationUs == 6_000_000L } >= 4)
    }

    @Test
    fun theClockWrappingIsNotADiscontinuity() {
        val wrap = 1L shl 33
        val ts = TsStream()
        ts.tables()
        repeat(150) { frame -> ts.video(pts = (wrap - 90_000L * 2 + frame * 3600L) % wrap, key = frame % 25 == 0) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 2_000_000).feed(ts.bytes())
        assertFalse(sink.segments.any { it.discontinuity })
        sink.closed.forEach { assertEquals(2_000_000L, it.durationUs) }
    }

    @Test
    fun aKeyFrameIsFoundByItsNalUnitWithoutTheFlag() {
        val ts = TsStream()
        ts.tables()
        repeat(100) { frame -> ts.video(pts = frame * 3600L, key = frame % 25 == 0, flag = false) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 1_000_000).feed(ts.bytes())
        assertEquals(3, sink.closed.size)
        sink.closed.forEach { assertEquals(1_000_000L, it.durationUs) }
    }

    @Test
    fun soundOnlyIsCutOnItsOwnClock() {
        val ts = TsStream(audioOnly = true)
        ts.tables()
        // AAC frames about 21 ms apart.
        repeat(300) { frame -> ts.audio(pts = frame * 1920L) }
        val sink = Recorder()
        TsSegmenter(sink, targetUs = 2_000_000).feed(ts.bytes())
        assertTrue(sink.closed.isNotEmpty())
        sink.closed.forEach { assertTrue(it.durationUs in 2_000_000L..2_030_000L) }
    }

    @Test
    fun recognisesTransportStreams() {
        val ts = TsStream()
        ts.tables()
        repeat(4) { ts.video(pts = it * 3600L, key = it == 0) }
        val bytes = ts.bytes()
        assertTrue(TimeshiftSession.looksLikeTs(bytes.copyOfRange(0, 188 * 4)))
        assertTrue(TimeshiftSession.looksLikeTs(bytes.copyOfRange(57, 57 + 188 * 4)))
        assertFalse(TimeshiftSession.looksLikeTs("#EXTM3U\n#EXTINF:-1,x\nhttp://a/b\n".repeat(40).toByteArray()))
    }

    private fun pid(b: ByteArray, p: Int) = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
}
