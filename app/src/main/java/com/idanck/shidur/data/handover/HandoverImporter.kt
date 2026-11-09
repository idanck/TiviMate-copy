package com.idanck.shidur.data.handover

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.idanck.shidur.core.AppError
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.prefs.SecureStore
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.work.RefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Where the new app is in taking the old package's data in (ADR-060). */
sealed interface HandoverState {
    data object Idle : HandoverState
    data object Importing : HandoverState

    /** In, and checked. The process restarts next, since everything read before it is stale. */
    data class Imported(
        val result: HandoverSnapshot.Imported,
        /** The old package recorded to a folder picked through the system; it must be picked again. */
        val folderToChoose: Boolean,
    ) : HandoverState

    /** Nothing was changed. The old package keeps everything and is not offered for removal. */
    data class Failed(val error: AppError) : HandoverState
}

/**
 * The new app's half of the move (ADR-060): on its first start, when the old package is installed
 * with the bridge's provider and signed with this app's key, it takes the snapshot in, checks the
 * counts, tells the bridge, and then offers to remove the old package — once, and once more on a
 * later start if the user said no.
 */
class HandoverImporter(
    private val context: Context,
    private val database: Database,
    private val settings: Settings,
) {

    private val _state = MutableStateFlow<HandoverState>(HandoverState.Idle)
    val state: StateFlow<HandoverState> = _state

    private val running = AtomicBoolean(false)

    private val legacy: String get() = Handover.legacyPackage(context.packageName)

    /**
     * Whether this start has data to take in. Cheap: three package-manager queries, no I/O. Asked
     * by `App.onCreate`, which holds the start-up work back while it is true.
     */
    fun pending(): Boolean {
        if (Handover.isBridge) return false
        if (settings.handoverImportedUtc() > 0) return false
        if (!Handover.isOurs(context, legacy)) return false
        return context.packageManager.resolveContentProvider(Handover.authority(legacy), 0) != null
    }

    /** Takes the data in. True when it is in; the state says what happened either way. */
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext false
        _state.value = HandoverState.Importing
        val incoming = File(context.cacheDir, "handover-in.db")
        try {
            // A refresh running beside the import would write channels into playlist ids the
            // import is about to give to other playlists.
            RefreshScheduler.cancel(context)
            // Opened, so the file exists with this app's schema for the import to fill.
            database.writableDatabase
            val stream = context.contentResolver.openInputStream(Handover.snapshotUri(legacy))
                ?: throw HandoverSnapshot.HandoverException("the previous version sent nothing")
            stream.use { input -> incoming.outputStream().use { input.copyTo(it) } }

            val imported = HandoverSnapshot.importInto(
                snapshot = incoming,
                live = context.getDatabasePath(Database.NAME),
                encrypt = SecureStore::encrypt,
            )
            settings.importFromHandover(imported.prefs.filter { (key, value) -> HandoverSnapshot.importable(key, value) })
            settings.setHandoverImported(System.currentTimeMillis())
            // The bridge then shows only "open the new Shidur" and "remove this version". If it
            // does not hear this, it keeps offering the move, which leads back here, and this
            // install no longer takes anything in.
            runCatching {
                context.contentResolver.call(
                    Uri.parse("content://${Handover.authority(legacy)}"),
                    Handover.METHOD_IMPORTED,
                    null,
                    null,
                )
            }
            _state.value = HandoverState.Imported(
                result = imported,
                folderToChoose = HandoverSnapshot.isPickedFolder(imported.prefs[Settings.K_REC_FOLDER]),
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = HandoverState.Failed(AppError.of(t))
            false
        } finally {
            incoming.delete()
            File(incoming.path + "-journal").delete()
            running.set(false)
        }
    }

    /** "Not now" after a failure: this start carries on without the data, the next one asks again. */
    fun dismissFailure() {
        if (_state.value is HandoverState.Failed) _state.value = HandoverState.Idle
    }

    /**
     * Whether to offer removing the old package now: after the import, and on one later start if
     * the user said no. Never after a failed import, which leaves the import undone.
     */
    fun removalPromptDue(): Boolean =
        !Handover.isBridge &&
            settings.handoverImportedUtc() > 0 &&
            settings.handoverRemovalPrompts() < MAX_REMOVAL_PROMPTS &&
            Handover.isInstalled(context, legacy)

    /** Whether the prompt now due is the reminder, the second and last. */
    fun removalPromptIsReminder(): Boolean = settings.handoverRemovalPrompts() > 0

    fun noteRemovalPrompted() = settings.noteHandoverRemovalPrompt()

    /** The platform's "remove this app?" for the old package. */
    fun removeLegacyIntent(): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$legacy")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        private const val MAX_REMOVAL_PROMPTS = 2

        /** Starts the app again from scratch, after an import replaced what it had read. */
        fun restart(context: Context) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (intent != null) runCatching { context.startActivity(intent) }
            Runtime.getRuntime().exit(0)
        }
    }
}
