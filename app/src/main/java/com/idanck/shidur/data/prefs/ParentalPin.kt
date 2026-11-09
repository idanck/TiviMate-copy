package com.idanck.shidur.data.prefs

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parental PIN storage (FR-PRF-04, FR-PRF-08).
 *
 * Only a salted PBKDF2 hash is persisted — the PIN itself is never written anywhere, so a
 * device backup or a `adb pull` of the preferences file does not disclose it. Verification is
 * constant-time.
 *
 * Recovery flow (documented for the user in Settings): the PIN cannot be recovered, only
 * reset, and resetting it clears every channel and group lock so a forgotten PIN can never
 * leave content permanently inaccessible.
 */
object ParentalPin {

    private const val ITERATIONS = 60_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"

    data class Hashed(val hash: String, val salt: String)

    fun isValidPin(pin: String): Boolean =
        pin.length in 4..6 && pin.all(Char::isDigit)

    fun hash(pin: String): Hashed {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = derive(pin, salt)
        return Hashed(
            hash = Base64.encodeToString(digest, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
        )
    }

    fun verify(pin: String, hash: String, salt: String): Boolean {
        if (hash.isEmpty() || salt.isEmpty()) return false
        return try {
            val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
            val expected = Base64.decode(hash, Base64.NO_WRAP)
            val actual = derive(pin, saltBytes)
            constantTimeEquals(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Failed-attempt backoff (FR-PRF-07). In-memory only: a restart clears it, which is the right
 * trade-off for a living-room device — the lockout deters a child guessing, not an attacker
 * with the unlocked device and a debugger.
 */
class PinAttemptLimiter(
    private val threshold: Int = 3,
    private val baseBackoffMillis: Long = 15_000,
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun remainingLockMillis(now: Long = System.currentTimeMillis()): Long =
        (lockedUntil - now).coerceAtLeast(0)

    fun isLocked(now: Long = System.currentTimeMillis()): Boolean = remainingLockMillis(now) > 0

    fun onFailure(now: Long = System.currentTimeMillis()) {
        failures++
        if (failures >= threshold) {
            val steps = failures - threshold
            val backoff = baseBackoffMillis shl steps.coerceAtMost(5)
            lockedUntil = now + backoff
        }
    }

    fun onSuccess() {
        failures = 0
        lockedUntil = 0
    }
}

/**
 * What waits for the PIN (FR-PRF-04/05). Kept apart from the screens so the rules can be read —
 * and tested — in one place. Nothing is gated until a PIN exists: a lock without a PIN has
 * nothing to check against, and the switches that scope it are moot until then.
 */
object ParentalGate {

    /** Playing a locked channel, or one in a locked group, with "require the PIN" on. */
    fun channelNeedsPin(
        channelLocked: Boolean,
        groupLocked: Boolean,
        pinSet: Boolean,
        lockChannels: Boolean,
        unlocked: Boolean,
    ): Boolean = pinSet && lockChannels && !unlocked && (channelLocked || groupLocked)

    /** Opening Settings at all, when the user asked for that. */
    fun settingsNeedPin(pinSet: Boolean, lockSettings: Boolean, unlocked: Boolean): Boolean =
        pinSet && lockSettings && !unlocked

    /**
     * Loosening parental controls — changing the PIN, a lock-scope switch, taking a lock off a
     * channel. Removing the PIN is deliberately not here: it is the documented recovery for a
     * forgotten PIN (FR-PRF-08), and it clears every lock, which is plain to see.
     */
    fun parentalChangeNeedsPin(pinSet: Boolean, unlocked: Boolean): Boolean = pinSet && !unlocked
}
