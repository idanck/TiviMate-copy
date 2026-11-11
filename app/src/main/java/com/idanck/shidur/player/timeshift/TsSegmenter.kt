package com.idanck.shidur.player.timeshift

/**
 * Cuts a live MPEG-TS byte stream into segments a local HLS playlist can list (ADR-065).
 *
 * The provider sends one endless transport stream, which no player can seek in: there is no end
 * to measure and no index. Cut into short segments, each opening on a picture the decoder can
 * start from and each carrying the stream's PAT and PMT, the same bytes become an HLS live window
 * that Media3 pauses, rewinds and fast-forwards in natively.
 *
 * Pure Kotlin and stateful: feed it bytes in whatever chunks they arrive ([feed]); it keeps the
 * 188-byte packet alignment across chunks, finds the programme, and reports through [Sink]. No
 * Android, so the cutting is tested on the JVM.
 *
 * Where it cuts: at the start of a video PES that is a random-access point (the adaptation
 * field's flag, or failing that an IDR/IRAP or sequence header at the start of the payload) once
 * the segment is [targetUs] long; and at any video PES start once it is [maxUs] long, so a
 * stream with rare key frames still makes segments. A stream with no video is timed and cut on
 * its first audio track, where every frame is a starting point.
 *
 * Durations come from the PES timestamps (90 kHz, 33 bits, wrapping). A jump backwards, or forward
 * by more than [maxJumpUs], is a discontinuity: the segment open at the time closes on the last
 * timestamp it saw and the next one is flagged, as is the first segment after [reconnect].
 */
class TsSegmenter(
    private val sink: Sink,
    private val targetUs: Long = DEFAULT_TARGET_US,
    private val maxUs: Long = DEFAULT_MAX_US,
    private val maxJumpUs: Long = DEFAULT_MAX_JUMP_US,
) {
    /** Where the segments go. Called on the thread that calls [feed]. */
    interface Sink {
        /** A segment starts; [discontinuity] when it does not follow on from the one before. */
        fun begin(discontinuity: Boolean)

        /** Bytes of the open segment, whole 188-byte packets. */
        fun write(bytes: ByteArray, offset: Int, length: Int)

        /** The open segment is complete and lasts [durationUs]. */
        fun end(durationUs: Long)
    }

    private val carry = ByteArray(PACKET)
    private var carried = 0

    private var pat: ByteArray? = null
    private var pmtPid = -1
    private var pmt: ByteArray? = null
    private var timingPid = -1
    private var timingIsVideo = false
    private var videoStreamType = 0

    private var open = false
    private var startPts = 0L
    private var lastPts = NO_PTS
    private var frameUs = DEFAULT_FRAME_US
    private var pendingDiscontinuity = false

    /** Whether a segment is open now, which is to say the programme has been found. */
    val isSegmentOpen: Boolean get() = open

    /** The stream carries pictures, not only sound; known once its PMT has been read. */
    val hasVideo: Boolean get() = timingIsVideo

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var i = offset
        val end = offset + length
        if (carried > 0) {
            val take = minOf(PACKET - carried, end - i)
            System.arraycopy(bytes, i, carry, carried, take)
            carried += take
            i += take
            if (carried < PACKET) return
            carried = 0
            if (carry[0] == SYNC) packet(carry, 0) else resyncFrom(carry, 0, PACKET)
        }
        while (i < end) {
            if (bytes[i] != SYNC) {
                i++
                continue
            }
            if (end - i < PACKET) {
                System.arraycopy(bytes, i, carry, 0, end - i)
                carried = end - i
                return
            }
            // Two syncs a packet apart, unless this is the last whole packet in the chunk.
            if (end - i > PACKET && bytes[i + PACKET] != SYNC) {
                i++
                continue
            }
            packet(bytes, i)
            i += PACKET
        }
    }

    /**
     * The upstream connection was opened again: whatever arrives next does not follow on. The open
     * segment closes on what it has, and the programme is looked for afresh.
     */
    fun reconnect() {
        closeOnLastTimestamp()
        carried = 0
        pat = null
        pmt = null
        pmtPid = -1
        timingPid = -1
        pendingDiscontinuity = true
    }

    /** The stream ended for good: the open segment closes on what it has. */
    fun finish() {
        closeOnLastTimestamp()
    }

    private fun resyncFrom(buffer: ByteArray, from: Int, to: Int) {
        // A packet that lost its sync is dropped; the next chunk starts the search again.
        var j = from + 1
        while (j < to && buffer[j] != SYNC) j++
        if (j < to) {
            val rest = to - j
            System.arraycopy(buffer, j, carry, 0, rest)
            carried = rest
        }
    }

    private fun packet(b: ByteArray, p: Int) {
        val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val pusi = b[p + 1].toInt() and 0x40 != 0
        val afc = (b[p + 3].toInt() shr 4) and 0x03
        val hasPayload = afc and 0x01 != 0
        var payload = p + 4
        var randomAccess = false
        if (afc and 0x02 != 0) {
            val afLength = b[p + 4].toInt() and 0xFF
            if (afLength > 0) randomAccess = b[p + 5].toInt() and 0x40 != 0
            payload = p + 5 + afLength
        }
        val payloadEnd = p + PACKET
        if (payload > payloadEnd) return

        if (pid == PAT_PID && pusi && hasPayload) {
            readPat(b, payload, payloadEnd)?.let { found ->
                pmtPid = found
                pat = b.copyOfRange(p, p + PACKET)
            }
        } else if (pid == pmtPid && pusi && hasPayload) {
            if (readPmt(b, payload, payloadEnd)) pmt = b.copyOfRange(p, p + PACKET)
        }
        if (pmt == null || timingPid < 0) return

        if (pid == timingPid && pusi && hasPayload) {
            val pts = readPts(b, payload, payloadEnd)
            if (pts != NO_PTS) {
                val key = !timingIsVideo || randomAccess || isKeyPicture(b, payload, payloadEnd)
                onTimedStart(pts, key)
            }
        }
        if (open) sink.write(b, p, PACKET)
    }

    private fun onTimedStart(pts: Long, key: Boolean) {
        if (lastPts != NO_PTS) {
            val step = delta(lastPts, pts)
            if (step in 1 until MAX_FRAME_US_90K) frameUs = minOf(frameUs, step * 1000 / 90)
        }
        if (!open) {
            if (key) openAt(pts)
            lastPts = pts
            return
        }
        val sinceStart = delta(startPts, pts)
        val elapsedUs = sinceStart * 1000 / 90
        if (sinceStart < 0 || elapsedUs > maxJumpUs) {
            // The clock jumped: the segment ends where its own timestamps did.
            closeOnLastTimestamp()
            pendingDiscontinuity = true
            if (key) openAt(pts)
        } else if ((key && elapsedUs >= targetUs) || elapsedUs >= maxUs) {
            sink.end(elapsedUs)
            openAt(pts)
        }
        lastPts = pts
    }

    private fun openAt(pts: Long) {
        open = true
        startPts = pts
        sink.begin(pendingDiscontinuity)
        pendingDiscontinuity = false
        pat?.let { sink.write(it, 0, PACKET) }
        pmt?.let { sink.write(it, 0, PACKET) }
    }

    private fun closeOnLastTimestamp() {
        if (!open) return
        val lastUs = if (lastPts == NO_PTS) 0 else delta(startPts, lastPts).coerceAtLeast(0) * 1000 / 90
        sink.end(lastUs + frameUs)
        open = false
        lastPts = NO_PTS
    }

    // ------------------------------------------------------------------ tables

    /** The first programme's PMT PID, or null. */
    private fun readPat(b: ByteArray, payload: Int, end: Int): Int? {
        val s = payload + 1 + (b[payload].toInt() and 0xFF)
        if (s + 8 > end || b[s].toInt() != 0x00) return null
        val sectionLength = ((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)
        val loopEnd = minOf(s + 3 + sectionLength - 4, end)
        var i = s + 8
        while (i + 4 <= loopEnd) {
            val program = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val pid = ((b[i + 2].toInt() and 0x1F) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (program != 0) return pid
            i += 4
        }
        return null
    }

    /** Picks the timing track from the PMT; true when it found one. */
    private fun readPmt(b: ByteArray, payload: Int, end: Int): Boolean {
        val s = payload + 1 + (b[payload].toInt() and 0xFF)
        if (s + 12 > end || b[s].toInt() != 0x02) return false
        val sectionLength = ((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)
        val programInfo = ((b[s + 10].toInt() and 0x0F) shl 8) or (b[s + 11].toInt() and 0xFF)
        val loopEnd = minOf(s + 3 + sectionLength - 4, end)
        var i = s + 12 + programInfo
        var video = -1
        var videoType = 0
        var audio = -1
        while (i + 5 <= loopEnd) {
            val type = b[i].toInt() and 0xFF
            val pid = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)
            val esInfo = ((b[i + 3].toInt() and 0x0F) shl 8) or (b[i + 4].toInt() and 0xFF)
            if (video < 0 && type in VIDEO_TYPES) {
                video = pid
                videoType = type
            } else if (audio < 0 && type in AUDIO_TYPES) {
                audio = pid
            }
            i += 5 + esInfo
        }
        return when {
            video >= 0 -> {
                timingPid = video
                timingIsVideo = true
                videoStreamType = videoType
                true
            }
            audio >= 0 -> {
                timingPid = audio
                timingIsVideo = false
                true
            }
            else -> false
        }
    }

    private fun readPts(b: ByteArray, payload: Int, end: Int): Long {
        if (payload + 14 > end) return NO_PTS
        if (b[payload].toInt() != 0 || b[payload + 1].toInt() != 0 || b[payload + 2].toInt() != 1) return NO_PTS
        val flags = (b[payload + 7].toInt() shr 6) and 0x03
        if (flags and 0x02 == 0) return NO_PTS
        val q = payload + 9
        return ((b[q].toLong() and 0x0E) shl 29) or
            ((b[q + 1].toLong() and 0xFF) shl 22) or
            ((b[q + 2].toLong() and 0xFE) shl 14) or
            ((b[q + 3].toLong() and 0xFF) shl 7) or
            ((b[q + 4].toLong() and 0xFE) shr 1)
    }

    /** A picture a decoder can start from, by the first NAL units of the PES payload. */
    private fun isKeyPicture(b: ByteArray, payload: Int, end: Int): Boolean {
        if (payload + 9 > end) return false
        var i = payload + 9 + (b[payload + 8].toInt() and 0xFF)
        while (i + 3 < end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val h = b[i + 3].toInt() and 0xFF
                when (videoStreamType) {
                    STREAM_H264 -> when (h and 0x1F) {
                        5, 7 -> return true
                    }
                    STREAM_HEVC -> when ((h shr 1) and 0x3F) {
                        in 16..21, 32, 33 -> return true
                    }
                    else -> if (h == 0xB3) return true
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** [to] minus [from] on the 33-bit clock, negative when [to] is earlier. */
    private fun delta(from: Long, to: Long): Long {
        var d = (to - from) % PTS_MOD
        if (d < 0) d += PTS_MOD
        return if (d > PTS_MOD / 2) d - PTS_MOD else d
    }

    companion object {
        const val PACKET = 188
        const val DEFAULT_TARGET_US = 2_000_000L
        const val DEFAULT_MAX_US = 6_000_000L
        const val DEFAULT_MAX_JUMP_US = 30_000_000L
        private const val SYNC: Byte = 0x47
        private const val PAT_PID = 0
        private const val NO_PTS = -1L
        private const val PTS_MOD = 1L shl 33
        private const val DEFAULT_FRAME_US = 40_000L
        private const val MAX_FRAME_US_90K = 9_000L // 100 ms: anything longer is not one frame
        private const val STREAM_H264 = 0x1B
        private const val STREAM_HEVC = 0x24
        private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24)
        private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)
    }
}
