package com.idanck.shidur.data

import com.idanck.shidur.data.youtube.LikedSong
import com.idanck.shidur.data.youtube.MusicSearchResult
import com.idanck.shidur.data.youtube.MusicTop
import com.idanck.shidur.data.youtube.YtArtist
import com.idanck.shidur.data.youtube.addRecent
import com.idanck.shidur.data.youtube.topResult
import com.idanck.shidur.data.youtube.MusicRepository
import com.idanck.shidur.data.youtube.PlayedSong
import com.idanck.shidur.data.youtube.SavedAlbum
import com.idanck.shidur.data.youtube.YtMusicList
import com.idanck.shidur.data.youtube.YtMusicParse
import com.idanck.shidur.data.youtube.YtSong
import com.idanck.shidur.data.youtube.mergeLiked
import com.idanck.shidur.data.youtube.nextSong
import com.idanck.shidur.data.youtube.previousSong
import com.idanck.shidur.data.youtube.quickPicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music tab's data (ADR-069): reading the callables, and what Shidur keeps for the account. */
class YouTubeMusicTest {

    private fun song(id: String) = YtSong(id, "Song $id", "Artist", "UCabcdefghijklmnopqrstuv", null, 200, true)

    @Test
    fun `songs and lists are read from the callables' maps`() {
        val songs = YtMusicParse.songs(
            listOf(
                mapOf("id" to "a", "title" to "A", "artist" to "Artist", "channelId" to "UCx", "thumb" to "t", "duration" to 222.0, "embeddable" to false),
                mapOf("id" to "a", "title" to "again"),
                "junk",
            ),
        )
        assertEquals(1, songs.size)
        assertEquals(222, songs.single().duration)
        assertFalse(songs.single().embeddable)
        val list = YtMusicParse.list(mapOf("id" to "OLAK5uy_x", "title" to "Album", "owner" to "Artist", "count" to 12, "album" to true))!!
        assertTrue(list.album)
        assertEquals(12, list.count)
        assertNull(YtMusicParse.list(mapOf("title" to "no id")))
    }

    @Test
    fun `liked songs are Shidur's first, newest first, then YouTube's not already there`() {
        val merged = mergeLiked(
            listOf(LikedSong(song("old"), 1), LikedSong(song("new"), 2)),
            listOf(song("yt"), song("old")),
        )
        assertEquals(listOf("new", "old", "yt"), merged.map { it.id })
    }

    @Test
    fun `quick picks put the songs played most first, then liked ones`() {
        val history = listOf(
            PlayedSong(song("b"), 30, "tv", null),
            PlayedSong(song("a"), 20, "phone", null),
            PlayedSong(song("b"), 10, "tv", null),
        )
        assertEquals(listOf("b", "a", "c"), quickPicks(history, listOf(song("c"), song("a"))).map { it.id })
        assertTrue(quickPicks(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `the queue moves on, and starts again only on repeat`() {
        val q = listOf(song("1"), song("2"))
        assertEquals("2", nextSong(q, "1", repeat = false)?.id)
        assertNull(nextSong(q, "2", repeat = false))
        assertEquals("1", nextSong(q, "2", repeat = true)?.id)
        assertEquals("1", previousSong(q, "2")?.id)
        assertEquals("1", previousSong(q, "1")?.id)
    }

    @Test
    fun `the account's documents carry the fields firestore rules allow`() {
        val like = MusicRepository.likeData(LikedSong(song("v").copy(title = "x".repeat(400)), 5))
        assertEquals(setOf("videoId", "title", "artist", "channelId", "thumb", "duration", "likedUtc", "updatedUtc"), like.keys)
        assertEquals(300, (like["title"] as String).length)
        val album = MusicRepository.albumData(SavedAlbum(YtMusicList("OLAK5uy_x", "A", "Artist", null, 3, true), 9))
        assertEquals(setOf("listId", "title", "artist", "thumb", "count", "savedUtc", "updatedUtc"), album.keys)
        val played = MusicRepository.historyData(PlayedSong(song("v"), 7, "car", "PLx"))
        assertEquals("tv", played["device"])
        assertEquals(
            setOf("videoId", "title", "artist", "channelId", "thumb", "duration", "playedUtc", "device", "context", "updatedUtc"),
            played.keys,
        )
    }

    @Test
    fun `a search answer is read, each item once`() {
        val result = YtMusicParse.search(
            mapOf(
                "songs" to listOf(mapOf("id" to "s1", "title" to "Song"), mapOf("id" to "s1", "title" to "again")),
                "artists" to listOf(mapOf("id" to "UCa", "title" to "Artist"), "junk"),
                "lists" to listOf(mapOf("id" to "OLAK5uy_a", "title" to "Album", "album" to true)),
            ),
        )
        assertEquals(listOf("s1"), result.songs.map { it.id })
        assertEquals(listOf("UCa"), result.artists.map { it.id })
        assertTrue(result.lists.single().album)
        assertTrue(YtMusicParse.search(null).isEmpty)
    }

    @Test
    fun `the top result is the artist named, else the first song, list or artist`() {
        val artist = YtArtist("UCa", "Dynoro - Topic", null)
        val list = YtMusicList("PLx", "Mix", "", null, 3, false)
        val full = MusicSearchResult(listOf(song("s1")), listOf(artist), listOf(list))
        assertEquals(MusicTop.OfArtist(artist), topResult(full, " dynoro "))
        assertEquals(MusicTop.OfSong(song("s1")), topResult(full, "in my mind"))
        assertEquals(MusicTop.OfList(list), topResult(full.copy(songs = emptyList()), "mix"))
        assertEquals(MusicTop.OfArtist(artist), topResult(MusicSearchResult(emptyList(), listOf(artist), emptyList()), "x"))
        assertNull(topResult(MusicSearchResult(emptyList(), emptyList(), emptyList()), "x"))
    }

    @Test
    fun `recent searches put the newest first, once each, and keep a few`() {
        val recent = addRecent(listOf("Dynoro", "omer adam"), "  Omer   Adam ")
        assertEquals(listOf("Omer Adam", "Dynoro"), recent)
        assertEquals(recent, addRecent(recent, "   "))
        assertEquals(3, addRecent(listOf("a", "b", "c"), "d", max = 3).size)
    }
}
