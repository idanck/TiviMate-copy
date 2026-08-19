package com.tivicopy.player.data.xtream

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.model.CatchupMode
import com.tivicopy.player.data.m3u.M3uEntry
import com.tivicopy.player.data.model.OutputFormat
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder

data class XtreamAccount(
    val status: String?,
    val expiryUtc: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean = false,
    val allowedOutputFormats: List<String> = emptyList(),
    val timezone: String? = null,
) {
    val expiresSoon: Boolean
        get() = expiryUtc != null && expiryUtc - System.currentTimeMillis() < 7L * 24 * 60 * 60 * 1000
}

data class XtreamSnapshot(
    val account: XtreamAccount?,
    val liveEntries: List<M3uEntry>,
    val vod: List<VodItem>,
    val serverEpgUrl: String,
)

/**
 * Xtream Codes player API client (FR-SRC-03).
 *
 * Panels vary a great deal in what they return: numbers arrive as strings, optional fields are
 * `null`, `""` or missing, and some panels answer `player_api.php` with an HTML error page.
 * Every read here is defensive; an unparsable section degrades to empty rather than failing
 * the whole source.
 */
class XtreamClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val profile: RequestProfile,
) {

    private val root: String = baseUrl.trim().trimEnd('/')

    private fun api(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(root).append("/player_api.php")
            .append("?username=").append(enc(username))
            .append("&password=").append(enc(password))
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return sb.toString()
    }

    fun xmltvUrl(): String =
        "$root/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): Outcome<String> = try {
        HttpFactory.client(profile).newCall(HttpFactory.request(url, profile)).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) Outcome.Err(AppError.fromHttp(r.code, body))
            else if (body.isBlank()) Outcome.Err(AppError(AppError.Kind.EmptyResponse, "empty body"))
            else Outcome.Ok(body)
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Outcome.Err(AppError.of(t))
    }

    /** Streams the provider guide; the caller closes it. */
    fun openXmltv(): Outcome<InputStream> = try {
        val response = HttpFactory.client(profile)
            .newCall(HttpFactory.request(xmltvUrl(), profile)).execute()
        if (!response.isSuccessful) {
            response.close()
            Outcome.Err(AppError.fromHttp(response.code))
        } else {
            val stream = response.body?.byteStream()
            if (stream == null) {
                response.close()
                Outcome.Err(AppError(AppError.Kind.EmptyResponse, "no guide body"))
            } else {
                Outcome.Ok(stream)
            }
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Outcome.Err(AppError.of(t))
    }

    fun account(): Outcome<XtreamAccount> = get(api()).map { body ->
        val root = runCatching { JSONObject(body) }.getOrNull()
        val info = root?.optJSONObject("user_info")
        val server = root?.optJSONObject("server_info")
        XtreamAccount(
            status = info?.optString("status")?.takeIf { it.isNotBlank() },
            expiryUtc = info?.optString("exp_date")?.toLongOrNull()?.times(1000),
            maxConnections = info?.optString("max_connections")?.toIntOrNull(),
            activeConnections = info?.optString("active_cons")?.toIntOrNull(),
            isTrial = info?.optString("is_trial") == "1",
            allowedOutputFormats = info?.optJSONArray("allowed_output_formats")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            }.orEmpty(),
            timezone = server?.optString("timezone")?.takeIf { it.isNotBlank() },
        )
    }

    /** Live channels, already shaped like playlist entries so one ingest path serves both. */
    fun liveChannels(format: OutputFormat): Outcome<List<M3uEntry>> {
        val categories = get(api("get_live_categories")).getOrNull()
            ?.let(::parseCategories).orEmpty()

        return get(api("get_live_streams")).map { body ->
            val array = runCatching { JSONArray(body) }.getOrNull() ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val streamId = o.optNumberAsString("stream_id") ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: "Channel $streamId"
                val catchupDays = o.optNumberAsString("tv_archive_duration")?.toIntOrNull() ?: 0
                val hasArchive = (o.optNumberAsString("tv_archive")?.toIntOrNull() ?: 0) > 0
                M3uEntry(
                    name = name,
                    url = streamUrl("live", streamId, format.extension()),
                    tvgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                    tvgName = name,
                    tvgLogo = o.optString("stream_icon").takeIf { it.isNotBlank() && it != "null" },
                    tvgChno = o.optNumberAsString("num")?.toIntOrNull(),
                    groupTitle = categories[o.optNumberAsString("category_id")] ?: "Live",
                    catchupMode = if (hasArchive && catchupDays > 0) CatchupMode.XC else CatchupMode.NONE,
                    catchupDays = if (hasArchive) catchupDays else 0,
                    userAgent = profile.userAgent,
                    headers = profile.headers,
                )
            }
        }
    }

    fun vod(): Outcome<List<VodItem>> {
        val categories = get(api("get_vod_categories")).getOrNull()
            ?.let(::parseCategories).orEmpty()

        return get(api("get_vod_streams")).map { body ->
            val array = runCatching { JSONArray(body) }.getOrNull() ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val streamId = o.optNumberAsString("stream_id") ?: return@mapNotNull null
                VodItem(
                    playlistId = 0,
                    streamId = streamId,
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: "Title $streamId",
                    categoryName = categories[o.optNumberAsString("category_id")],
                    posterUrl = o.optString("stream_icon").takeIf { it.isNotBlank() && it != "null" },
                    plot = o.optString("plot").takeIf { it.isNotBlank() && it != "null" },
                    year = o.optString("year").takeIf { it.isNotBlank() && it != "null" },
                    rating = o.optString("rating").takeIf { it.isNotBlank() && it != "null" },
                    containerExtension = o.optString("container_extension").takeIf { it.isNotBlank() } ?: "mp4",
                )
            }
        }
    }

    /** Series are listed separately and expanded to episodes on demand. */
    fun series(): Outcome<List<VodItem>> {
        val categories = get(api("get_series_categories")).getOrNull()
            ?.let(::parseCategories).orEmpty()

        return get(api("get_series")).map { body ->
            val array = runCatching { JSONArray(body) }.getOrNull() ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val seriesId = o.optNumberAsString("series_id") ?: return@mapNotNull null
                VodItem(
                    playlistId = 0,
                    streamId = "series:$seriesId",
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: "Series $seriesId",
                    categoryName = categories[o.optNumberAsString("category_id")],
                    posterUrl = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                    plot = o.optString("plot").takeIf { it.isNotBlank() && it != "null" },
                    year = o.optString("releaseDate").takeIf { it.isNotBlank() && it != "null" }?.take(4),
                    rating = o.optString("rating").takeIf { it.isNotBlank() && it != "null" },
                    seriesId = seriesId,
                )
            }
        }
    }

    /** Episodes of one series, flattened into VOD rows keyed by season/episode. */
    fun seriesEpisodes(seriesId: String): Outcome<List<VodItem>> =
        get(api("get_series_info", mapOf("series_id" to seriesId))).map { body ->
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@map emptyList<VodItem>()
            val seasons = root.optJSONObject("episodes") ?: return@map emptyList<VodItem>()
            val out = ArrayList<VodItem>()
            seasons.keys().forEach { seasonKey ->
                val arr = seasons.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optNumberAsString("id") ?: continue
                    val info = e.optJSONObject("info")
                    out += VodItem(
                        playlistId = 0,
                        streamId = id,
                        name = e.optString("title").takeIf { it.isNotBlank() } ?: "Episode $id",
                        posterUrl = info?.optString("movie_image")?.takeIf { it.isNotBlank() && it != "null" },
                        plot = info?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" },
                        durationSeconds = info?.optNumberAsString("duration_secs")?.toIntOrNull() ?: 0,
                        containerExtension = e.optString("container_extension").takeIf { it.isNotBlank() } ?: "mp4",
                        seriesId = seriesId,
                        seasonNumber = seasonKey.toIntOrNull(),
                        episodeNumber = e.optNumberAsString("episode_num")?.toIntOrNull(),
                    )
                }
            }
            out
        }

    fun streamUrl(kind: String, streamId: String, extension: String): String =
        "$root/$kind/${enc(username)}/${enc(password)}/$streamId.$extension"

    fun vodUrl(streamId: String, containerExtension: String): String =
        "$root/movie/${enc(username)}/${enc(password)}/$streamId.$containerExtension"

    private fun parseCategories(body: String): Map<String, String> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyMap()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optNumberAsString("category_id") ?: return@mapNotNull null
            val name = o.optString("category_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.toMap()
    }

}

/** Panels return ids as either a JSON number or a JSON string; accept both. */
private fun JSONObject.optNumberAsString(key: String): String? {
    if (isNull(key)) return null
    val raw = opt(key) ?: return null
    val s = when (raw) {
        is Number -> if (raw.toDouble() == raw.toLong().toDouble()) raw.toLong().toString() else raw.toString()
        else -> raw.toString()
    }
    return s.takeIf { it.isNotBlank() && it != "null" }
}

/** File extension for the selected Xtream output format (FR-SRC-06). */
internal fun OutputFormat.extension(): String = when (this) {
    OutputFormat.TS -> "ts"
    OutputFormat.HLS -> "m3u8"
}
