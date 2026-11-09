package com.idanck.shidur.data.handover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's provider answers the new app and nothing else (ADR-060). The platform's
 * `signature` permission is the first lock; this is the second, and the one a test can reach.
 */
class HandoverAccessTest {

    private val bridge = "com.tivicopy.player"
    private val shidur = "com.idanck.shidur"

    private fun allows(
        caller: String?,
        own: String = bridge,
        permission: Boolean = true,
        sameKey: Boolean = true,
    ) = HandoverAccess.allows(caller, own, permission, signatureMatches = { sameKey })

    @Test
    fun `the new app, holding the permission and signed with the same key, is let in`() {
        assertTrue(allows(shidur))
    }

    @Test
    fun `a package signed with another key is refused`() {
        assertFalse(allows(shidur, sameKey = false))
    }

    @Test
    fun `a package without the permission is refused`() {
        assertFalse(allows(shidur, permission = false))
    }

    @Test
    fun `any other package is refused, even signed with the same key`() {
        assertFalse(allows("com.example.other"))
        assertFalse(allows(bridge))
        assertFalse(allows("$shidur.debug"))
    }

    @Test
    fun `a caller the platform could not name is refused`() {
        assertFalse(allows(null))
    }

    @Test
    fun `debug builds pair with each other only`() {
        assertTrue(allows("$shidur.debug", own = "$bridge.debug"))
        assertFalse(allows(shidur, own = "$bridge.debug"))
    }

    @Test
    fun `the signature is asked about the caller itself`() {
        var asked: String? = null
        HandoverAccess.allows(shidur, bridge, holdsPermission = true, signatureMatches = { asked = it; true })
        assertTrue(asked == shidur)
    }
}
