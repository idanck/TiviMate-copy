package com.idanck.shidur.data

import android.app.Application
import com.idanck.shidur.data.podcast.PodcastChapters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Podcasting 2.0 chapters file (ADR-067). Robolectric for `org.json`, a platform class. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PodcastChaptersTest {

    @Test
    fun `reads the listed chapters in order`() {
        val raw = """
            {"version":"1.2.0","chapters":[
              {"startTime":1290.5,"title":"שלישי"},
              {"startTime":0,"title":"פתיחה"},
              {"startTime":252,"title":"שני"},
              {"startTime":300,"title":"hidden","toc":false},
              {"startTime":400},
              {"title":"no time"}
            ]}
        """.trimIndent()
        val chapters = PodcastChapters.parse(raw)
        assertEquals(listOf("פתיחה", "שני", "שלישי"), chapters.map { it.title })
        assertEquals(listOf(0L, 252_000L, 1_290_500L), chapters.map { it.startMs })
        assertEquals(1, PodcastChapters.at(chapters, 600_000))
        assertEquals(-1, PodcastChapters.at(emptyList(), 5))
    }

    @Test
    fun `anything else is no chapters`() {
        assertEquals(emptyList<PodcastChapters.Chapter>(), PodcastChapters.parse("<html/>"))
        assertEquals(emptyList<PodcastChapters.Chapter>(), PodcastChapters.parse("{}"))
    }
}
