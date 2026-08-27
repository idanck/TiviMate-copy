package com.tivicopy.player.tv

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.tivicopy.player.ServiceLocator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes [WatchNextPlan]'s rows to the system TV provider (FR-TVI-02).
 *
 * Everything here is best-effort and silent. The provider exists only on Android TV and Google
 * TV; on a phone `resolveContentProvider` finds nothing and the whole sync is skipped, and on a
 * television that refuses the write, a lost launcher row is not worth surfacing an error over a
 * stream that is playing perfectly well. Nothing in this file is on a playback path.
 *
 * The `androidx.tvprovider` types are API 26 and this app's `minSdk` is 23, and — unlike most
 * such hazards — lint will not catch a mistake here: `lint { abortOnError = false }` means a
 * `NewApi` finding is a warning in a log nobody reads, and the failure lands as a
 * `NoClassDefFoundError` on somebody's Android 7 box instead. Every reference to those types is
 * therefore confined to `@RequiresApi` members that only [sync] and [clear] can reach, and both
 * check the version themselves before calling in.
 */
object WatchNext {

    /**
     * The shortest gap between two syncs, unless forced.
     *
     * `AppViewModel.play` fires on every channel-up press. Zapping through twenty channels
     * would otherwise be twenty round trips to a content provider in another process, for a
     * shelf whose top row will be right after the last one.
     */
    private const val MIN_INTERVAL_MS = 30_000L

    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastSyncUtc = 0L

    /**
     * Whether this device has the TV provider at all.
     *
     * Asked of the package manager rather than inferred from leanback or the UI mode: what this
     * code needs to know is not "is this a television" but "is there somewhere to write", and a
     * device can answer those two differently.
     */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching {
                context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
            }.getOrDefault(false)

    /**
     * Rebuilds the row from the recently watched channels.
     *
     * Rebuilt wholesale rather than patched incrementally — the same choice `RadioBrowseTree`
     * makes for Android Auto, and for the same reason: a playlist refresh, a hide or a rename is
     * picked up the next time this runs, with no invalidation to get wrong. The list is ten rows.
     */
    suspend fun sync(context: Context, force: Boolean = false) {
        // Spelled out here as well as inside [isSupported] so the guard sits in the same function
        // as the calls it protects; neither lint nor a reader should have to follow indirection.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isSupported(context)) return
        ServiceLocator.init(context)
        if (!ServiceLocator.settings.state.value.watchNextEnabled) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSyncUtc in 0 until MIN_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { runCatching { syncBlocking(context) } }
            lastSyncUtc = System.currentTimeMillis()
        } finally {
            inFlight.set(false)
        }
    }

    /** Removes every row this app owns. What turning the setting off has to actually do. */
    suspend fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isSupported(context)) return
        withContext(Dispatchers.IO) {
            runCatching { existingRows(context).values.forEach { deleteRow(context, it) } }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncBlocking(context: Context) {
        val dao = ServiceLocator.dao
        // Twice the row length, because the plan drops hidden and stale channels and a short
        // read would leave the row shorter than it needs to be.
        val recent = dao.recentChannels(limit = WatchNextPlan.MAX_ENTRIES * 2)
        val nowByChannel = recent.associate { channel ->
            channel.id to runCatching { ServiceLocator.epg.nowAndNext(channel).first }.getOrNull()
        }
        val wanted = WatchNextPlan.entries(recent, nowByChannel, nowUtc = System.currentTimeMillis())

        val existing = existingRows(context)
        wanted.forEach { entry -> write(context, entry, existing[entry.channelId]) }
        WatchNextPlan.stale(existing.keys, wanted.map { it.channelId }).forEach { channelId ->
            existing[channelId]?.let { deleteRow(context, it) }
        }
    }

    /** Our own rows only: the provider scopes this query to the calling package. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun existingRows(context: Context): Map<Long, Long> = runCatching {
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    val channelId = program.internalProviderId?.toLongOrNull() ?: continue
                    put(channelId, program.id)
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyMap())

    @RequiresApi(Build.VERSION_CODES.O)
    private fun deleteRow(context: Context, rowId: Long) {
        runCatching {
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(rowId), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun write(context: Context, entry: WatchNextEntry, existingRowId: Long?) {
        runCatching {
            val builder = WatchNextProgram.Builder()
                .setType(TvContractCompat.WatchNextPrograms.TYPE_CHANNEL)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                // A live channel has no position to resume, so the playback-position and
                // duration fields Google's guidance pairs with CONTINUE are deliberately left
                // unset: the launcher then draws no progress bar, which is the truth.
                .setLive(true)
                .setTitle(entry.title)
                .setLastEngagementTimeUtcMillis(entry.lastEngagementUtc)
                .setInternalProviderId(entry.channelId.toString())
                .setIntentUri(Uri.parse(entry.deepLink))
            entry.description?.let { builder.setDescription(it) }
            entry.posterUri?.takeIf { it.isNotBlank() }?.let {
                builder.setPosterArtUri(Uri.parse(it))
                // Claimed honestly rather than flatteringly. These are channel logos — square-ish
                // marks with transparent margins, not 16:9 key art — and telling the launcher
                // otherwise invites it to crop the sides off. Rendering real 16:9 cards would
                // mean a FileProvider and cross-process URI grants for artwork; see ADR-017.
                builder.setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_1_1)
            }
            val values = builder.build().toContentValues()
            if (existingRowId != null) {
                context.contentResolver.update(
                    TvContractCompat.buildWatchNextProgramUri(existingRowId),
                    values,
                    null,
                    null,
                )
            } else {
                context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            }
        }
    }
}
