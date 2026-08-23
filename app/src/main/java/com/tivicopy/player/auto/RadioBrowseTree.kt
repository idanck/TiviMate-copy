package com.tivicopy.player.auto

import android.net.Uri
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
import kotlinx.coroutines.runBlocking

/**
 * The Android Auto browse tree for the built-in radio playlist (ADR-015): a category folder per
 * [RadioCategory] bucket (National/Regional/genre/Other — the same client-side classification the
 * phone/TV radio screen uses, see [RadioCategoryClassifier]), each containing that category's
 * stations. Built fresh from the DB on every call, not cached — a playlist refresh or a hide/unhide
 * is picked up the next time the car asks, with no invalidation to get wrong. All methods here do
 * DB I/O and must be called off the main thread.
 */
class RadioBrowseTree {

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
     * One browsable folder per category with at least one station in it, plus a leading
     * "Favourites" folder ([FAVOURITES_ID]) when at least one station is favourited — the same
     * [Channel.favourite] flag the phone/TV radio screen already reads and writes (ADR-015: radio
     * repoints `ChannelListScreen` at a different playlist rather than duplicating its favourites
     * mechanism), so a station favourited here is favourited everywhere and vice versa.
     */
    fun categories(): List<MediaItem> {
        val channels = categorizedChannels()
        val present = channels.map { it.second }.toSet()
        val ordered = mutableListOf<RadioCategory>()
        if (RadioCategory.NATIONAL in present) ordered += RadioCategory.NATIONAL
        if (RadioCategory.REGIONAL in present) ordered += RadioCategory.REGIONAL
        present.filterIsInstance<RadioCategory.GENRE>().sortedBy { it.label }.forEach { ordered += it }
        if (RadioCategory.OTHER in present) ordered += RadioCategory.OTHER
        val categoryItems = ordered.map { category ->
            MediaItem.Builder()
                .setMediaId(categoryId(category))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(categoryLabel(category))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .build(),
                )
                .build()
        }
        val hasFavourites = channels.any { (channel, _) -> channel.favourite }
        return if (hasFavourites) listOf(favouritesFolder()) + categoryItems else categoryItems
    }

    private fun favouritesFolder(): MediaItem = MediaItem.Builder()
        .setMediaId(FAVOURITES_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(ServiceLocator.locale.getString(R.string.favourites))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .build(),
        )
        .build()

    /** The favourited stations, in provider order — the [FAVOURITES_ID] folder's children. */
    fun favouriteStations(): List<MediaItem> =
        categorizedChannels()
            .filter { (channel, _) -> channel.favourite }
            .mapNotNull { (channel, _) -> toMediaItem(channel) }

    /**
     * The playable stations inside one category folder, in provider order — also what a tapped
     * station's queue is scoped to ([RadioSessionCallback.onSetMediaItems]), so the car's native
     * skip-next/previous walks the rest of that category rather than jumping between unrelated
     * ones, which suits a driving context better than one queue across all stations.
     */
    fun stationsIn(categoryMediaId: String): List<MediaItem> =
        categorizedChannels()
            .filter { (_, category) -> categoryId(category) == categoryMediaId }
            .mapNotNull { (channel, _) -> toMediaItem(channel) }

    /** All (non-Russian) stations, flattened, in provider order. */
    fun stations(): List<MediaItem> = categorizedChannels().mapNotNull { (channel, _) -> toMediaItem(channel) }

    /**
     * The queue [RadioSessionCallback.onSetMediaItems] hands the player for a tapped station: every
     * station in *that station's own category*, not the whole playlist — one DB read, so the
     * station's category is looked up and its sibling stations resolved together. Falls back to
     * every station if the id is somehow not one [categorizedChannels] knows (should not happen —
     * a car can only tap something [stationsIn] handed it).
     */
    fun categoryQueue(stationMediaId: String): List<MediaItem> {
        val all = categorizedChannels()
        val target = all.firstOrNull { (channel, _) -> channel.id.toString() == stationMediaId }
            ?: return all.mapNotNull { (channel, _) -> toMediaItem(channel) }
        return all.filter { (_, category) -> category == target.second }
            .mapNotNull { (channel, _) -> toMediaItem(channel) }
    }

    private fun toMediaItem(channel: Channel): MediaItem? {
        val url = channel.urls.firstOrNull() ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.displayName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { channel.displayLogo?.let { setArtworkUri(Uri.parse(it)) } }
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
    }
}
