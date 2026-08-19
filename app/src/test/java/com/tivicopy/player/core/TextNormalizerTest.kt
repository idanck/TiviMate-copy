package com.tivicopy.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `canonical form drops quality tokens and punctuation`() {
        assertEquals("bbc one", TextNormalizer.canonical("BBC One HD"))
        assertEquals("bbc one", TextNormalizer.canonical("  BBC  One   FHD "))
        assertEquals("bbc one", TextNormalizer.canonical("12. BBC One [UK]"))
        assertEquals("canal plus", TextNormalizer.canonical("Canal+ 4K"))
    }

    @Test
    fun `canonical form strips diacritics`() {
        assertEquals("television francaise", TextNormalizer.canonical("Télévision Française"))
    }

    @Test
    fun `similar names score high and different ones do not`() {
        assertTrue(TextNormalizer.similarity("BBC One HD", "BBC One") > 0.9)
        assertTrue(TextNormalizer.similarity("Sky Sports Main Event", "Sky Sports Main Event FHD") > 0.9)
        assertTrue(TextNormalizer.similarity("BBC One", "CBS One") < 0.82)
        assertTrue(TextNormalizer.similarity("BBC One", "Discovery Channel") < 0.4)
    }

    @Test
    fun `an empty side never matches`() {
        assertEquals(0.0, TextNormalizer.similarity("", "BBC One"), 0.0001)
        assertEquals(0.0, TextNormalizer.similarity("HD", "4K"), 0.0001)
    }

    @Test
    fun `redaction removes credentials from every shape a provider URL takes`() {
        val query = TextNormalizer.redactUrl("http://host:8080/player_api.php?username=alice&password=hunter2&action=x")
        assertFalse(query.contains("alice"))
        assertFalse(query.contains("hunter2"))
        assertTrue(query.contains("action=x"))

        val path = TextNormalizer.redactUrl("http://host:8080/live/alice/hunter2/1234.ts")
        assertFalse(path.contains("alice"))
        assertFalse(path.contains("hunter2"))
        assertTrue(path.contains("1234.ts"))

        val basic = TextNormalizer.redactUrl("http://alice:hunter2@host/stream.m3u8")
        assertFalse(basic.contains("alice"))
        assertFalse(basic.contains("hunter2"))
    }
}
