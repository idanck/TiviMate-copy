package com.idanck.shidur.auto

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.builtin.IsraelRadioStations
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.ChannelSort
import com.idanck.shidur.data.model.Playlist
import com.idanck.shidur.data.radio.RadioCategoryClassifier
import com.idanck.shidur.data.radio.RadioRegion
import com.idanck.shidur.data.radio.RadioShelf
import kotlinx.coroutines.runBlocking

/**
 * The Android Auto browse tree for the built-in radio playlist (ADR-015), on the radio menu's
 * shelves (ADR-050, brought to the car by ADR-064): a "Favourites" folder, then national, the
 * regions and the genres — the same split the phone's chips and the television's strip use.
 *
 * Four folders at most, because a car shows four tabs at the root and may drop the rest
 * (`BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`, four in most cars). So the regions are one folder
 * with a heading per region — north, centre, Jerusalem, south, other regions — and the genres one
 * folder with a heading per genre, rather than a tab each. Before this the folders were
 * [com.idanck.shidur.data.radio.RadioCategory], the feed's `group-title`s, which are genres now:
 * nearly every station sat in "Other" and the regions never showed.
 *
 * Built fresh from the DB on every call, not cached — a playlist refresh or a hide/unhide is
 * picked up the next time the car asks, with no invalidation to get wrong. All methods here do DB
 * I/O and must be called off the main thread.
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
     * empty station list. [com.idanck.shidur.data.repo.SourceRepository.ensureRadioPlaylist]
     * is idempotent and cheap once the row exists, so calling it again here — blocking only the
     * background thread this is invoked from, never the main thread — closes that race for free.
     * It does not bring back a built-in list the viewer deleted.
     */
    fun radioPlaylist(): Playlist? {
        runBlocking { ServiceLocator.sources.ensureRadioPlaylist() }
        // Removed or turned off in Settings: the car offers no stations either.
        return ServiceLocator.dao.playlists().firstOrNull { it.enabled && it.url == IsraelRadioStations.M3U_URL }
    }

    /**
     * Every station with Russian-language ones excluded ([RadioCategoryClassifier.isRussian]),
     * each with the shelves it is on ([RadioCategoryClassifier.shelves]: its place, and its genre
     * if its name says one) — the one place [categories], [stationsIn] and [categoryQueue] all
     * read from, so a station can never appear in a folder that [categories] does not list.
     */
    private fun shelvedChannels(): List<Pair<Channel, List<RadioShelf>>> {
        val playlistId = radioPlaylist()?.id ?: return emptyList()
        val groups = ServiceLocator.dao.groups(playlistId, includeHidden = true)
        fun groupNameOf(channel: Channel) = groups.firstOrNull { it.id == channel.groupId }?.name
        return ServiceLocator.dao.channels(playlistId = playlistId, sort = ChannelSort.PROVIDER)
            .filterNot { RadioCategoryClassifier.isRussian(it.displayName, groupNameOf(it)) }
            .map { channel -> channel to RadioCategoryClassifier.shelves(groupNameOf(channel), channel.displayName) }
    }

    /**
     * A leading "Favourites" folder ([FAVOURITES_ID]), then national, the regions and the genres,
     * each only when a station is in it.
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
        val channels = shelvedChannels()
        val present = channels.flatMapTo(HashSet<CarFolder>()) { (_, shelves) -> shelves.map { it.carFolder() } }
        val playing = channels.firstOrNull { (channel, _) -> channel.id.toString() == nowPlayingId }
        val playingIn = playing?.second.orEmpty().map { it.carFolder() }.toSet()
        val folders = CarFolder.entries.filter { it in present }.map { folder ->
            folder(
                mediaId = folderId(folder),
                title = folderLabel(folder),
                subtitle = playing?.takeIf { folder in playingIn }?.let { nowPlayingSubtitle(it.first) },
            )
        }
        val favouritesSubtitle = playing?.takeIf { it.first.favourite }?.let { nowPlayingSubtitle(it.first) }
        return listOf(
            folder(
                mediaId = FAVOURITES_ID,
                title = ServiceLocator.locale.getString(R.string.favourites),
                subtitle = favouritesSubtitle,
            ),
        ) + folders
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
     * flat, with no headings: a favourites list is the user's own selection across shelves, and
     * splitting it by where each station broadcasts would be noise.
     */
    fun favouriteStations(): List<MediaItem> =
        shelvedChannels()
            .filter { (channel, _) -> channel.favourite }
            .mapNotNull { (channel, _) -> toMediaItem(channel) }

    /**
     * The playable stations inside one folder, in the order [inCarFolder] gives them: provider
     * order in national, under a heading per region or per genre in the other two.
     */
    fun stationsIn(folderMediaId: String): List<MediaItem> {
        val folder = CarFolder.entries.firstOrNull { folderId(it) == folderMediaId } ?: return emptyList()
        return shelvedChannels().inCarFolder(folder)
            .mapNotNull { (channel, shelf) -> toMediaItem(channel, headingOf(shelf)) }
    }

    /** All (non-Russian) stations, flattened, in provider order. */
    fun stations(): List<MediaItem> = shelvedChannels().mapNotNull { (channel, _) -> toMediaItem(channel) }

    /** Every station logo URL, for [RadioArtwork.prefetch] to warm before anything is played. */
    fun logoUrls(): List<String> =
        shelvedChannels().mapNotNull { (channel, _) -> channel.displayLogo?.takeIf { it.isNotBlank() } }

    /**
     * The queue [RadioSessionCallback.onSetMediaItems] hands the player for a tapped station: the
     * folder of *that station's place* — national, or the regions — in the order the folder shows
     * it, so the car's skip-next moves to the station listed underneath and, at the end of a
     * region, on to the next region. Its place rather than the folder it was tapped in, because the
     * car hands back only the station's id: a station is in exactly one place, and in at most one
     * genre besides. Falls back to every station if the id is somehow not one [shelvedChannels]
     * knows (should not happen — a car can only tap something [stationsIn] handed it).
     *
     * Queue items — and only queue items — carry a rendered square logo ([RadioArtwork]) rather
     * than just the logo URL, because this is what ends up on the car's Now Playing screen. The
     * tapped station's logo is rendered synchronously if the warm-up has not reached it yet; the
     * rest of the queue takes whatever is already cached, since blocking playback on a download
     * for a station the user has not asked for would be the wrong trade.
     */
    fun categoryQueue(stationMediaId: String): List<MediaItem> {
        val all = shelvedChannels()
        val target = all.firstOrNull { (channel, _) -> channel.id.toString() == stationMediaId }
            ?: return all.mapNotNull { (channel, _) ->
                toMediaItem(channel, artworkData = artworkFor(channel.displayLogo, fetch = false))
            }
        return all.inCarFolder(placeFolder(target.second))
            .mapNotNull { (channel, shelf) ->
                val isTapped = channel.id == target.first.id
                toMediaItem(channel, headingOf(shelf), artworkFor(channel.displayLogo, fetch = isTapped))
            }
    }

    private fun artworkFor(logoUrl: String?, fetch: Boolean): ByteArray? =
        if (fetch) RadioArtwork.square(logoUrl) else RadioArtwork.cached(logoUrl)

    /**
     * The words a station is grouped under in its folder: its region in the regions folder, its
     * genre in the genres folder, nothing in national. Each station carries it both as the car's
     * group-title hint — which draws a heading above the run of stations that share it — and as
     * its own subtitle, so the split is still legible on a head unit that ignores the hint.
     */
    private fun headingOf(shelf: RadioShelf): String? = when (shelf) {
        RadioShelf.National -> null
        is RadioShelf.Regional -> regionLabel(shelf.region)
        is RadioShelf.Genre -> shelf.label
    }

    private fun toMediaItem(
        channel: Channel,
        heading: String? = null,
        artworkData: ByteArray? = null,
    ): MediaItem? {
        val url = channel.urls.firstOrNull() ?: return null
        val subtitle = listOfNotNull(
            ServiceLocator.locale.getString(R.string.radio_now_playing)
                .takeIf { channel.id.toString() == nowPlayingId },
            heading,
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
                heading?.let { setExtras(Bundle().apply { putString(EXTRA_GROUP_TITLE, it) }) }
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

        /** The synthetic "Favourites" folder's media id, a sibling of the shelf folders. */
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

        // "national" as before, so a car that remembered the national tab finds it again.
        private fun folderId(folder: CarFolder): String = CATEGORY_ID_PREFIX + when (folder) {
            CarFolder.NATIONAL -> "national"
            CarFolder.REGIONS -> "regions"
            CarFolder.GENRES -> "genres"
        }

        // Same strings the phone/TV radio screen uses where there is one, read through the app's
        // own locale context (see ServiceLocator.locale), so a language change moves both.
        private fun folderLabel(folder: CarFolder): String = ServiceLocator.locale.getString(
            when (folder) {
                CarFolder.NATIONAL -> R.string.radio_category_national
                CarFolder.REGIONS -> R.string.radio_folder_regions
                CarFolder.GENRES -> R.string.radio_folder_genres
            },
        )

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

/** The car's folders after Favourites, in the order its tabs show them (ADR-064). */
internal enum class CarFolder { NATIONAL, REGIONS, GENRES }

/** Which of the car's folders a shelf of the radio menu is gathered into. */
internal fun RadioShelf.carFolder(): CarFolder = when (this) {
    RadioShelf.National -> CarFolder.NATIONAL
    is RadioShelf.Regional -> CarFolder.REGIONS
    is RadioShelf.Genre -> CarFolder.GENRES
}

/**
 * The folder a station's queue walks: the one its place is in. Every station is on exactly one of
 * the place shelves ([RadioCategoryClassifier.regionShelf]); national if, against that, none is.
 */
internal fun placeFolder(shelves: List<RadioShelf>): CarFolder =
    shelves.map { it.carFolder() }.firstOrNull { it != CarFolder.GENRES } ?: CarFolder.NATIONAL

/**
 * The items on [folder]'s shelves, each with the shelf that put it there, in the order the car
 * lists them: the regions in [RadioRegion]'s order (north, centre, Jerusalem, south, other
 * regions), the genres alphabetically, and within a shelf the order given — the provider's, since
 * the sort is stable.
 */
internal fun <T> List<Pair<T, List<RadioShelf>>>.inCarFolder(folder: CarFolder): List<Pair<T, RadioShelf>> =
    flatMap { (item, shelves) -> shelves.filter { it.carFolder() == folder }.map { item to it } }
        .sortedWith(
            compareBy<Pair<T, RadioShelf>>(
                { (it.second as? RadioShelf.Regional)?.region?.ordinal ?: -1 },
                { (it.second as? RadioShelf.Genre)?.label.orEmpty() },
            ),
        )
