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
 * [onSetMediaItems] is where a single tapped station becomes a full queue: the car passes just
 * the one selected [MediaItem] (carrying only the `mediaId` set in [RadioBrowseTree]), and this
 * resolves it against the whole ordered station list so the underlying player gets every station
 * as its playlist, positioned at the one the user picked — which is what makes the car's native
 * skip-next/previous controls walk the other stations for free, with no custom session commands.
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
        if (parentId != RadioBrowseTree.ROOT_ID) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
        return future { LibraryResult.ofItemList(ImmutableList.copyOf(browseTree.stations()), params) }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        browseTree.stations().firstOrNull { it.mediaId == mediaId }
            ?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
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
            val stations = browseTree.stations()
            val resolvedIndex = stations.indexOfFirst { it.mediaId == requestedId }
            val index = if (resolvedIndex >= 0) resolvedIndex else 0
            MediaSession.MediaItemsWithStartPosition(stations, index, /* startPositionMs = */ 0L)
        }
    }
}
