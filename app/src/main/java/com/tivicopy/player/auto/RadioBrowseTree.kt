package com.tivicopy.player.auto

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.radio.RadioCategory
import com.tivicopy.player.data.radio.RadioCategoryClassifier
import com.tivicopy.player.data.radio.RadioRegion
import kotlinx.coroutines.runBlocking

/**
 * The Android Auto browse tree for the built-in radio playlist (ADR-015): a "Favourites" folder,
 * then a category folder per [RadioCategory] bucket (National/Regional/genre/Other — the same
 * client-side classification the phone/TV radio screen uses, see [RadioCategoryClassifier]), each
 * containing that category's stations. Built fresh from the DB on every call, not cached — a
 * playlist refresh or a hide/unhide is picked up the next time the car asks, with no invalidation
 * to get wrong. All methods here do DB I/O and must be called off the main thread.
 *
 * The one piece of state it does hold is [setNowPlaying]: which station the car is playing, so
 * every list it builds can mark it. That is pushed in from [RadioMediaLibraryService]'s player
 * listener rather than read from the player here, because the player may only be touched on the
 * main thread and everything else in this class may not.
 */
class RadioBrowseTree {

    /**
     * The media id of the station currently loaded in the car's player, or null before anything
     * has played. Volatile because it is written from the player's (main) thread and read on
     * whichever background thread a browse request landed on.
     */
    @Volatile
    private var nowPlayingId: String? = null

    /** Called by [RadioMediaLibraryService] as the player moves between stations. */
    fun setNowPlaying(mediaId: String?) {
        nowPlayingId = mediaId
    }

    val root: MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(IsraelRadioStations.NAME)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .build(),
        )
        .build()

    /**
     * The bundled radio [Playlist] row.
     *
     * `App.onCreate` already seeds it, but this service can be the *first* thing to run in the
     * process — Android Auto binds it independently of `MainActivity` — so a car connecting in
     * the narrow window before that fire-and-forget bootstrap coroutine lands must not see an
     * empty station list. [com.tivicopy.player.data.repo.SourceRepository.ensureRadioPlaylist]
     * is idempotent and cheap once the row exists, so calling it again here — blocking only the
     * background thread this is invoked from, never the main thread — closes that race for free.
     */
    fun radioPlaylist(): Playlist? {
        runBlocking { ServiceLocator.sources.ensureRadioPlaylist() }
        return ServiceLocator.dao.playlists().firstOrNull { it.url == IsraelRadioStations.M3U_URL }
    }

    /**
     * Every station with Russian-language ones excluded ([RadioCategoryClassifier.isRussian]),
     * each paired with the [RadioCategory] it classifies into — the one place [categories] and
     * [stationsIn] both read from, so a station can never appear in a category folder that
     * [categories] itself does not list.
     */
    private fun categorizedChannels(): List<Pair<Channel, RadioCategory>> {
        val playlistId = radioPlaylist()?.id ?: return emptyList()
        val groups = ServiceLocator.dao.groups(playlistId, includeHidden = true)
        fun groupNameOf(channel: Channel) = groups.firstOrNull { it.id == channel.groupId }?.name
        return ServiceLocator.dao.channels(playlistId = playlistId, sort = ChannelSort.PROVIDER)
            .filterNot { RadioCategoryClassifier.isRussian(it.displayName, groupNameOf(it)) }
            .map { channel -> channel to RadioCategoryClassifier.classify(groupNameOf(channel), channel.displayName) }
    }

    /**
     * A leading "Favourites" folder ([FAVOURITES_ID]) followed by one browsable folder per
     * category with at least one station in it.
     *
     * Favourites is listed **unconditionally**, even with nothing in it yet: these folders are
     * what the car renders as its top-level tabs, and a tab that appears only once a favourite
     * exists is a tab the user cannot find in order to learn that favourites exist at all. It
     * reads the same [Channel.favourite] flag the phone/TV radio screen writes (ADR-015: radio
     * repoints `ChannelListScreen` at a different playlist rather than duplicating its favourites
     * mechanism), so a station favourited here is favourited everywhere and vice versa.
     *
     * Whichever folder holds the station currently playing says so in its subtitle, so the
     * station that is on is visible from the screen the user comes back to to change it.
     */
    fun categories(): List<MediaItem> {
        val channels = categorizedChannels()
        val present = channels.map { it.second }.toSet()
        val ordered = mutableListOf<RadioCategory>()
        if (RadioCategory.NATIONAL in present) ordered += RadioCategory.NATIONAL
        if (RadioCategory.REGIONAL in present) ordered += RadioCategory.REGIONAL
        present.filterIsInstance<RadioCategory.GENRE>().sortedBy { it.label }.forEach { ordered += it }
        if (RadioCategory.OTHER in present) ordered += RadioCategory.OTHER
        val playing = channels.firstOrNull { (channel, _) -> channel.id.toString() == nowPlayingId }
        val categoryItems = ordered.map { category ->
            folder(
                mediaId = categoryId(category),
                title = categoryLabel(category),
                subtitle = playing?.takeIf { it.second == category }?.let { nowPlayingSubtitle(it.first) },
            )
        }
        val favouritesSubtitle = playing?.takeIf { it.first.favourite }?.let { nowPlayingSubtitle(it.first) }
        return listOf(
            folder(
                mediaId = FAVOURITES_ID,
                title = ServiceLocator.locale.getString(R.string.favourites),
                subtitle = favouritesSubtitle,
            ),
        ) + categoryItems
    }

    private fun folder(mediaId: String, title: String, subtitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .build(),
        )
        .build()

    /** "Now playing · <station>", the label a folder wears while it holds the playing station. */
    private fun nowPlayingSubtitle(channel: Channel): String =
        "${ServiceLocator.locale.getString(R.string.radio_now_playing)} · ${channel.displayName}"

    /**
     * The favourited stations, in provider order — the [FAVOURITES_ID] folder's children. Listed
     * flat, with no regional grouping: a favourites list is the user's own selection across
     * categories, and splitting it by where each station broadcasts would be noise.
     */
    fun favouriteStations(): List<MediaItem> =
        categorizedChannels()
            .filter { (channel, _) -> channel.favourite }
            .mapNotNull { (channel, _) -> toMediaItem(channel) }

    /**
     * The playable stations inside one category folder — also what a tapped station's queue is
     * scoped to ([RadioSessionCallback.onSetMediaItems]), so the car's native skip-next/previous
     * walks the rest of that category rather than jumping between unrelated ones, which suits a
     * driving context better than one queue across all stations.
     *
     * Provider order, except in the Regional folder: see [byRegion].
     */
    fun stationsIn(categoryMediaId: String): List<MediaItem> =
        byRegion(categorizedChannels().filter { (_, category) -> categoryId(category) == categoryMediaId })
            .mapNotNull { (channel, region) -> toMediaItem(channel, region) }

    /** All (non-Russian) stations, flattened, in provider order. */
    fun stations(): List<MediaItem> = categorizedChannels().mapNotNull { (channel, _) -> toMediaItem(channel) }

    /** Every station logo URL, for [RadioArtwork.prefetch] to warm before anything is played. */
    fun logoUrls(): List<String> =
        categorizedChannels().mapNotNull { (channel, _) -> channel.displayLogo?.takeIf { it.isNotBlank() } }

    /**
     * The queue [RadioSessionCallback.onSetMediaItems] hands the player for a tapped station: every
     * station in *that station's own category*, not the whole playlist — one DB read, so the
     * station's category is looked up and its sibling stations resolved together. In the same order
     * the folder showed them, so skip-next moves to the station that was listed underneath. Falls
     * back to every station if the id is somehow not one [categorizedChannels] knows (should not
     * happen — a car can only tap something [stationsIn] handed it).
     *
     * Queue items — and only queue items — carry a rendered square logo ([RadioArtwork]) rather
     * than just the logo URL, because this is what ends up on the car's Now Playing screen. The
     * tapped station's logo is rendered synchronously if the warm-up has not reached it yet; the
     * rest of the queue takes whatever is already cached, since blocking playback on a download
     * for a station the user has not asked for would be the wrong trade.
     */
    fun categoryQueue(stationMediaId: String): List<MediaItem> {
        val all = categorizedChannels()
        val target = all.firstOrNull { (channel, _) -> channel.id.toString() == stationMediaId }
            ?: return all.mapNotNull { (channel, _) ->
                toMediaItem(channel, artworkData = artworkFor(channel.displayLogo, fetch = false))
            }
        return byRegion(all.filter { (_, category) -> category == target.second })
            .mapNotNull { (channel, region) ->
                val isTapped = channel.id == target.first.id
                toMediaItem(channel, region, artworkFor(channel.displayLogo, fetch = isTapped))
            }
    }

    private fun artworkFor(logoUrl: String?, fetch: Boolean): ByteArray? =
        if (fetch) RadioArtwork.square(logoUrl) else RadioArtwork.cached(logoUrl)

    /**
     * Pairs each station with the [RadioRegion] its name places it in — but only for stations in
     * [RadioCategory.REGIONAL], and sorts them so the regions come out in
     * [RadioRegion]'s own declaration order, north first. Everything else keeps provider order
     * and gets no region (Kotlin's sort is stable, and a null region sorts as the first bucket,
     * so a non-regional list comes back untouched).
     *
     * The region is what the Regional folder is separated by: each station carries it both as the
     * car's group-title hint — which draws a heading above the run of stations that share it —
     * and as its own subtitle, so the split is still legible on a head unit that ignores the hint.
     */
    private fun byRegion(entries: List<Pair<Channel, RadioCategory>>): List<Pair<Channel, RadioRegion?>> =
        entries
            .map { (channel, category) ->
                channel to RadioCategoryClassifier.region(channel.displayName)
                    .takeIf { category == RadioCategory.REGIONAL }
            }
            .sortedBy { (_, region) -> region?.ordinal ?: -1 }

    private fun toMediaItem(
        channel: Channel,
        region: RadioRegion? = null,
        artworkData: ByteArray? = null,
    ): MediaItem? {
        val url = channel.urls.firstOrNull() ?: return null
        val regionText = region?.let { regionLabel(it) }
        val subtitle = listOfNotNull(
            ServiceLocator.locale.getString(R.string.radio_now_playing)
                .takeIf { channel.id.toString() == nowPlayingId },
            regionText,
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.displayName)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                channel.displayLogo?.let { setArtworkUri(Uri.parse(it)) }
                artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                regionText?.let { setExtras(Bundle().apply { putString(EXTRA_GROUP_TITLE, it) }) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(channel.id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val ROOT_ID = "radio_root"

        /** The synthetic "Favourites" folder's media id, a sibling of the category folders. */
        const val FAVOURITES_ID = "radio_favourites"

        /**
         * The car's "group these items under a heading" hint, as a literal rather than through
         * `androidx.media3.session.MediaConstants`: this key is the documented, stable browse
         * contract Android Auto reads (it predates Media3 and is spelled identically in
         * `androidx.media.utils.MediaConstants`), and spelling it out keeps the one place this
         * app depends on it from being tied to which Media3 version happens to expose it.
         */
        private const val EXTRA_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

        /** True for a media id [categories] itself produced, as opposed to a station's own id. */
        fun isCategoryId(mediaId: String): Boolean = mediaId.startsWith(CATEGORY_ID_PREFIX)

        private const val CATEGORY_ID_PREFIX = "radio_cat_"

        private fun categoryId(category: RadioCategory): String = when (category) {
            RadioCategory.NATIONAL -> "${CATEGORY_ID_PREFIX}national"
            RadioCategory.REGIONAL -> "${CATEGORY_ID_PREFIX}regional"
            RadioCategory.OTHER -> "${CATEGORY_ID_PREFIX}other"
            is RadioCategory.GENRE -> "$CATEGORY_ID_PREFIX${category.label}"
        }

        // Same strings the phone/TV radio screen uses, read through the app's own locale context
        // (see ServiceLocator.locale) rather than duplicated here, so a language change moves both.
        private fun categoryLabel(category: RadioCategory): String = when (category) {
            RadioCategory.NATIONAL -> ServiceLocator.locale.getString(R.string.radio_category_national)
            RadioCategory.REGIONAL -> ServiceLocator.locale.getString(R.string.radio_category_regional)
            RadioCategory.OTHER -> ServiceLocator.locale.getString(R.string.radio_category_other)
            is RadioCategory.GENRE -> category.label
        }

        private fun regionLabel(region: RadioRegion): String = ServiceLocator.locale.getString(
            when (region) {
                RadioRegion.NORTH -> R.string.radio_region_north
                RadioRegion.CENTER -> R.string.radio_region_center
                RadioRegion.JERUSALEM -> R.string.radio_region_jerusalem
                RadioRegion.SOUTH -> R.string.radio_region_south
                RadioRegion.UNKNOWN -> R.string.radio_region_other
            },
        )
    }
}
