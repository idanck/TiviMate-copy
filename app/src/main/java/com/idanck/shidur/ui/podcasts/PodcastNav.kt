package com.idanck.shidur.ui.podcasts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The five parts at the top of the podcasts screen (`PodTabs`, ADR-067). */
enum class PodcastTab { FOR_YOU, LIBRARY, FAVOURITES, HISTORY, DISCOVER }

/** Where the podcasts screen is: one of its parts, a show's page, or the player. */
sealed interface PodcastPage {
    data class Home(val tab: PodcastTab) : PodcastPage
    data class Show(val podcastId: Long) : PodcastPage
    data object Player : PodcastPage
}

/**
 * The podcasts screen's own way back (ADR-067): the player and a show's page step back to where
 * they were opened from, and only the screen's parts leave it. Held by the view model so the
 * television's Back, which the activity owns, can climb it too.
 */
class PodcastNav {
    private val stack = ArrayDeque<PodcastPage>()
    private val _page = MutableStateFlow<PodcastPage>(PodcastPage.Home(PodcastTab.FOR_YOU))
    val page: StateFlow<PodcastPage> = _page

    /** Opens [page] over the current one; a part of the screen replaces the stack. */
    fun open(page: PodcastPage) {
        if (page is PodcastPage.Home) {
            stack.clear()
        } else if (_page.value != page) {
            stack.addLast(_page.value)
        }
        _page.value = page
    }

    /** One step back; false when already on a part of the screen, so the caller leaves it. */
    fun back(): Boolean {
        val previous = stack.removeLastOrNull() ?: return false
        _page.value = previous
        return true
    }

    val atHome: Boolean get() = _page.value is PodcastPage.Home
}
