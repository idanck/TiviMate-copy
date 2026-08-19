package com.tivicopy.player.data.prefs

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts playlist credentials at rest with an Android Keystore key (NFR-SEC-01).
 *
 * The key never leaves the keystore, so the ciphertext in the database is useless on another
 * device. On API levels or devices where the keystore is unavailable the store degrades to a
 * pass-through and reports it, rather than silently pretending the data is protected —
 * [isHardwareBacked] drives the warning shown in Settings.
 */
object SecureStore {

    private const val KEY_ALIAS = "tivicopy.credentials.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "enc1:"

    @Volatile
    private var available: Boolean? = null

    val isHardwareBacked: Boolean
        get() = available ?: runCatching { key() != null }.getOrDefault(false).also { available = it }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val secret = runCatching { key() }.getOrNull() ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return stored
        val secret = runCatching { key() }.getOrNull() ?: return null
        return runCatching {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val body = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
