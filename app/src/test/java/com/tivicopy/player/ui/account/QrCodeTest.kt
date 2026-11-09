package com.tivicopy.player.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The television's QR code and the address beside it (ADR-059). */
class QrCodeTest {

    private val url = "https://example.web.app/pair?code=ABCD-EFGH"

    @Test
    fun `the code is a square of a QR version's size`() {
        val modules = qrModules(url)
        assertTrue(modules.all { it.size == modules.size })
        // Version n is 17 + 4n modules wide.
        assertEquals(0, (modules.size - 17) % 4)
        assertTrue("an address this short fits a small code", modules.size <= 41)
    }

    @Test
    fun `the code carries its three finder patterns`() {
        val m = qrModules(url)
        val n = m.size
        for ((x0, y0) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (i in 0 until 7) {
                assertTrue(m[y0][x0 + i])
                assertTrue(m[y0 + 6][x0 + i])
                assertTrue(m[y0 + i][x0])
                assertTrue(m[y0 + i][x0 + 6])
            }
        }
    }

    @Test
    fun `the address to type drops the scheme and the code`() {
        assertEquals("example.web.app/pair", pageAddress(url))
        assertEquals("shidur.example.com/pair", pageAddress("https://shidur.example.com/pair"))
    }
}
