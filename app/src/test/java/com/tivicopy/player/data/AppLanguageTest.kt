package com.tivicopy.player.data

import com.tivicopy.player.data.prefs.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-UI-23: the interface language resolves to a locale the user chose, and to the device's own
 * when they have not chosen one.
 *
 * This covers the resolution only. Whether `createConfigurationContext` then hands back Hebrew
 * strings and a right-to-left layout is a platform behaviour that needs a device or an emulator;
 * nothing here claims to have proved it.
 */
class AppLanguageTest {

    @Test
    fun `a stored choice resolves to its language`() {
        assertEquals(AppLanguage.HEBREW, AppLanguage.fromStored("HEBREW"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStored("ENGLISH"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("SYSTEM"))
    }

    @Test
    fun `nothing stored means follow the device`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(""))
    }

    /**
     * A value written by a later build, or a corrupted preference file, must not crash the app
     * during `attachBaseContext` — the earliest and least recoverable point in the launch.
     */
    @Test
    fun `an unknown stored value falls back to the device language`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("KLINGON"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("hebrew"))
    }

    @Test
    fun `only a real choice carries a language tag`() {
        assertNull(AppLanguage.SYSTEM.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("he", AppLanguage.HEBREW.tag)
    }
}
