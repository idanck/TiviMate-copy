package com.idanck.shidur.data.backup

import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.model.ChannelGroup
import com.idanck.shidur.data.model.OutputFormat
import com.idanck.shidur.data.model.Playlist
import com.idanck.shidur.data.model.SourceType
import com.idanck.shidur.data.prefs.SecureStore
import com.idanck.shidur.data.prefs.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Portable configuration backup (FR-SET-08).
 *
 * The file is plain JSON so it can be inspected before it is restored. Credentials are
 * excluded by default (NFR-SEC-07); including them writes them in clear text, which is why
 * the UI states that plainly and defaults the switch to off.
 */
class BackupManager(
    private val dao: Dao,
    private val settings: Settings,
) {

    fun export(out: OutputStream, includeCredentials: Boolean): Outcome<Int> = try {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("createdUtc", System.currentTimeMillis())
        root.put("includesCredentials", includeCredentials)

        val playlists = JSONArray()
        dao.playlists().forEach { p ->
            playlists.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    // Without credentials means without them anywhere: an Xtream guide address is
                    // xmltv.php?username=…&password=…, and an M3U panel address is get.php with
                    // the same two parameters. Until this the switch only left out the two
                    // columns while the same values went out inside the addresses.
                    put("url", if (includeCredentials) p.url else BackupCredentials.strip(p.url))
                    put("outputFormat", p.outputFormat.name)
                    put("userAgent", p.userAgent)
                    put("headers", JSONObject(p.headers as Map<*, *>))
                    // A guide address that carries credentials is left out rather than stripped:
                    // a refresh only fills in a guide address the playlist does not have, so a
                    // stripped one would stand in for the working one for good.
                    put(
                        "epgUrl",
                        p.epgUrl?.takeIf { includeCredentials || BackupCredentials.strip(it) == it },
                    )
                    put("refreshIntervalMinutes", p.refreshIntervalMinutes)
                    put("enabled", p.enabled)
                    put("colour", p.colour)
                    put("sortIndex", p.sortIndex)
                    if (includeCredentials) {
                        put("username", SecureStore.decrypt(p.username))
                        put("password", SecureStore.decrypt(p.password))
                    }
                },
            )
        }
        root.put("playlists", playlists)

        val groups = JSONArray()
        dao.playlists().forEach { p ->
            dao.groups(p.id, includeHidden = true).forEach { g ->
                groups.put(
                    JSONObject().apply {
                        put("playlistId", g.playlistId)
                        put("name", g.name)
                        put("custom", g.custom)
                        put("hidden", g.hidden)
                        put("locked", g.locked)
                        put("sortIndex", g.sortIndex)
                    },
                )
            }
        }
        root.put("groups", groups)

        // Only the user-owned per-channel state travels; the channel list itself is rebuilt
        // from the source on the first refresh after a restore.
        val channels = JSONArray()
        dao.allChannels().forEach { c ->
            if (!c.favourite && !c.hidden && !c.locked && c.customName == null &&
                c.customLogoUrl == null && c.epgOffsetMinutes == null && c.manualEpgId == null &&
                c.videoDecoder.name == "AUTO" && c.audioDecoder.name == "AUTO"
            ) return@forEach
            channels.put(
                JSONObject().apply {
                    put("playlistId", c.playlistId)
                    put("identity", c.identity)
                    put("customName", c.customName)
                    put("customLogoUrl", c.customLogoUrl)
                    put("number", c.number)
                    put("favourite", c.favourite)
                    put("hidden", c.hidden)
                    put("locked", c.locked)
                    put("sortIndex", c.sortIndex)
                    put("epgOffsetMinutes", c.epgOffsetMinutes)
                    put("manualEpgId", c.manualEpgId)
                    put("videoDecoder", c.videoDecoder.name)
                    put("audioDecoder", c.audioDecoder.name)
                },
            )
        }
        root.put("channels", channels)

        val prefs = JSONObject()
        settings.exportAll().forEach { (k, v) -> prefs.put(k, v) }
        root.put("settings", prefs)

        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        out.use { it.write(bytes) }
        Outcome.Ok(bytes.size)
    } catch (t: Throwable) {
        Outcome.Err(AppError.of(t))
    }

    fun import(input: InputStream): Outcome<ImportReport> = try {
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        if (root.optInt("format", 0) > FORMAT_VERSION) {
            Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "backup was written by a newer version"))
        } else {
            var playlists = 0
            var groups = 0
            var channels = 0

            // Playlists are keyed on URL so restoring twice updates rather than duplicates — on
            // the stripped form as well, so a backup without credentials still finds the
            // playlist it came from, and leaves its working address alone.
            val existing = dao.playlists()
            val existingByUrl = existing.associateBy { BackupCredentials.strip(it.url) } +
                existing.associateBy { it.url }
            val idMap = HashMap<Long, Long>()

            root.optJSONArray("playlists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isBlank()) continue
                    val previous = existingByUrl[url]
                    val restored = Playlist(
                        id = previous?.id ?: 0,
                        name = o.optString("name", "Restored"),
                        type = runCatching { SourceType.valueOf(o.optString("type")) }
                            .getOrDefault(SourceType.M3U_URL),
                        url = previous?.url?.takeIf { BackupCredentials.strip(it) == url } ?: url,
                        username = o.optStringOrNull("username")?.let(SecureStore::encrypt) ?: previous?.username,
                        password = o.optStringOrNull("password")?.let(SecureStore::encrypt) ?: previous?.password,
                        outputFormat = OutputFormat.from(o.optString("outputFormat")),
                        userAgent = o.optStringOrNull("userAgent"),
                        headers = o.optJSONObject("headers")?.toStringMap().orEmpty(),
                        epgUrl = o.optStringOrNull("epgUrl") ?: previous?.epgUrl,
                        refreshIntervalMinutes = o.optInt("refreshIntervalMinutes", 720),
                        enabled = o.optBoolean("enabled", true),
                        colour = o.optInt("colour", 0),
                        sortIndex = o.optInt("sortIndex", 0),
                    )
                    val newId = dao.upsertPlaylist(restored)
                    idMap[o.optLong("id", newId)] = newId
                    playlists++
                }
            }

            root.optJSONArray("groups")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val playlistId = idMap[o.optLong("playlistId")] ?: continue
                    dao.upsertGroup(
                        ChannelGroup(
                            playlistId = playlistId,
                            name = o.optString("name"),
                            custom = o.optBoolean("custom"),
                            hidden = o.optBoolean("hidden"),
                            locked = o.optBoolean("locked"),
                            sortIndex = o.optInt("sortIndex"),
                        ),
                    )
                    groups++
                }
            }

            root.optJSONArray("channels")?.let { arr ->
                val pending = ArrayList<PendingChannelState>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val playlistId = idMap[o.optLong("playlistId")] ?: continue
                    pending += PendingChannelState(
                        playlistId = playlistId,
                        identity = o.optString("identity"),
                        json = o,
                    )
                }
                channels = applyChannelState(pending)
            }

            root.optJSONObject("settings")?.let { o ->
                val values = HashMap<String, Any?>()
                o.keys().forEach { k -> values[k] = o.opt(k) }
                settings.importAll(values)
            }

            Outcome.Ok(ImportReport(playlists, groups, channels))
        }
    } catch (t: Throwable) {
        Outcome.Err(AppError.of(t))
    }

    /**
     * Per-channel state is applied to whatever channels currently exist. Rows for channels the
     * user has not re-imported yet are kept in a staging table so the state is not lost — here
     * they are simply skipped and reapplied on the next restore after a refresh.
     */
    private fun applyChannelState(pending: List<PendingChannelState>): Int {
        var applied = 0
        val byPlaylist = pending.groupBy { it.playlistId }
        byPlaylist.forEach { (playlistId, items) ->
            val existing = dao.channels(playlistId, includeHidden = true).associateBy { it.identity }
            dao.transaction { db ->
                items.forEach { item ->
                    val channel = existing[item.identity] ?: return@forEach
                    val o = item.json
                    dao.updateChannel(
                        db,
                        channel.copy(
                            customName = o.optStringOrNull("customName"),
                            customLogoUrl = o.optStringOrNull("customLogoUrl"),
                            number = o.optInt("number", channel.number),
                            favourite = o.optBoolean("favourite", channel.favourite),
                            hidden = o.optBoolean("hidden", channel.hidden),
                            locked = o.optBoolean("locked", channel.locked),
                            sortIndex = o.optInt("sortIndex", channel.sortIndex),
                            epgOffsetMinutes = if (o.isNull("epgOffsetMinutes")) null else o.optInt("epgOffsetMinutes"),
                            manualEpgId = o.optStringOrNull("manualEpgId"),
                            videoDecoder = runCatching {
                                com.idanck.shidur.data.model.DecoderMode.valueOf(o.optString("videoDecoder"))
                            }.getOrDefault(channel.videoDecoder),
                            audioDecoder = runCatching {
                                com.idanck.shidur.data.model.DecoderMode.valueOf(o.optString("audioDecoder"))
                            }.getOrDefault(channel.audioDecoder),
                        ),
                    )
                    applied++
                }
            }
        }
        return applied
    }

    private data class PendingChannelState(
        val playlistId: Long,
        val identity: String,
        val json: JSONObject,
    )

    data class ImportReport(val playlists: Int, val groups: Int, val channels: Int)

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_EXTENSION = "tivicopy.json"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    keys().forEach { k -> put(k, optString(k, "")) }
}

/**
 * Takes the credentials out of an address for a backup written without them (NFR-SEC-07): the
 * `username` and `password` query parameters an Xtream panel puts in `get.php` and `xmltv.php`,
 * and any `user:password@` in front of the host. Everything else, order included, is kept.
 */
internal object BackupCredentials {
    private val USER_INFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@")
    private val SECRET_KEYS = setOf("username", "password")

    fun strip(url: String): String {
        val noUserInfo = USER_INFO.replace(url, "$1")
        val queryStart = noUserInfo.indexOf('?')
        if (queryStart < 0) return noUserInfo
        val fragmentStart = noUserInfo.indexOf('#', queryStart).takeIf { it >= 0 } ?: noUserInfo.length
        val kept = noUserInfo.substring(queryStart + 1, fragmentStart)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in SECRET_KEYS }
        val base = noUserInfo.substring(0, queryStart)
        val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return base + query + noUserInfo.substring(fragmentStart)
    }
}
