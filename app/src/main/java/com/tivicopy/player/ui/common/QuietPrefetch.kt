package com.tivicopy.player.ui.common

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Composes a row of cards ahead of the remote while the screen is still, so a press that scrolls
 * the row finds its next card already built.
 *
 * A lazy row composes the card past its edge in the time left over after a frame, and only when
 * it expects that to fit before the next one. A channel card takes 15–18ms to compose on a Google
 * TV Streamer — more than a whole frame — so it never fits: the card was composed in the frame
 * that scrolled to it (a 92ms frame on home, as four arrived at once), and the request, never
 * done, asked again every frame, waking the app sixty times a second for as long as the remote
 * stayed on that row (device trace, 0.20.8). Home's list of rows had the same loop and gave up
 * composing ahead altogether in 0.20.7 (`NoRowPrefetch`).
 *
 * This scheduler waits instead: for the screen to have drawn nothing for [QUIET_MS], which is
 * after the last frame of a press's motion and before the next press in all but a held key; then
 * it composes for up to [BUDGET_MS] at once, always at least one request, and gives the rest the
 * next quiet moment. A press arriving meanwhile waits for the card being composed, which is less
 * than the frame it would have cost.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class QuietPrefetchScheduler(private val view: View) : PrefetchScheduler, Runnable {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PrefetchRequest>()
    private var posted = false
    private var released = false

    /** Unbounded for the first request of a run, so every run finishes one; the budget after. */
    private val scope = object : PrefetchRequestScope {
        var deadline = Long.MAX_VALUE
        override fun availableTimeNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0L)
    }

    override fun schedulePrefetch(prefetchRequest: PrefetchRequest) {
        if (released) return
        queue.addLast(prefetchRequest)
        post(QUIET_MS)
    }

    private fun post(delayMs: Long) {
        if (posted || released) return
        posted = true
        handler.postDelayed(this, delayMs)
    }

    override fun run() {
        posted = false
        if (released || queue.isEmpty() || !view.isAttachedToWindow) return
        // `drawingTime` is when the window last drew, on the clock `uptimeMillis` reads.
        val still = SystemClock.uptimeMillis() - view.drawingTime
        if (still < QUIET_MS) {
            post(QUIET_MS - still)
            return
        }
        scope.deadline = Long.MAX_VALUE
        val stop = System.nanoTime() + BUDGET_MS * 1_000_000L
        while (queue.isNotEmpty()) {
            val request = queue.first()
            val more = with(request) { scope.execute() }
            if (more) break
            queue.removeFirst()
            scope.deadline = stop
            if (System.nanoTime() >= stop) break
        }
        if (queue.isNotEmpty()) post(QUIET_MS)
    }

    /** Drops what is waiting, with the screen it was composing for. */
    fun release() {
        released = true
        handler.removeCallbacks(this)
        queue.clear()
    }

    private companion object {
        /** Longer than the gap between two frames of any motion, shorter than a press. */
        const val QUIET_MS = 120L

        /** What one quiet moment composes: two or three cards. */
        const val BUDGET_MS = 40L
    }
}

/** One [QuietPrefetchScheduler] for a screen, released with it. */
@Composable
internal fun rememberQuietPrefetchScheduler(): QuietPrefetchScheduler {
    val view = LocalView.current
    val scheduler = remember(view) { QuietPrefetchScheduler(view) }
    DisposableEffect(scheduler) { onDispose { scheduler.release() } }
    return scheduler
}

/**
 * Keeps [ahead] items composed past each edge of a lazy row, on a [QuietPrefetchScheduler]: the
 * next cards either way are built before the remote gets there, whichever way it goes.
 *
 * One per list; it remembers what it asked for. Items that fall back from the edge are let go, so
 * a row keeps at most [ahead] cards a side composed beyond what it shows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class AheadPrefetch(
    override val prefetchScheduler: PrefetchScheduler,
    private val ahead: Int = 2,
) : LazyListPrefetchStrategy {
    private val handles = HashMap<Int, LazyLayoutPrefetchState.PrefetchHandle>()
    private var itemCount = -1

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        // A rebuilt row can put other cards at the same places: start again rather than keep
        // asking by place for cards that have moved.
        if (layoutInfo.totalItemsCount != itemCount) {
            itemCount = layoutInfo.totalItemsCount
            handles.values.forEach { it.cancel() }
            handles.clear()
        }
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val first = visible.first().index
        val last = visible.last().index
        val wanted = ((first - ahead until first) + (last + 1..last + ahead))
            .filter { it in 0 until layoutInfo.totalItemsCount }
            .toSet()
        // Cancelling an item that has since come on screen does nothing, so this is safe either way.
        handles.keys.filter { it !in wanted }.forEach { handles.remove(it)?.cancel() }
        wanted.forEach { index -> if (index !in handles) handles[index] = schedulePrefetch(index) }
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        repeat(ahead) { schedulePrefetch(firstVisibleItemIndex + it) }
    }
}
