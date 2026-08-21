package com.tivicopy.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.player.PlaybackStatus
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.UiMessage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The parts of "what is playing" both shells need.
 *
 * These lived inside the phone's player screen, where the television could not reach them — which
 * is a large part of why the ten-foot shell had no transport controls at all: not because the
 * logic was hard, but because it was in the wrong file. Nothing here draws anything; each shell
 * still lays its own controls out the way its input wants.
 */

/**
 * Steps to the next aspect mode and says which one, every time.
 *
 * The announcement is not decoration. Aspect is bound to a long press on the phone and a menu on
 * the television, both easy to hit by accident, and a silently persisted Zoom reads as "the
 * picture is suddenly cropped" with nothing on screen to explain or undo it (device report).
 */
@Composable
fun rememberAspectCycler(vm: AppViewModel, current: AspectMode): () -> Unit {
    val changed = stringResource(R.string.aspect_changed)
    val labels = AspectMode.entries.associateWith { stringResource(it.labelRes()) }
    return {
        val order = AspectMode.entries
        val next = order[(order.indexOf(current) + 1) % order.size]
        ServiceLocator.settings.setAspectMode(next)
        vm.post(UiMessage(text = changed.format(labels.getValue(next))))
    }
}

/**
 * What the stream is actually delivering — resolution, frame rate, bitrate, codec — with only the
 * fields that are known.
 *
 * Isolated left-to-right so the mixed run cannot reorder inside a Hebrew layout.
 */
fun qualitySummary(status: PlaybackStatus): String? {
    val parts = buildList {
        if (status.height > 0) add("${status.height}p")
        if (status.frameRate > 0f) add("${status.frameRate.roundToInt()} fps")
        if (status.bitrate > 0) {
            add(String.format(Locale.ROOT, "%.1f Mbps", status.bitrate / 1_000_000f))
        }
        status.videoCodec?.takeIf { it.isNotBlank() }?.let { add(it.removePrefix("video/")) }
    }
    return if (parts.isEmpty()) null else TimeModel.ltr(parts.joinToString(" · "))
}

/**
 * The title and subtitle for whatever is playing.
 *
 * Every kind of playback has an identity, not only live: the ten-foot shell used to caption a
 * recording or an on-demand title with the channel info card, which knows about neither, so
 * watching anything that was not a live channel left the screen unable to say what it was.
 */
@Composable
fun nowPlayingLabels(playing: NowPlayingInfo?, nowTitle: String?): Pair<String, String?>? =
    when (playing) {
        is NowPlayingInfo.Live ->
            playing.channel.displayName to (nowTitle ?: stringResource(R.string.no_guide_data))

        is NowPlayingInfo.Catchup -> playing.programme.title to (
            playing.channel.displayName + " · " +
                TimeModel.formatClockRange(playing.programme.startUtc, playing.programme.endUtc)
            )

        is NowPlayingInfo.OnDemand ->
            playing.item.name to listOfNotNull(playing.item.year, playing.item.rating)
                .joinToString(" · ")
                .ifBlank { null }

        is NowPlayingInfo.RecordingFile -> playing.recording.title to (
            playing.recording.channelName + " · " +
                TimeModel.formatDayTime(playing.recording.startUtc)
            )

        null -> null
    }

/** Whether this kind of playback can be zapped away from — only live has a next channel. */
fun NowPlayingInfo?.isLive(): Boolean = this is NowPlayingInfo.Live

/**
 * Whether the stream can be seeked.
 *
 * Live television cannot, and offering a seek bar that does nothing is worse than offering none:
 * catch-up, on-demand and a finished recording all can.
 */
fun NowPlayingInfo?.isSeekable(): Boolean = when (this) {
    is NowPlayingInfo.Catchup, is NowPlayingInfo.OnDemand, is NowPlayingInfo.RecordingFile -> true
    is NowPlayingInfo.Live, null -> false
}
