package com.tivicopy.player.auto

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/**
 * Browse tree + queue resolution for the radio [MediaLibrarySession] (ADR-015).
 *
 * The tree is two levels: [RadioBrowseTree.ROOT_ID] holds one browsable folder per category
 * (Favourites/National/Regional/genre/Other), and each folder holds its own stations.
 * [onGetChildren] tells [RadioBrowseTree.FAVOURITES_ID], a category id ([RadioBrowseTree.isCategoryId]),
 * and the root apart.
 *
 * [onSetMediaItems] is where a single tapped station becomes a full queue: the car passes just
 * the one selected [MediaItem] (carrying only the `mediaId` set in [RadioBrowseTree]), and this
 * resolves it against that station's own category ([RadioBrowseTree.categoryQueue]) so the
 * underlying player gets that category as its playlist, positioned at the one the user picked —
 * which is what makes the car's native skip-next/previous controls walk the other stations in the
 * same category for free. That holds regardless of whether the station was tapped from its
 * category folder or from Favourites, so a favourited station's queue is still its category, not
 * the favourites list — simplest to reason about, and consistent with how it behaves everywhere
 * else a station can be reached from.
 *
 * [onCustomCommand] handles [TOGGLE_FAVOURITE_ACTION]: a "Favourite" [CommandButton] Android Auto
 * shows on the Now Playing screen next to the transport controls, which flips
 * [com.tivicopy.player.data.model.Channel.favourite] on whatever station is currently playing —
 * the same flag and DB column [com.tivicopy.player.ui.AppViewModel.setFavourite] writes for the
 * phone/TV star toggle, so marking a station favourite from the car is visible in the app and
 * vice versa. [RadioBrowseTree] never caches, so the Favourites folder picks up the change the
 * next time the car browses into it — no explicit invalidation needed.
 */
class RadioSessionCallback(private val browseTree: RadioBrowseTree) : MediaLibrarySession.Callback {

    private val ioExecutor: Executor = Dispatchers.IO.asExecutor()

    private fun <T> future(block: () -> T): ListenableFuture<T> = Futures.submit(Callable(block), ioExecutor)

    private val toggleFavouriteCommand = SessionCommand(TOGGLE_FAVOURITE_ACTION, Bundle.EMPTY)

    private val toggleFavouriteButton = CommandButton.Builder()
        .setDisplayName(ServiceLocator.locale.getString(R.string.action_favourite))
        .setSessionCommand(toggleFavouriteCommand)
        .setIconResId(R.drawable.ic_radio_favourite)
        .build()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
            .add(toggleFavouriteCommand)
            .build()
        return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        session.setCustomLayout(ImmutableList.of(toggleFavouriteButton))
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != TOGGLE_FAVOURITE_ACTION) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        val channelId = session.player.currentMediaItem?.mediaId?.toLongOrNull()
            ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        return future {
            val isFavourite = ServiceLocator.dao.channel(channelId)?.favourite ?: false
            ServiceLocator.dao.setChannelFlags(channelId, favourite = !isFavourite)
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(browseTree.root, params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when {
            parentId == RadioBrowseTree.ROOT_ID ->
                future { LibraryResult.ofItemList(ImmutableList.copyOf(browseTree.categories()), params) }
            parentId == RadioBrowseTree.FAVOURITES_ID ->
                future { LibraryResult.ofItemList(ImmutableList.copyOf(browseTree.favouriteStations()), params) }
            RadioBrowseTree.isCategoryId(parentId) ->
                future { LibraryResult.ofItemList(ImmutableList.copyOf(browseTree.stationsIn(parentId)), params) }
            else -> Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        val item = if (RadioBrowseTree.isCategoryId(mediaId) || mediaId == RadioBrowseTree.FAVOURITES_ID) {
            browseTree.categories().firstOrNull { it.mediaId == mediaId }
        } else {
            browseTree.stations().firstOrNull { it.mediaId == mediaId }
        }
        item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val requestedId = mediaItems.firstOrNull()?.mediaId
        return future {
            val queue = requestedId?.let { browseTree.categoryQueue(it) } ?: browseTree.stations()
            val resolvedIndex = queue.indexOfFirst { it.mediaId == requestedId }
            val index = if (resolvedIndex >= 0) resolvedIndex else 0
            MediaSession.MediaItemsWithStartPosition(queue, index, /* startPositionMs = */ 0L)
        }
    }

    private companion object {
        /** Custom [SessionCommand] action id for the Now Playing "Favourite" [CommandButton]. */
        const val TOGGLE_FAVOURITE_ACTION = "com.tivicopy.player.auto.action.TOGGLE_FAVOURITE"
    }
}
