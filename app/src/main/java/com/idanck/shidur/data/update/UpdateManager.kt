package com.idanck.shidur.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.idanck.shidur.BuildInfo
import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.net.RequestProfile
import com.idanck.shidur.data.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why an install cannot start. Each one is a different sentence to the user, which is why these
 * are named here rather than folded into a generic failure: "no network" and "not over mobile
 * data, by your own setting" are not the same message.
 */
enum class UpdateBlocker { RECORDING_IN_PROGRESS, INSTALL_PERMISSION, METERED, NEEDS_NEWER_ANDROID, NO_SPACE }

/** Where the app is in the update cycle. Exactly one of these is live at a time. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtUtc: Long) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val downloadedBytes: Long) : UpdateState {
        val fraction: Float
            get() = if (manifest.sizeBytes <= 0) 0f else
                (downloadedBytes.toFloat() / manifest.sizeBytes).coerceIn(0f, 1f)
    }
    data class Verifying(val manifest: UpdateManifest) : UpdateState
    /**
     * The session is committed; the platform now owns the outcome, prompt included.
     *
     * [startedUtc] is here because that hand-off is not always answered: an activity start the
     * system drops leaves nothing to move this state on, and a state that suppresses every
     * later check has to be able to expire.
     */
    data class Installing(val manifest: UpdateManifest, val startedUtc: Long) : UpdateState
    data class Failed(val error: AppError) : UpdateState
    /** Refused before anything was fetched, for a reason the user can act on. */
    data class Blocked(val manifest: UpdateManifest, val blocker: UpdateBlocker) : UpdateState
}

/**
 * In-app updates (FR-UPD-01..08).
 *
 * The app is distributed outside any store, so it carries the store's job itself: ask a
 * published manifest whether something newer exists, fetch that APK, prove it is the file the
 * manifest describes, and hand it to the platform installer.
 *
 * Three things are deliberately *not* trusted here. The manifest is fetched over HTTPS only and
 * parsed strictly ([UpdateManifest]); the downloaded file is checked against the manifest's
 * SHA-256 before anything else looks at it; and the APK's signing certificate is compared with
 * the running app's, so a mismatch surfaces as a clear message instead of the platform's opaque
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` at the very end of the flow.
 */
class UpdateManager(
    private val context: Context,
    private val settings: Settings,
    private val dao: Dao,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val updateDir: File get() = File(context.cacheDir, "updates")

    /**
     * Claimed for the length of one install attempt.
     *
     * [_state] cannot be the guard on its own: [downloadAndInstall] suspends over the blocking
     * checks before it publishes `Downloading`, and two taps inside that window would both pass
     * and then race over the same cache file.
     */
    private val installing = AtomicBoolean(false)

    /**
     * Whether the platform will let this app install another, as something the UI can watch.
     *
     * [canRequestInstalls] is a one-shot read, and the grant is made on a screen of the
     * platform's own — so nothing inside this process changes at the moment the user gives it.
     * The activity re-reads it on resume ([refreshInstallPermission]); this flow is what turns
     * that read into a recomposition, and into the offer picking itself back up.
     */
    private val _canInstall = MutableStateFlow(canRequestInstalls())
    val canInstall: StateFlow<Boolean> = _canInstall

    /**
     * Re-reads the per-app install permission, after the user has been somewhere it could change.
     *
     * Cheap enough to call on every resume: a package-manager query, no I/O.
     */
    fun refreshInstallPermission(): Boolean {
        val granted = canRequestInstalls()
        _canInstall.value = granted
        return granted
    }

    /**
     * Looks for a newer build.
     *
     * [force] distinguishes the user pressing "check now" from the background sweep: the sweep
     * is rate-limited to [CHECK_INTERVAL_MS] so a device that wakes often does not re-ask every
     * hour, while a person who asks is always answered.
     */
    suspend fun check(force: Boolean): Outcome<UpdateManifest?> {
        // An install already under way owns both the state and the cache directory: a check
        // landing in the middle of one would overwrite the progress the user is watching and
        // prune the very file being written.
        if (inFlight()) return Outcome.Ok(null)

        val now = System.currentTimeMillis()
        if (!force) {
            if (!settings.state.value.updateAutoCheck) return Outcome.Ok(null)
            val since = now - settings.state.value.updateLastCheckUtc
            if (since in 0 until CHECK_INTERVAL_MS) return Outcome.Ok(restorePending())
        }

        if (force) _state.value = UpdateState.Checking
        val fetched = withContext(Dispatchers.IO) { fetchManifest() }
        val (manifest, json) = when (fetched) {
            is Outcome.Err -> {
                // A background sweep that cannot reach the host says nothing: the user did not
                // ask, and a settings screen reporting a failure nobody triggered is noise.
                _state.value = if (force) UpdateState.Failed(fetched.error) else UpdateState.Idle
                return fetched
            }
            is Outcome.Ok -> fetched.value
        }

        settings.rememberUpdateCheck(now, manifest.versionCode, json)
        val newer = manifest.isNewerThan(BuildInfo.versionCode)
        // An APK that was installed, superseded or abandoned is 16 MB of cache nobody will read
        // again. The daily check is the natural place to notice.
        withContext(Dispatchers.IO) { pruneDownloads(keep = manifest.versionCode.takeIf { newer }) }
        _state.value = when {
            newer -> UpdateState.Available(manifest)
            force -> UpdateState.UpToDate(now)
            else -> UpdateState.Idle
        }
        return Outcome.Ok(manifest.takeIf { newer })
    }

    private fun inFlight(): Boolean = when (val current = _state.value) {
        is UpdateState.Downloading, is UpdateState.Verifying -> true
        // A committed session normally ends this process. When the platform's confirmation
        // never appeared, nothing else will ever clear the state, so it lapses rather than
        // silencing every future check for the life of the process.
        is UpdateState.Installing ->
            System.currentTimeMillis() - current.startedUtc < INSTALL_HANDOFF_TIMEOUT_MS
        else -> false
    }

    /**
     * The newer build the last successful check found, when there still is one.
     *
     * The check interval outlives the process, so without this a sweep that found an update at
     * 3am would have nothing to show at 8am — while the settings screen reported, accurately
     * and uselessly, that it had looked recently.
     */
    private fun restorePending(): UpdateManifest? {
        val json = settings.state.value.updateLastManifest ?: return null
        val manifest = UpdateManifest.parse(json).getOrNull() ?: return null
        if (!manifest.isNewerThan(BuildInfo.versionCode)) return null
        if (_state.value is UpdateState.Idle) _state.value = UpdateState.Available(manifest)
        return manifest
    }

    /** True when this version was dismissed with "skip" and should stay quiet (FR-UPD-06). */
    fun isSkipped(manifest: UpdateManifest): Boolean =
        settings.state.value.updateSkippedVersionCode >= manifest.versionCode

    fun skip(manifest: UpdateManifest) {
        settings.setUpdateSkippedVersion(manifest.versionCode)
        _state.value = UpdateState.Idle
    }

    /** Clears a terminal state so the row goes back to reporting when it last looked. */
    fun dismiss() {
        val current = _state.value
        if (current is UpdateState.Failed || current is UpdateState.UpToDate ||
            current is UpdateState.Blocked
        ) {
            _state.value = UpdateState.Idle
        }
    }

    /**
     * Downloads, verifies and hands the APK to the platform installer.
     *
     * Everything that can refuse does so before a byte is fetched: a recording in flight would
     * be killed by the install, an update over a metered link is the user's decision to make,
     * and a cache partition with no room fails halfway through otherwise.
     */
    suspend fun downloadAndInstall(manifest: UpdateManifest): Outcome<Unit> {
        // Two entry points reach this — the banner and the settings row — and either can be
        // pressed twice. The claim is taken before the first suspension point, so the loser
        // returns to a screen that is already showing the winner's progress.
        if (inFlight() || !installing.compareAndSet(false, true)) return Outcome.Ok(Unit)
        try {
            return install(manifest)
        } finally {
            installing.set(false)
        }
    }

    private suspend fun install(manifest: UpdateManifest): Outcome<Unit> {
        // Off the caller's thread: the recording check reads the database.
        withContext(Dispatchers.IO) { blockingReason(manifest) }?.let { blocker ->
            _state.value = UpdateState.Blocked(manifest, blocker)
            return Outcome.Err(AppError(AppError.Kind.Cancelled, blocker.name))
        }

        _state.value = UpdateState.Downloading(manifest, 0)
        val downloaded = withContext(Dispatchers.IO) {
            download(manifest) { bytes -> _state.value = UpdateState.Downloading(manifest, bytes) }
        }
        val file = when (downloaded) {
            is Outcome.Err -> {
                _state.value = UpdateState.Failed(downloaded.error)
                return downloaded
            }
            is Outcome.Ok -> downloaded.value
        }

        _state.value = UpdateState.Verifying(manifest)
        val verified = withContext(Dispatchers.IO) { verifySignature(file) }
        if (verified is Outcome.Err) {
            file.delete()
            _state.value = UpdateState.Failed(verified.error)
            return verified
        }

        _state.value = UpdateState.Installing(manifest, System.currentTimeMillis())
        val committed = withContext(Dispatchers.IO) { commitSession(file) }
        if (committed is Outcome.Err) {
            file.delete()
            _state.value = UpdateState.Failed(committed.error)
        }
        return committed
    }

    /**
     * Reports what the install would fail on, or null when it may proceed.
     *
     * Reads the database, so it belongs off the main thread.
     */
    fun blockingReason(manifest: UpdateManifest): UpdateBlocker? = when {
        // Replacing the package kills the process, and with it the recorder's foreground
        // service: a scheduled recording would end up truncated with nothing to say why.
        dao.activeRecordings().isNotEmpty() -> UpdateBlocker.RECORDING_IN_PROGRESS
        // Refreshed rather than merely read: a refusal on this reason and what the screens
        // show about the permission must be the same answer.
        !refreshInstallPermission() -> UpdateBlocker.INSTALL_PERMISSION
        settings.state.value.updateWifiOnly && !isUnmetered() -> UpdateBlocker.METERED
        manifest.minSdk > Build.VERSION.SDK_INT -> UpdateBlocker.NEEDS_NEWER_ANDROID
        updateDir.parentFile?.let { it.usableSpace < manifest.sizeBytes * 2 } == true ->
            UpdateBlocker.NO_SPACE
        else -> null
    }

    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * The settings screen that grants "install unknown apps".
     *
     * Returns null below API 26, where the switch is global rather than per app, and on the
     * TV builds that ship no such activity — the caller shows instructions instead of
     * launching an intent that would throw.
     */
    fun unknownSourcesIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        val resolved = context.packageManager.resolveActivity(intent, 0) != null
        return intent.takeIf { resolved }
    }

    fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    internal fun onInstallFailed(error: AppError) {
        _state.value = UpdateState.Failed(error)
    }

    /** Drops every cached APK except, optionally, the one still worth installing. */
    private fun pruneDownloads(keep: Int?) {
        val wanted = keep?.let { "tivicopy-$it.apk" }
        updateDir.listFiles()?.forEach { file ->
            if (file.name != wanted) file.delete()
        }
    }

    /**
     * The shared client, minus one concession: it follows a redirect from HTTPS to plain HTTP,
     * because providers routinely hand an HTTPS portal's streams to HTTP servers. The update
     * channel is HTTPS end to end, so a downgrade there is refused rather than followed.
     */
    private fun updateClient(profile: RequestProfile): OkHttpClient =
        HttpFactory.client(profile).newBuilder().followSslRedirects(false).build()

    private fun fetchManifest(): Outcome<Pair<UpdateManifest, String>> {
        // The default profile deliberately, rather than the user's source profile: "allow
        // insecure TLS" is a concession to one broken provider, never to the update channel.
        val profile = RequestProfile(connectTimeoutSeconds = 15, readTimeoutSeconds = 20)
        return try {
            updateClient(profile)
                .newCall(HttpFactory.request(MANIFEST_URL, profile))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code))
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    else UpdateManifest.parse(body).map { it to body }
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    /** Streams the APK to the cache, hashing as it goes so the file is read exactly once. */
    private suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Long) -> Unit,
    ): Outcome<File> {
        updateDir.mkdirs()
        val target = File(updateDir, "tivicopy-${manifest.versionCode}.apk")
        // Anything left by an interrupted attempt, or by a version that is already installed.
        updateDir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        // The retry after a failed install would otherwise re-fetch 16 MB the device already
        // holds; hashing it costs a local read and proves it is still the manifest's file.
        if (target.length() == manifest.sizeBytes && hashOf(target) == manifest.sha256) {
            onProgress(manifest.sizeBytes)
            return Outcome.Ok(target)
        }
        target.delete()

        val profile = RequestProfile(connectTimeoutSeconds = 20, readTimeoutSeconds = 60)
        return try {
            updateClient(profile)
                .newCall(HttpFactory.request(manifest.apkUrl, profile))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code))
                    val source = response.body?.byteStream()
                        ?: return Outcome.Err(AppError(AppError.Kind.EmptyResponse))

                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > manifest.sizeBytes) {
                                return fail(target, AppError.Kind.UnsupportedFormat, "longer than the manifest says")
                            }
                            digest.update(buffer, 0, read)
                            sink.write(buffer, 0, read)
                            onProgress(total)
                        }
                    }

                    if (total != manifest.sizeBytes) {
                        return fail(target, AppError.Kind.EmptyResponse, "truncated download")
                    }
                    val hex = digest.digest().hex()
                    if (hex != manifest.sha256) {
                        return fail(target, AppError.Kind.UnsupportedFormat, "checksum mismatch")
                    }
                    Outcome.Ok(target)
                }
        } catch (t: Throwable) {
            target.delete()
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    private fun hashOf(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().hex()
    }.getOrNull()

    private fun fail(target: File, kind: AppError.Kind, detail: String): Outcome<File> {
        target.delete()
        return Outcome.Err(AppError(kind, detail))
    }

    /**
     * Refuses a file the platform would refuse anyway, while there is still a screen to say so on.
     *
     * Android only replaces a package when the new APK carries the same signing certificate, and
     * the failure it reports at commit time is a bare status code. Comparing here turns "the
     * build you fetched was signed with a different key" into something the user can act on.
     */
    private fun verifySignature(file: File): Outcome<Unit> = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        when {
            candidate == null ->
                Outcome.Err(AppError(AppError.Kind.ParseFailed, "downloaded file is not an APK"))
            candidate.packageName != context.packageName ->
                Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "different package"))
            certificates(candidate) != certificates(installed) ->
                Outcome.Err(AppError(AppError.Kind.SignatureMismatch, "signature mismatch"))
            else -> Outcome.Ok(Unit)
        }
    } catch (t: Throwable) {
        Outcome.Err(AppError.of(t))
    }

    private fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().mapNotNull { signature ->
            signature?.toByteArray()?.let { digest.digest(it).hex() }
        }.toSet()
    }

    private fun commitSession(file: File): Outcome<Unit> {
        val installer = context.packageManager.packageInstaller
        // Sessions this app opened and never finished: each holds its own staged copy of a
        // 16 MB APK, and the per-UID session limit is small enough to reach by failing a few
        // times. Nothing of ours is in flight here — the claim in `downloadAndInstall` says so.
        runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // A self-update by the installer of record needs no prompt. When some other
            // installer owns the package — a file manager, for the very first sideload — the
            // platform ignores this and shows its confirmation, which is the correct fallback.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            return Outcome.Err(AppError.of(t))
        }
        return try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, file.length()).use { sink ->
                    file.inputStream().use { it.copyTo(sink) }
                    session.fsync(sink)
                }
                session.commit(statusIntent(sessionId).intentSender)
            }
            Outcome.Ok(Unit)
        } catch (t: Throwable) {
            // Staged bytes with no commit behind them; the next attempt starts clean.
            runCatching { installer.abandonSession(sessionId) }
            Outcome.Err(AppError.of(t))
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        // An explicit component, not an action with a package: since Android 8 a manifest
        // receiver does not receive implicit broadcasts, and naming the class also means the
        // status can never reach anything but this app.
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_STATUS)
        // Mutable because the platform fills the result in.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    companion object {
        /**
         * The published manifest (FR-UPD-01). Served from the project's own host rather than
         * the source repository, which is private and could not hand a binary to the app
         * without shipping a credential inside it.
         */
        const val MANIFEST_URL = "https://updates.idanck.com/latest.json"

        /** One background check a day; the user can always ask sooner. */
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /**
         * How long a committed session may sit unanswered before the app stops treating an
         * install as live. Long enough for a person to read the platform's dialog, short
         * enough that a prompt which never appeared does not wedge the feature.
         */
        const val INSTALL_HANDOFF_TIMEOUT_MS = 15 * 60 * 1000L

        private const val SESSION_ENTRY = "tivicopy-update"
    }
}

/** Lower-case hex, the form every checksum in this feature is compared in. */
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
