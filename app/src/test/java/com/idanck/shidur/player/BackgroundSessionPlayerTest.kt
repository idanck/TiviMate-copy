package com.idanck.shidur.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The background card's player (ADR-068): what the session is told is playing, and where its
 * buttons go.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundSessionPlayerTest {

    /** A player that offers play/pause and stop, and records whether it was stopped. */
    private class Engine : SimpleBasePlayer(Looper.getMainLooper()) {
        var stopped = false

        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP).build(),
            )
            .build()

        override fun handleStop(): ListenableFuture<*> {
            stopped = true
            return Futures.immediateVoidFuture()
        }
    }

    private class Recorder : Player.Listener {
        val metadata = mutableListOf<MediaMetadata>()
        val commands = mutableListOf<Player.Commands>()

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            metadata += mediaMetadata
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            commands += availableCommands
        }
    }

    private val engine = Engine()
    private val skips = mutableListOf<Int>()
    private var stops = 0
    private val player = BackgroundSessionPlayer(engine, onSkip = { skips += it }, onStop = { stops++ })

    private val station = BackgroundSessionPlayer.Display(
        title = "Kan 88",
        line = "Artist - Song",
        artworkUrl = "https://example.com/kan88.png",
        stations = true,
    )
    private val episode = station.copy(title = "Episode 12", line = "A podcast", stations = false)

    @Test
    fun `the card names the station, the song it announces and its logo`() {
        player.show(station)
        val metadata = player.mediaMetadata
        assertEquals("Kan 88", metadata.title.toString())
        assertEquals("Artist - Song", metadata.artist.toString())
        assertEquals("Kan 88", metadata.station.toString())
        assertEquals("https://example.com/kan88.png", metadata.artworkUri.toString())
    }

    @Test
    fun `an episode is not a station`() {
        player.show(episode)
        assertNull(player.mediaMetadata.station)
    }

    @Test
    fun `a station's previous and next change station`() {
        player.show(station)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        player.seekToNextMediaItem()
        player.seekToNext()
        player.seekToPreviousMediaItem()
        player.seekToPrevious()
        assertEquals(listOf(1, 1, -1, -1), skips)
    }

    @Test
    fun `an episode's buttons stay the player's own`() {
        player.show(episode)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun `stop asks the service and leaves the engine's player alone`() {
        player.stop()
        assertEquals(1, stops)
        assertFalse(engine.stopped)
    }

    @Test
    fun `a new card reaches the listeners, with the commands when they change`() {
        val recorder = Recorder()
        player.addListener(recorder)
        player.show(station)
        assertEquals("Kan 88", recorder.metadata.last().title.toString())
        assertTrue(recorder.commands.last().contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        player.show(station.copy(line = "Another song"))
        assertEquals("Another song", recorder.metadata.last().artist.toString())
        assertEquals(1, recorder.commands.size)

        player.removeListener(recorder)
        player.show(episode)
        assertEquals(2, recorder.metadata.size)
    }

    @Test
    fun `the stream's own metadata is replaced by the card's`() {
        player.show(station)
        val recorder = Recorder()
        val raw = MediaMetadata.Builder().setTitle("ARTIST - SONG").build()
        player.MetadataListener(recorder).onMediaMetadataChanged(raw)
        assertEquals("Kan 88", recorder.metadata.single().title.toString())
    }

    @Test
    fun `the wrapper forwards every callback a listener has`() {
        val missing = Player.Listener::class.java.methods
            .filter { it.declaringClass == Player.Listener::class.java }
            .filter { method ->
                runCatching {
                    BackgroundSessionPlayer.MetadataListener::class.java
                        .getDeclaredMethod(method.name, *method.parameterTypes)
                }.isFailure
            }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
