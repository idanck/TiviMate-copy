package com.idanck.shidur.player.timeshift

import java.io.ByteArrayOutputStream

/** A transport stream written packet by packet, for the tests: one programme, H.264 video and AAC audio. */
internal class TsStream(private val audioOnly: Boolean = false) {
    private val out = ByteArrayOutputStream()
    private val cc = IntArray(8192)

    fun bytes(): ByteArray = out.toByteArray()

    fun tables() {
        // PAT: program 1 -> PMT_PID.
        val pat = byteArrayOf(
            0x00, 0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        section(0, pat)
        val streams = ArrayList<Byte>()
        if (!audioOnly) streams += listOf(0x1B, 0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF, 0xF0, 0x00).map { it.toByte() }
        streams += listOf(0x0F, 0xE0 or (AUDIO_PID shr 8), AUDIO_PID and 0xFF, 0xF0, 0x00).map { it.toByte() }
        val length = 9 + streams.size + 4
        val pmt = byteArrayOf(
            0x00, 0x02, (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte(),
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
        ) + streams.toByteArray() + byteArrayOf(0, 0, 0, 0)
        section(PMT_PID, pmt)
    }

    fun video(pts: Long, key: Boolean, flag: Boolean = true) {
        val nal = if (key) byteArrayOf(0, 0, 0, 1, 0x67, 0x42) else byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte())
        pes(VIDEO_PID, 0xE0, pts, nal, randomAccess = key && flag)
        // A second packet of the same picture, no start.
        payloadPacket(VIDEO_PID, ByteArray(184) { 0x11 })
        // Some sound between pictures.
        pes(AUDIO_PID, 0xC0, pts, byteArrayOf(0xFF.toByte(), 0xF1.toByte()), randomAccess = false)
    }

    fun audio(pts: Long) = pes(AUDIO_PID, 0xC0, pts, byteArrayOf(0xFF.toByte(), 0xF1.toByte()), randomAccess = true)

    private fun section(pid: Int, section: ByteArray) {
        val payload = ByteArray(184) { 0xFF.toByte() }
        System.arraycopy(section, 0, payload, 0, section.size)
        packet(pid, start = true, adaptation = null, payload = payload)
    }

    private fun pes(pid: Int, streamId: Int, pts: Long, es: ByteArray, randomAccess: Boolean) {
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 14).toInt() and 0xFE) or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts shl 1).toInt() and 0xFE) or 1).toByte(),
        )
        val body = header + es
        val adaptation = if (randomAccess) byteArrayOf(0x40) else null
        val room = 184 - (adaptation?.let { it.size + 1 } ?: 0)
        val payload = ByteArray(room) { 0x22 }
        System.arraycopy(body, 0, payload, 0, body.size)
        packet(pid, start = true, adaptation = adaptation, payload = payload)
    }

    private fun payloadPacket(pid: Int, payload: ByteArray) = packet(pid, start = false, adaptation = null, payload = payload)

    private fun packet(pid: Int, start: Boolean, adaptation: ByteArray?, payload: ByteArray) {
        val p = ByteArray(188)
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        val counter = cc[pid]
        cc[pid] = (counter + 1) and 0x0F
        var i = 4
        if (adaptation != null) {
            p[3] = (0x30 or counter).toByte()
            p[4] = adaptation.size.toByte()
            System.arraycopy(adaptation, 0, p, 5, adaptation.size)
            i = 5 + adaptation.size
        } else {
            p[3] = (0x10 or counter).toByte()
        }
        System.arraycopy(payload, 0, p, i, minOf(payload.size, 188 - i))
        out.write(p)
    }

    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
    }
}
