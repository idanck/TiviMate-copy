package com.tivicopy.player.ui.home

import com.tivicopy.player.data.featured.FeaturedItem
import com.tivicopy.player.data.featured.resolveChannel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme

/**
 * Which rail this is. The *title* deliberately is not here: this file is plain Kotlin so that
 * [HomeRails.build] can be unit-tested on the JVM, and a string resource would drag Android in.
 * `ui/home/HomeScreen.kt` maps the id to a `stringResource`.
 */
enum class HomeRailId { FEATURED, CONTINUE, CHANNELS, RADIO, FAVOURITES, STARTING_SOON }

/**
 * One card on the home screen, already carrying everything the tile needs to draw itself.
 *
 * A card is not a channel: the featured rail promotes things that may lead to a web address
 * rather than a stream, and the "starting soon" rail is about programmes rather than channels.
 * Flattening all three into one shape is what lets the screen have exactly one tile composable.
 */
data class HomeCard(
    /** Unique across the whole screen, so `LazyRow` keys never collide between rails. */
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /** Drawn as the corner badge, as on the reference design. Absent when the source has none. */
    val number: Int? = null,
    /** What OK tunes. Every card drawn today has one; see `FeaturedItem.channelRef`. */
    val channelId: Long? = null,
    val startUtc: Long = 0,
    val endUtc: Long = 0,
    /**
     * Whether [startUtc]..[endUtc] is on air *now*, as opposed to a window still ahead.
     *
     * The "starting soon" rail carries a programme window too, and a progress bar drawn from it
     * would sit at zero and read as a bug. Only a card that is currently on air gets one.
     */
    val live: Boolean = false,
) {
    /** Whether there is a real, currently-running window behind this card to draw a bar from. */
    val hasProgress: Boolean get() = live && endUtc > startUtc
}

data class HomeRail(val id: HomeRailId, val cards: List<HomeCard>)

/** One snapshot of everything the home screen reads, fetched off the main thread in one go. */
data class HomeData(
    val recent: List<Channel> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val radio: List<Channel> = emptyList(),
    val favourites: List<Channel> = emptyList(),
    /** Channel id to that channel's programmes, as [com.tivicopy.player.ui.AppViewModel.guideWindow] returns them. */
    val programmes: Map<Long, List<Programme>> = emptyMap(),
    val featured: List<FeaturedItem> = emptyList(),
)

/**
 * Turns a [HomeData] snapshot into the rails the screen draws (FR-UI-24).
 *
 * Pure on purpose. This sandbox cannot compile the app at all — the Android Gradle Plugin is
 * unreachable (see CLAUDE.md) — so anything that can be a plain function over plain data is
 * one, and is covered by `HomeRailsTest` rather than by a build nobody here can run.
 *
 * The rules it encodes, all of which are the kind of thing that is easy to get subtly wrong in
 * a composable and impossible to notice without a device:
 *
 * - **An empty rail is not drawn.** A viewer with no favourites should see no Favourites row,
 *   not a heading with nothing under it. That is also what makes the featured rail free: it
 *   simply has no items until the owner configures a feed.
 * - **Every rail is capped.** A playlist of hundreds would otherwise build hundreds of tiles
 *   for a row that shows six.
 * - **Cards carry their own now-programme.** One batched guide query feeds every rail, so the
 *   caption under a tile costs nothing extra.
 */
object HomeRails {

    const val DEFAULT_MAX_PER_RAIL = 20

    /** How far ahead "starting soon" looks. Long enough to be useful, short enough to be *soon*. */
    const val STARTING_SOON_WINDOW_MS = 3L * 60 * 60 * 1000

    fun build(
        data: HomeData,
        nowUtc: Long,
        maxPerRail: Int = DEFAULT_MAX_PER_RAIL,
    ): List<HomeRail> {
        val cap = maxPerRail.coerceAtLeast(1)

        /*
         * Everything this device is willing to show, by id.
         *
         * `Dao.channels` excludes both hidden channels and channels in a hidden group;
         * `Dao.recentChannels` and `Dao.favouriteChannels` only exclude hidden *channels*, so a
         * channel inside a group the viewer hid can still come back through those two. Screening
         * both lists through the ones that were queried properly closes that here rather than in
         * SQL, where changing it would also change the existing Recently-watched view.
         */
        val visible = (data.channels + data.radio).associateBy { it.id }
        // Only playlists that were actually loaded can be screened. `recent` spans every
        // playlist on the device, so judging a channel from one that was not read here would
        // silently empty the rail rather than filter it.
        val screened = visible.values.mapTo(HashSet()) { it.playlistId }
        fun onlyVisible(channels: List<Channel>) =
            channels.filter { it.id in visible || it.playlistId !in screened }

        return listOf(
            HomeRail(HomeRailId.FEATURED, featuredCards(data.featured, visible.values.toList(), cap)),
            HomeRail(HomeRailId.CONTINUE, channelCards(onlyVisible(data.recent), "recent", data, nowUtc, cap)),
            HomeRail(HomeRailId.CHANNELS, channelCards(data.channels, "live", data, nowUtc, cap)),
            HomeRail(HomeRailId.RADIO, channelCards(data.radio, "radio", data, nowUtc, cap)),
            HomeRail(HomeRailId.FAVOURITES, channelCards(onlyVisible(data.favourites), "fav", data, nowUtc, cap)),
            HomeRail(HomeRailId.STARTING_SOON, startingSoonCards(data, nowUtc, cap)),
        ).filter { it.cards.isNotEmpty() }
    }

    /** The programme on [channelId] at [nowUtc], if the guide covers it. */
    fun nowOn(programmes: Map<Long, List<Programme>>, channelId: Long, nowUtc: Long): Programme? =
        programmes[channelId]?.firstOrNull { nowUtc >= it.startUtc && nowUtc < it.endUtc }

    /**
     * The promoted cards, with every channel reference already resolved.
     *
     * Resolution happens here rather than when OK is pressed so that a card which leads nowhere
     * is never drawn: on a remote, a tile that swallows OK is indistinguishable from a frozen
     * screen. An item naming a channel this device does not have is therefore dropped — which
     * is also what makes one feed safe to serve to installs with different playlists.
     */
    private fun featuredCards(
        items: List<FeaturedItem>,
        channels: List<Channel>,
        cap: Int,
    ): List<HomeCard> =
        items.asSequence()
            .distinctBy { it.id }
            .mapNotNull { item ->
                val channel = item.channelRef?.let { resolveChannel(it, channels) }
                    ?: return@mapNotNull null
                HomeCard(
                    key = "featured:${item.id}",
                    title = item.title,
                    subtitle = item.subtitle ?: channel.displayName,
                    imageUrl = item.imageUrl ?: channel.displayLogo,
                    number = channel.number.takeIf { it > 0 },
                    channelId = channel.id,
                )
            }
            .take(cap)
            .toList()

    private fun channelCards(
        channels: List<Channel>,
        prefix: String,
        data: HomeData,
        nowUtc: Long,
        cap: Int,
    ): List<HomeCard> =
        channels.asSequence()
            .distinctBy { it.id }
            .take(cap)
            .map { channel ->
                val now = nowOn(data.programmes, channel.id, nowUtc)
                HomeCard(
                    key = "$prefix:${channel.id}",
                    title = channel.displayName,
                    subtitle = now?.title,
                    imageUrl = channel.displayLogo,
                    number = channel.number.takeIf { it > 0 },
                    channelId = channel.id,
                    startUtc = now?.startUtc ?: 0,
                    endUtc = now?.endUtc ?: 0,
                    live = now != null,
                )
            }
            .toList()

    /**
     * Programmes starting inside the next few hours, soonest first.
     *
     * Reads the same guide map every other rail reads, so it adds no query. A channel appears
     * at most once — the row answers "what is coming up", and three consecutive programmes on
     * one channel would crowd out five other channels' worth of it.
     */
    private fun startingSoonCards(data: HomeData, nowUtc: Long, cap: Int): List<HomeCard> {
        val byId = (data.channels + data.radio).associateBy { it.id }
        val until = nowUtc + STARTING_SOON_WINDOW_MS
        return data.programmes.entries.asSequence()
            .mapNotNull { (channelId, list) ->
                val channel = byId[channelId] ?: return@mapNotNull null
                val soon = list
                    .filter { it.startUtc > nowUtc && it.startUtc <= until }
                    .minByOrNull { it.startUtc }
                    ?: return@mapNotNull null
                channel to soon
            }
            .sortedWith(compareBy({ it.second.startUtc }, { it.first.number }, { it.first.id }))
            .take(cap)
            .map { (channel, programme) ->
                HomeCard(
                    key = "soon:${channel.id}:${programme.startUtc}",
                    title = programme.title,
                    subtitle = channel.displayName,
                    imageUrl = programme.iconUrl ?: channel.displayLogo,
                    number = channel.number.takeIf { it > 0 },
                    channelId = channel.id,
                    startUtc = programme.startUtc,
                    endUtc = programme.endUtc,
                )
            }
            .toList()
    }
}
