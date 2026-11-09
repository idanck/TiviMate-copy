package com.idanck.shidur.auto

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
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
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
 * [com.idanck.shidur.data.model.Channel.favourite] on whatever station is currently playing —
 * the same flag and DB column [com.idanck.shidur.ui.AppViewModel.setFavourite] writes for the
 * phone/TV star toggle, so marking a station favourite from the car is visible in the app and
 * vice versa. [RadioBrowseTree] never caches, so the Favourites folder picks up the change the
 * next time the car browses into it — no explicit invalidation needed.
 *
 * [notifyBrowseRefresh] is the one thing that does need telling: a station starting or changing
 * makes every list showing the "now playing" marker stale, and a car that has already loaded a
 * folder will not ask for it again on its own. Which folders those are is exactly the set
 * [onGetChildren] has answered for, so that is what is recorded and re-notified — nothing has to
 * enumerate the tree, and a folder the car has never opened is never notified about.
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
                future { LibraryResult.ofItemList(paged(parentId, browseTree.categories(), page, pageSize), params) }
            parentId == RadioBrowseTree.FAVOURITES_ID ->
                future {
                    LibraryResult.ofItemList(paged(parentId, browseTree.favouriteStations(), page, pageSize), params)
                }
            RadioBrowseTree.isCategoryId(parentId) ->
                future {
                    LibraryResult.ofItemList(paged(parentId, browseTree.stationsIn(parentId), page, pageSize), params)
                }
            else -> Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
    }

    private fun paged(parentId: String, items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        servedChildCounts[parentId] = items.size
        return ImmutableList.copyOf(items.browsePage(page, pageSize))
    }

    /**
     * How many children each folder was last served, keyed by parent media id — both the record of
     * which folders a car is actually showing and the count
     * [MediaLibrarySession.notifyChildrenChanged] wants. Concurrent because it is written from the
     * IO executor a browse request is answered on and read from the player's thread.
     */
    private val servedChildCounts = ConcurrentHashMap<String, Int>()

    /**
     * Re-announces every folder this session has served, so the car reloads it and picks up a
     * moved "now playing" marker. Must be called on the session's application thread — the player
     * listener in [RadioMediaLibraryService] that calls it already runs there.
     */
    fun notifyBrowseRefresh(session: MediaLibrarySession) {
        servedChildCounts.forEach { (parentId, count) -> session.notifyChildrenChanged(parentId, count, null) }
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
        const val TOGGLE_FAVOURITE_ACTION = "com.idanck.shidur.auto.action.TOGGLE_FAVOURITE"
    }
}

/**
 * The window of [this] list a browsing controller actually asked for.
 *
 * Ignoring [MediaLibrarySession.Callback.onGetChildren]'s `page`/`pageSize` and answering every
 * page with the whole list looks harmless while the lists are short, but a host that does page —
 * the contract says it may — then reads the same stations back as page 1, and shows each of them
 * twice. Media3 passes [Int.MAX_VALUE] as the size when the controller wants everything, which is
 * also the only value for which `page * pageSize` could overflow an `Int`, so the multiplication
 * is done in `Long` and that case answered without it.
 *
 * Top-level and `internal` rather than a private method: this is the one piece of the callback
 * that is pure list arithmetic, and it is the piece worth pinning in a test without standing up a
 * [MediaLibrarySession].
 */
internal fun <T> List<T>.browsePage(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
    val from = page.toLong() * pageSize.toLong()
    if (from >= size) return emptyList()
    return drop(from.toInt()).take(pageSize)
}
