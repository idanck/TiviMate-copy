package com.tivicopy.player.auto

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/**
 * Browse tree + queue resolution for the radio [MediaLibrarySession] (ADR-015).
 *
 * The tree is two levels: [RadioBrowseTree.ROOT_ID] holds one browsable folder per category
 * (National/Regional/genre/Other), and each category folder holds its own stations. [onGetChildren]
 * tells the two apart with [RadioBrowseTree.isCategoryId].
 *
 * [onSetMediaItems] is where a single tapped station becomes a full queue: the car passes just
 * the one selected [MediaItem] (carrying only the `mediaId` set in [RadioBrowseTree]), and this
 * resolves it against that station's own category ([RadioBrowseTree.categoryQueue]) so the
 * underlying player gets that category as its playlist, positioned at the one the user picked —
 * which is what makes the car's native skip-next/previous controls walk the other stations in the
 * same category for free, with no custom session commands.
 */
class RadioSessionCallback(private val browseTree: RadioBrowseTree) : MediaLibrarySession.Callback {

    private val ioExecutor: Executor = Dispatchers.IO.asExecutor()

    private fun <T> future(block: () -> T): ListenableFuture<T> = Futures.submit(Callable(block), ioExecutor)

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
        val item = if (RadioBrowseTree.isCategoryId(mediaId)) {
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
}
