package com.tivicopy.player.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account's pure decisions (ADR-059): when the sign-in screen shows, and reading Functions' answers. */
class AccountModelTest {

    private val signedIn = AccountState.SignedIn(uid = "u1", name = "Viewer", email = "viewer@example.com")

    @Test
    fun `the sign-in screen shows only with the switch on and nobody signed in`() {
        assertTrue(signInRequired(AccountState.SignedOut, requireSignIn = true))
        assertFalse(signInRequired(AccountState.SignedOut, requireSignIn = false))
        assertFalse(signInRequired(signedIn, requireSignIn = true))
        assertFalse(signInRequired(signedIn, requireSignIn = false))
    }

    @Test
    fun `a build without a Firebase project never shows it`() {
        assertFalse(signInRequired(AccountState.Unavailable, requireSignIn = true))
        assertFalse(signInRequired(AccountState.Unavailable, requireSignIn = false))
    }

    @Test
    fun `a pairing is read from the function's answer`() {
        val pairing = parsePairing(
            mapOf(
                "code" to "ABCDEFGH",
                "display" to "ABCD-EFGH",
                "secret" to "s3cret",
                "expiresAt" to 1_800_000_000_000.0,
                "url" to "https://example.web.app/pair?code=ABCD-EFGH",
            ),
        )
        assertEquals(
            Pairing(
                code = "ABCDEFGH",
                display = "ABCD-EFGH",
                secret = "s3cret",
                expiresAtUtc = 1_800_000_000_000L,
                url = "https://example.web.app/pair?code=ABCD-EFGH",
            ),
            pairing,
        )
    }

    @Test
    fun `a pairing without a display code is shown in two halves`() {
        val pairing = parsePairing(
            mapOf("code" to "ABCDEFGH", "secret" to "s", "expiresAt" to 1L, "url" to "https://example.web.app/pair"),
        )
        assertEquals("ABCD-EFGH", pairing?.display)
    }

    @Test
    fun `an answer missing a field is not a pairing`() {
        assertNull(parsePairing(null))
        assertNull(parsePairing("ABCDEFGH"))
        assertNull(parsePairing(mapOf("code" to "ABCDEFGH", "secret" to "s", "url" to "u")))
        assertNull(parsePairing(mapOf("code" to "ABCDEFGH", "expiresAt" to 1L, "url" to "u")))
    }

    @Test
    fun `a claim is read with its token`() {
        assertEquals("approved" to "tok", parseClaim(mapOf("status" to "approved", "token" to "tok")))
        assertEquals("pending" to null, parseClaim(mapOf("status" to "pending")))
        assertEquals("invalid" to null, parseClaim(null))
        assertEquals("invalid" to null, parseClaim(mapOf("token" to "tok")))
    }
}
