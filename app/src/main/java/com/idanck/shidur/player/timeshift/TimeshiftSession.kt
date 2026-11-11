package com.idanck.shidur.player.timeshift

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Says the buffer cannot be had for this stream and the channel should play directly, as before
 * there was one: the stream is not MPEG-TS, the disk is full, or no picture to start from came in
 * time. Not a failure of the stream itself, so [com.idanck.shidur.player.PlayerEngine] falls back
 * at once rather than counting a retry.
 */
class TimeshiftUnavailableException(message: String) : IOException(message)

/**
 * One live channel's buffer: the only connection to the provider, and the segments cut from it
 * (ADR-065).
 *
 * A thread of its own reads the provider's stream at the stream's pace, whatever the player is
 * doing — that is what lets the player pause and the buffer go on filling — and hands the bytes
 * to a [TsSegmenter] whose segments land in [dir] and in [window]. The player never opens the
 * provider's address itself: it plays the playlist [TimeshiftPlaylistDataSource] serves from the
 * window, so an account with one connection still has one.
 *
 * A connection that drops is opened again at once, up to [MAX_RECONNECTS] times in a row, the
 * segments after it marked as a discontinuity. Past that the session fails with the last error,
 * and the playlist hands that same error to the player, so the engine's retries and failover see
 * exactly what a direct stream would have given them.
 */
class TimeshiftSession(
    private val dir: File,
    private val uri: Uri,
    private val upstreamFactory: DataSource.Factory,
    maxDurationUs: Long,
    maxBytes: Long,
) {
    val id: String = "s" + NEXT_ID.incrementAndGet()

    val window = TimeshiftWindow(maxDurationUs, maxBytes)

    @Volatile private var stopped = false
    @Volatile private var failure: IOException? = null
    @Volatile private var current: DataSource? = null
    private var thread: Thread? = null

    /** The address the player is given for this session's playlist. */
    val playlistUri: Uri get() = Uri.parse("$SCHEME://$id/live.m3u8")

    fun start() {
        dir.mkdirs()
        REGISTRY[id] = this
        thread = Thread(::run, "timeshift-$id").apply {
            isDaemon = true
            start()
        }
    }

    /** Lets go of the connection and the disk. Safe from any thread, and more than once. */
    fun stop() {
        if (stopped) return
        stopped = true
        REGISTRY.remove(id)
        // Closing from here ends a read blocked on the socket; the thread then sees [stopped].
        runCatching { current?.close() }
        thread?.interrupt()
        window.wake()
        // An hour of segments is a thousand files: deleted off the caller's thread, which is
        // usually the main one on its way to the next channel.
        Thread({
            window.clear()
            dir.deleteRecursively()
        }, "timeshift-clear-$id").start()
    }

    /**
     * Waits until the playlist has [START_SEGMENTS] segments, or the session has failed. Throws the
     * failure, as the player is to see it. One segment is not enough: the player would start on its
     * first frame with nothing after it and stall until the next one is cut.
     */
    fun awaitPlayable(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = window.version
        while (true) {
            failure?.let { throw it }
            if (stopped) throw IOException("the buffer was stopped")
            if (window.size >= START_SEGMENTS) return
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw TimeshiftUnavailableException("no segment within $timeoutMs ms")
            seen = window.awaitChange(seen, left)
        }
    }

    /** The playlist's bytes now, or the session's failure. */
    fun playlist(): ByteArray {
        failure?.let { throw it }
        if (stopped) throw IOException("the buffer was stopped")
        return window.playlist().toByteArray(Charsets.UTF_8)
    }

    private var sequence = 0L
    private var out: OutputStream? = null
    private var outFile: File? = null
    private var outDiscontinuity = false

    private val sink = object : TsSegmenter.Sink {
        override fun begin(discontinuity: Boolean) {
            val file = File(dir, "seg_${sequence}.ts")
            outFile = file
            outDiscontinuity = discontinuity
            out = BufferedOutputStream(FileOutputStream(file), WRITE_BUFFER)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out?.write(bytes, offset, length)
        }

        override fun end(durationUs: Long) {
            val file = outFile ?: return
            out?.close()
            out = null
            outFile = null
            if (stopped) {
                file.delete()
                return
            }
            window.add(TimeshiftWindow.Segment(sequence, file, durationUs, file.length(), outDiscontinuity))
            sequence++
        }
    }

    private fun run() {
        val segmenter = TsSegmenter(sink)
        var reconnects = 0
        var sniffed = false
        val sniff = ByteArray(SNIFF_BYTES)
        var sniffedBytes = 0
        val buffer = ByteArray(READ_BUFFER)
        var lastError: IOException = EOFException("the stream ended")
        while (!stopped) {
            val source = upstreamFactory.createDataSource()
            current = source
            var received = 0L
            try {
                source.open(DataSpec(uri))
                while (!stopped) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    if (n <= 0) continue
                    if (!sniffed) {
                        val take = minOf(n, SNIFF_BYTES - sniffedBytes)
                        System.arraycopy(buffer, 0, sniff, sniffedBytes, take)
                        sniffedBytes += take
                        if (sniffedBytes < SNIFF_BYTES) {
                            segmenter.feed(buffer, 0, n)
                            continue
                        }
                        if (!looksLikeTs(sniff)) {
                            fail(TimeshiftUnavailableException("not an MPEG-TS stream"))
                            return
                        }
                        sniffed = true
                    }
                    segmenter.feed(buffer, 0, n)
                    received += n
                    if (received > STEADY_BYTES) reconnects = 0
                }
                lastError = EOFException("the stream ended")
            } catch (e: IOException) {
                if (stopped) break
                lastError = e
                if (e.isStorage()) {
                    fail(TimeshiftUnavailableException("the buffer cannot write: ${e.message}"))
                    return
                }
            } finally {
                runCatching { source.close() }
                current = null
            }
            if (stopped) break
            reconnects++
            // An answer from the server (a 404, a 403 for the account) will not change by asking
            // again at once; the engine's own retries and failover decide what comes next.
            val answered = lastError is HttpDataSource.InvalidResponseCodeException
            if (answered || reconnects > MAX_RECONNECTS || (!sniffed && sniffedBytes == 0 && reconnects > 1)) {
                fail(lastError)
                return
            }
            Log.w(TAG, "$id: the connection dropped (${lastError.javaClass.simpleName}); opening it again")
            segmenter.reconnect()
            try {
                Thread.sleep(RECONNECT_WAIT_MS * reconnects)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun fail(error: IOException) {
        Log.w(TAG, "$id: ${error.message}")
        failure = error
        runCatching { out?.close() }
        window.wake()
    }

    private fun IOException.isStorage(): Boolean =
        this is java.io.FileNotFoundException || message?.contains("ENOSPC") == true

    companion object {
        const val SCHEME = "timeshift"

        /** Segments the playlist holds before the player is given it (ADR-065). */
        const val START_SEGMENTS = 2
        private const val TAG = "Timeshift"
        private const val READ_BUFFER = 64 * 1024
        private const val WRITE_BUFFER = 256 * 1024
        private const val SNIFF_BYTES = TsSegmenter.PACKET * 4
        /** About two seconds of a television stream: a connection that carried this much was up. */
        private const val STEADY_BYTES = 1_000_000L
        const val MAX_RECONNECTS = 2
        private const val RECONNECT_WAIT_MS = 500L
        private val NEXT_ID = AtomicLong()
        private val REGISTRY = java.util.concurrent.ConcurrentHashMap<String, TimeshiftSession>()

        fun find(id: String?): TimeshiftSession? = id?.let { REGISTRY[it] }

        /** Three sync bytes a packet apart, starting anywhere in the first packet. */
        fun looksLikeTs(bytes: ByteArray): Boolean {
            val p = TsSegmenter.PACKET
            for (start in 0 until p) {
                if (start + 2 * p >= bytes.size) return false
                if (bytes[start] == SYNC && bytes[start + p] == SYNC && bytes[start + 2 * p] == SYNC) return true
            }
            return false
        }

        private const val SYNC: Byte = 0x47
    }
}
