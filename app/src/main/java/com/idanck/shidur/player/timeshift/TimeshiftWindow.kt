package com.idanck.shidur.player.timeshift

import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * The segments the buffer holds, and the HLS media playlist that lists them (ADR-065).
 *
 * A sliding window: segments are added at the live end and dropped from the old end once the
 * window holds more than [maxDurationUs] of programme or [maxBytes] on disk, the file of a dropped
 * segment deleted with it. [MIN_SEGMENTS] always stay, whatever the caps say, so the player never
 * finds an empty playlist. The playlist follows RFC 8216's rules for a sliding window: the media
 * sequence counts the segments dropped, the discontinuity sequence the discontinuity tags dropped.
 *
 * Shared between the thread that cuts the stream and the player's loader, so every member is
 * synchronised; [version] lets a reader wait for the next segment instead of polling.
 */
class TimeshiftWindow(
    private val maxDurationUs: Long,
    private val maxBytes: Long,
) {
    data class Segment(
        val sequence: Long,
        val file: File,
        val durationUs: Long,
        val bytes: Long,
        val discontinuity: Boolean,
    )

    private val segments = ArrayDeque<Segment>()
    private var discontinuitySequence = 0L
    private var longestUs = 0L
    private var totalUs = 0L
    private var totalBytes = 0L

    /** Bumped by every change, so a reader can wait for one ([awaitChange]). */
    var version = 0L
        private set

    private val lock = Object()

    fun add(segment: Segment) = synchronized(lock) {
        segments.addLast(segment)
        totalUs += segment.durationUs
        totalBytes += segment.bytes
        longestUs = maxOf(longestUs, segment.durationUs)
        while (segments.size > MIN_SEGMENTS && (totalUs > maxDurationUs || totalBytes > maxBytes)) {
            val dropped = segments.removeFirst()
            totalUs -= dropped.durationUs
            totalBytes -= dropped.bytes
            if (dropped.discontinuity) discontinuitySequence++
            dropped.file.delete()
        }
        version++
        lock.notifyAll()
    }

    val isEmpty: Boolean get() = synchronized(lock) { segments.isEmpty() }

    val size: Int get() = synchronized(lock) { segments.size }

    /** How much programme the window holds now. */
    val durationUs: Long get() = synchronized(lock) { totalUs }

    val bytes: Long get() = synchronized(lock) { totalBytes }

    fun snapshot(): List<Segment> = synchronized(lock) { segments.toList() }

    /** Waits up to [timeoutMs] for [version] to move past [seen]; returns the version then. */
    fun awaitChange(seen: Long, timeoutMs: Long): Long = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (version == seen) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
        version
    }

    /** Wakes every [awaitChange], for a session that has stopped or failed. */
    fun wake() = synchronized(lock) {
        version++
        lock.notifyAll()
    }

    /** Deletes every file and forgets every segment. */
    fun clear() = synchronized(lock) {
        segments.forEach { it.file.delete() }
        segments.clear()
        totalUs = 0
        totalBytes = 0
        version++
        lock.notifyAll()
    }

    /**
     * The media playlist as it stands. No end tag: the window is live. The target duration is the
     * longest segment the window has held, rounded up, as the RFC asks, and never less than one.
     */
    fun playlist(): String = synchronized(lock) {
        val target = ceil(longestUs / 1_000_000.0).toLong().coerceAtLeast(1)
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(segments.firstOrNull()?.sequence ?: 0).append('\n')
            append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
            segments.forEach { segment ->
                // A tag on the window's first segment stays: the discontinuity sequence only
                // counts the ones dropped.
                if (segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:")
                append(String.format(Locale.ROOT, "%.6f", segment.durationUs / 1_000_000.0))
                append(",\n")
                append("file://").append(segment.file.absolutePath).append('\n')
            }
        }
    }

    companion object {
        /** Segments kept whatever the caps say: enough for the player to start and to follow. */
        const val MIN_SEGMENTS = 3
    }
}
