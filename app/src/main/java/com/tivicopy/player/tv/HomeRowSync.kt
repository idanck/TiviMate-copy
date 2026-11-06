package com.tivicopy.player.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Publishes [HomeRowPlan]'s cards as this app's own row on the Google TV home screen (FR-TVI-03).
 *
 * The same shape and the same guards as `WatchNextSync.kt`, for the same reasons (ADR-017,
 * ADR-056): best-effort and silent, every `androidx.tvprovider` reference behind `@RequiresApi`
 * members only [sync] and [clear] can reach, throttled for zapping, and an opt-out that takes
 * the row away rather than merely freezing it.
 */
object HomeRow {

    private const val MIN_INTERVAL_MS = 30_000L

    /** Launcher logo for the row's title, in pixels. The launcher scales it; this just bounds the bytes. */
    private const val LOGO_PX = 256

    private val gate = Mutex()

    @Volatile private var lastSyncUtc = 0L

    suspend fun sync(context: Context, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!WatchNext.isSupported(context)) return
        ServiceLocator.init(context)
        if (!ServiceLocator.settings.state.value.homeRowEnabled) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSyncUtc in 0 until MIN_INTERVAL_MS) return
        if (!gate.tryLock()) return
        try {
            // Re-read inside the gate, as WatchNext does: an opt-out that lands between the check
            // above and this point must not have its rows put straight back.
            if (!ServiceLocator.settings.state.value.homeRowEnabled) return
            withContext(Dispatchers.IO) { runCatching { syncBlocking(context) } }
            lastSyncUtc = System.currentTimeMillis()
        } finally {
            gate.unlock()
        }
    }

    /** Removes the row entirely — the channel and, with it, every card on it. */
    suspend fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!WatchNext.isSupported(context)) return
        gate.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val helper = PreviewChannelHelper(context)
                    ownChannels(helper).forEach { helper.deletePreviewChannel(it.id) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncBlocking(context: Context) {
        val dao = ServiceLocator.dao
        val favourites = dao.favouriteChannels()
        val recent = dao.recentChannels(limit = HomeRowPlan.MAX_ENTRIES * 2)
        val candidates = (favourites + recent).distinctBy { it.id }
        val nowByChannel = candidates.associate { channel ->
            channel.id to runCatching { ServiceLocator.epg.nowAndNext(channel).first }.getOrNull()
        }
        val wanted = HomeRowPlan.entries(favourites, recent, nowByChannel, nowUtc = System.currentTimeMillis())

        val helper = PreviewChannelHelper(context)
        val channelId = ensureChannel(context, helper) ?: return
        val existing = existingPrograms(context, channelId)
        wanted.forEach { entry -> write(helper, channelId, entry, existing[entry.channelId]) }
        HomeRowPlan.stale(existing.keys, wanted.map { it.channelId }).forEach { id ->
            existing[id]?.let { runCatching { helper.deletePreviewProgram(it) } }
        }
    }

    /** Our preview channels only: the provider scopes this to the calling package. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ownChannels(helper: PreviewChannelHelper): List<PreviewChannel> =
        runCatching { helper.allChannels }.getOrDefault(emptyList())
            .filter { it.internalProviderId == HomeRowPlan.CHANNEL_PROVIDER_ID }

    /**
     * The row's channel, created on first use as this app's default channel.
     *
     * Published with `publishDefaultChannel`, which also asks the system to make it browsable:
     * an app's first channel is shown without the viewer having to approve it, which is how
     * Emby's row appears on the same launcher. Further channels would need that approval — one
     * row is all this needs.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel(context: Context, helper: PreviewChannelHelper): Long? {
        ownChannels(helper).firstOrNull()?.let { return it.id }
        val logo = appLogo(context) ?: return null
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.app_name))
            .setAppLinkIntentUri(Uri.parse(appLink(context)))
            .setInternalProviderId(HomeRowPlan.CHANNEL_PROVIDER_ID)
            .setLogo(logo)
            .build()
        return runCatching { helper.publishDefaultChannel(channel) }.getOrNull()
    }

    /** What selecting the row's title opens: the app itself, as the launcher icon would. */
    private fun appLink(context: Context): String =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .toUri(Intent.URI_INTENT_SCHEME)

    private fun appLogo(context: Context): Bitmap? = runCatching {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(LOGO_PX, LOGO_PX)
    }.getOrNull()

    /** Card row id by the app channel it names, for this preview channel only. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun existingPrograms(context: Context, previewChannelId: Long): Map<Long, Long> = runCatching {
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(previewChannelId),
            PreviewProgram.PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val program = PreviewProgram.fromCursor(cursor)
                    val channelId = program.internalProviderId?.toLongOrNull() ?: continue
                    put(channelId, program.id)
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyMap())

    @RequiresApi(Build.VERSION_CODES.O)
    private fun write(helper: PreviewChannelHelper, previewChannelId: Long, entry: HomeRowEntry, existingId: Long?) {
        runCatching {
            val builder = PreviewProgram.Builder()
                .setChannelId(previewChannelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setLive(true)
                .setTitle(entry.title)
                .setWeight(entry.weight)
                .setInternalProviderId(entry.channelId.toString())
                .setIntentUri(Uri.parse(entry.deepLink))
            // Set on every write, null included: an update replaces the row's values, and a
            // programme that ended must not stay on the card because the guide has nothing now.
            builder.setDescription(entry.description)
            entry.posterUri?.takeIf { it.isNotBlank() }?.let {
                builder.setPosterArtUri(Uri.parse(it))
                // Channel logos, claimed as what they are — see ADR-017.
                builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
            }
            val program = builder.build()
            if (existingId != null) {
                helper.updatePreviewProgram(existingId, program)
            } else {
                helper.publishPreviewProgram(program)
            }
        }
    }
}
