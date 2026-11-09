package com.idanck.shidur.data.model

enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

enum class OutputFormat { TS, HLS;
    companion object {
        fun from(name: String?): OutputFormat =
            entries.firstOrNull { it.name.equals(name, true) } ?: TS
    }
}

/** How a channel's archive URL is built (FR-CU-03). */
enum class CatchupMode { NONE, DEFAULT, APPEND, SHIFT, FLUSSONIC, XC;
    companion object {
        fun from(raw: String?): CatchupMode = when (raw?.trim()?.lowercase()) {
            null, "", "0", "none" -> NONE
            "default", "1" -> DEFAULT
            "append" -> APPEND
            "shift", "timeshift" -> SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts" -> FLUSSONIC
            "xc", "xtream", "xtream-codes" -> XC
            else -> DEFAULT
        }
    }
}

enum class DecoderMode { AUTO, HARDWARE, SOFTWARE }

/**
 * How the channel list is ordered (FR-ORG-06).
 *
 * [PROVIDER] is the playlist's own order as adjusted by manual moves, and stays the default.
 * [orderBy] is a fixed SQL fragment chosen by the enum, never assembled from user input.
 */
enum class ChannelSort(val orderBy: String) {
    PROVIDER("sort_index, number, name"),
    NAME_ASC("COALESCE(NULLIF(custom_name, ''), name) COLLATE NOCASE, number"),
    MOST_WATCHED("watch_count DESC, last_watched_utc DESC, name"),
    RECENTLY_WATCHED("last_watched_utc DESC, name"),
}

data class Playlist(
    val id: Long = 0,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val outputFormat: OutputFormat = OutputFormat.TS,
    val userAgent: String? = null,
    /** Extra request headers, serialised as JSON in storage (FR-NET-02). */
    val headers: Map<String, String> = emptyMap(),
    val epgUrl: String? = null,
    val refreshIntervalMinutes: Int = 12 * 60,
    val enabled: Boolean = true,
    val colour: Int = 0xFF3D7BFF.toInt(),
    val lastRefreshUtc: Long = 0,
    val lastRefreshOk: Boolean = true,
    val lastRefreshMessage: String? = null,
    val sortIndex: Int = 0,
)

data class ChannelGroup(
    val id: Long = 0,
    val playlistId: Long,
    val name: String,
    /** Provider-supplied groups are recreated on refresh; custom groups are user-owned. */
    val custom: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val sortIndex: Int = 0,
)

data class Channel(
    val id: Long = 0,
    val playlistId: Long,
    /** Stable identity used by a non-destructive refresh (FR-SRC-11). */
    val identity: String,
    val streamId: String? = null,
    val tvgId: String? = null,
    val name: String,
    /** User rename; the provider name stays in [name] so a refresh can update it. */
    val customName: String? = null,
    val logoUrl: String? = null,
    val customLogoUrl: String? = null,
    val groupId: Long? = null,
    val number: Int = 0,
    /** Primary URL plus any declared alternates, used in order for failover (FR-PLB-17). */
    val urls: List<String> = emptyList(),
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val licenseType: String? = null,
    val licenseKey: String? = null,
    val catchupMode: CatchupMode = CatchupMode.NONE,
    val catchupSource: String? = null,
    val catchupDays: Int = 0,
    val tvgShiftMinutes: Int = 0,
    val epgOffsetMinutes: Int? = null,
    val videoDecoder: DecoderMode = DecoderMode.AUTO,
    val audioDecoder: DecoderMode = DecoderMode.AUTO,
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val sortIndex: Int = 0,
    /** Attributes we do not understand yet are preserved verbatim (FR-SRC-05). */
    val extraAttributes: Map<String, String> = emptyMap(),
    /** EPG channel id the user bound manually; sticky across refreshes (FR-EPG-08). */
    val manualEpgId: String? = null,
    val resolvedEpgId: String? = null,
    /** How [resolvedEpgId] was arrived at; drives the guide-match diagnostic (FR-EPG-09). */
    val matchKind: String = "NONE",
    val watchCount: Int = 0,
    val lastWatchedUtc: Long = 0,
) {
    val displayName: String get() = customName ?: name
    val displayLogo: String? get() = customLogoUrl ?: logoUrl
    val hasCatchup: Boolean get() = catchupMode != CatchupMode.NONE && catchupDays > 0
}

data class Programme(
    val id: Long = 0,
    val epgChannelId: String,
    val startUtc: Long,
    val endUtc: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** XMLTV `episode-num` in whatever system the source used; drives series recording. */
    val episodeId: String? = null,
    val rating: String? = null,
) {
    val durationMillis: Long get() = (endUtc - startUtc).coerceAtLeast(0)
    val hasSeriesMetadata: Boolean get() = episodeId != null || seasonNumber != null || episodeNumber != null
}

/** A channel entry discovered in an XMLTV document, used by the matcher. */
data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

data class VodItem(
    val id: Long = 0,
    val playlistId: Long,
    val streamId: String,
    val name: String,
    val categoryName: String? = null,
    val posterUrl: String? = null,
    val plot: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val durationSeconds: Int = 0,
    val containerExtension: String = "mp4",
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val favourite: Boolean = false,
    val resumePositionMs: Long = 0,
    val lastWatchedUtc: Long = 0,
)

enum class RecordingState { SCHEDULED, RECORDING, COMPLETED, PARTIAL, FAILED, CANCELLED }

data class Recording(
    val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val title: String,
    val startUtc: Long,
    val endUtc: Long,
    val state: RecordingState = RecordingState.SCHEDULED,
    val filePath: String? = null,
    val fileSizeBytes: Long = 0,
    val message: String? = null,
    val seriesKey: String? = null,
    val watched: Boolean = false,
    val resumePositionMs: Long = 0,
    val gapCount: Int = 0,
)

/** Result of a playlist refresh, surfaced to the user (FR-SRC-13). */
data class RefreshReport(
    val added: Int = 0,
    val removed: Int = 0,
    val renamed: Int = 0,
    val total: Int = 0,
    val ok: Boolean = true,
    val message: String? = null,
)

data class EpgMatchStats(
    val totalChannels: Int = 0,
    val matchedExact: Int = 0,
    val matchedName: Int = 0,
    val matchedFuzzy: Int = 0,
    val matchedManual: Int = 0,
    val unmatched: Int = 0,
) {
    val matched: Int get() = matchedExact + matchedName + matchedFuzzy + matchedManual
    val accuracy: Double get() = if (totalChannels == 0) 0.0 else matched.toDouble() / totalChannels
}
