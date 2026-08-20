package com.tivicopy.player.data

import android.app.Application
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.xtream.XtreamClient
import com.tivicopy.player.data.xtream.parseSeries
import com.tivicopy.player.data.xtream.parseSeriesEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-PLB-28: a series is a container and an episode is a stream, and the two are not served from
 * the same place.
 *
 * The defect these pin down was a URL: a series was stored with an invented `series:<id>` stream
 * id and that id was sent through the movie endpoint, so every series tile answered with a
 * playback error. The URL builders and the payload parsers are pure, so the fix is provable
 * here; whether the panel then streams what it is asked for needs a real subscription, and
 * nothing below claims otherwise.
 *
 * Robolectric rather than a plain JVM test only because `org.json` is a stub in `android.jar`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class XtreamSeriesTest {

    private val client = XtreamClient("http://host:8080/", "user", "p@ss", RequestProfile())

    // ------------------------------------------------------------------ URLs

    @Test
    fun `an episode is served from the series endpoint with the local prefix removed`() {
        assertEquals(
            "http://host:8080/series/user/p%40ss/4211.mkv",
            client.episodeUrl("episode:4211", "mkv"),
        )
    }

    /** The old behaviour, kept honest: a movie still goes to `/movie/` unchanged. */
    @Test
    fun `a movie is still served from the movie endpoint`() {
        assertEquals(
            "http://host:8080/movie/user/p%40ss/900.mp4",
            client.vodUrl("900", "mp4"),
        )
    }

    @Test
    fun `an id without the prefix is passed through untouched`() {
        assertEquals(
            "http://host:8080/series/user/p%40ss/4211.ts",
            client.episodeUrl("4211", "ts"),
        )
    }

    // --------------------------------------------------------------- parsing

    @Test
    fun `a series is stored as a container, not as something playable`() {
        val body = """
            [{"series_id":31,"name":"Fauda","category_id":"7","cover":"http://c/1.png",
              "plot":"Tel Aviv","releaseDate":"2015-02-15","rating":"8.3"}]
        """.trimIndent()

        val series = parseSeries(body, mapOf("7" to "Drama")).single()

        assertEquals(XtreamClient.SERIES_PREFIX + "31", series.streamId)
        assertEquals("31", series.seriesId)
        // The pair that marks a container. A season number here would make it an episode
        // everywhere else in the app.
        assertNull(series.seasonNumber)
        assertEquals("Drama", series.categoryName)
        assertEquals("2015", series.year)
    }

    @Test
    fun `episodes carry a real provider id, extension and numbering`() {
        val body = """
            {"episodes":{"1":[
               {"id":"4211","title":"Episode 1","container_extension":"mkv","episode_num":1,
                "info":{"duration_secs":2712,"plot":"...","movie_image":"http://c/e1.png"}},
               {"id":4212,"title":"Episode 2","container_extension":"mp4","episode_num":"2",
                "info":{"duration_secs":2600}}]}}
        """.trimIndent()

        val episodes = parseSeriesEpisodes(body, "31")

        assertEquals(2, episodes.size)
        // The id the panel issued survives; only the local prefix is added.
        assertEquals(XtreamClient.EPISODE_PREFIX + "4211", episodes[0].streamId)
        assertEquals("mkv", episodes[0].containerExtension)
        assertEquals(1, episodes[0].seasonNumber)
        assertEquals(1, episodes[0].episodeNumber)
        assertEquals(2712, episodes[0].durationSeconds)
        assertEquals("31", episodes[0].seriesId)
        // Panels send the same fields as numbers or as strings, interchangeably.
        assertEquals(XtreamClient.EPISODE_PREFIX + "4212", episodes[1].streamId)
        assertEquals(2, episodes[1].episodeNumber)
    }

    /**
     * A season number is never left unset. It is the marker that keeps an episode out of the
     * on-demand grid, so a payload that omits it must not produce a tile that plays nothing.
     */
    @Test
    fun `a flat payload with no season still yields episodes with a season`() {
        val body = """{"episodes":[{"id":7,"title":"Pilot","episode_num":1}]}"""

        val episode = parseSeriesEpisodes(body, "31").single()

        assertNotNull(episode.seasonNumber)
        assertEquals(XtreamClient.EPISODE_PREFIX + "7", episode.streamId)
        assertEquals("mp4", episode.containerExtension)
    }

    @Test
    fun `a season declared on the episode itself is used`() {
        val body = """{"episodes":[{"id":8,"season":3,"episode_num":4,"title":"x"}]}"""

        assertEquals(3, parseSeriesEpisodes(body, "31").single().seasonNumber)
    }

    /** Some panels answer `player_api.php` with an HTML error page. Degrade, do not throw. */
    @Test
    fun `an unparsable payload degrades to nothing`() {
        assertTrue(parseSeriesEpisodes("<html>nope</html>", "31").isEmpty())
        assertTrue(parseSeries("<html>nope</html>", emptyMap()).isEmpty())
        assertTrue(parseSeriesEpisodes("""{"user_info":{}}""", "31").isEmpty())
    }

    @Test
    fun `an entry with no id is skipped rather than failing the series`() {
        val body = """{"episodes":{"1":[{"title":"no id"},{"id":9,"title":"fine"}]}}"""

        assertEquals(
            listOf(XtreamClient.EPISODE_PREFIX + "9"),
            parseSeriesEpisodes(body, "31").map { it.streamId },
        )
    }
}
