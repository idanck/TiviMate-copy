package com.tivicopy.player.data.update

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import org.json.JSONObject

/**
 * The update descriptor the build publishes alongside each release APK (FR-UPD-01).
 *
 * Everything the app needs to decide "is there something newer, and is the file I downloaded
 * the file the publisher meant" lives here. The parser is deliberately strict: this document
 * decides which binary gets installed over the running app, so a field that is missing,
 * malformed or served over plain HTTP fails the whole check rather than being defaulted.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** Lower-case hex SHA-256 of the APK; verified after the download (FR-UPD-05). */
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    /** Release notes by BCP-47 language tag, as published. */
    val notes: Map<String, String>,
) {

    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    /** Notes in the interface language, falling back to English and then to whatever exists. */
    fun notesFor(languageTag: String): String? =
        notes[languageTag] ?: notes[languageTag.substringBefore('-')] ?: notes["en"]
            ?: notes.values.firstOrNull()

    companion object {
        /**
         * A ceiling on what the app is willing to fetch. The APK is around 16 MB; anything an
         * order of magnitude larger means the manifest is wrong or the host is not ours, and
         * either way filling the user's cache partition is not the right response.
         */
        const val MAX_SIZE_BYTES: Long = 200L * 1024 * 1024

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun parse(raw: String): Outcome<UpdateManifest> {
            val json = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "update manifest is not JSON", t))
            }

            val versionCode = json.optInt("versionCode", 0)
            if (versionCode <= 0) return invalid("versionCode")

            val versionName = json.optString("versionName").trim()
            if (versionName.isEmpty()) return invalid("versionName")

            val apkUrl = json.optString("apkUrl").trim()
            // Plain HTTP would let anything on the path swap the binary, and the signature check
            // the platform performs at install time is the last line of defence, not the first.
            if (!apkUrl.startsWith("https://", ignoreCase = true)) return invalid("apkUrl")

            val sha256 = json.optString("sha256").trim().lowercase()
            if (!SHA256_HEX.matches(sha256)) return invalid("sha256")

            val sizeBytes = json.optLong("sizeBytes", 0L)
            if (sizeBytes <= 0L || sizeBytes > MAX_SIZE_BYTES) return invalid("sizeBytes")

            val notes = buildMap {
                json.optJSONObject("notes")?.let { obj ->
                    obj.keys().forEach { key ->
                        val text = obj.optString(key).trim()
                        if (text.isNotEmpty()) put(key, text)
                    }
                }
            }

            return Outcome.Ok(
                UpdateManifest(
                    versionCode = versionCode,
                    versionName = versionName,
                    apkUrl = apkUrl,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    // Absent means "no floor stated"; the install itself still enforces the real one.
                    minSdk = json.optInt("minSdk", 0),
                    notes = notes,
                ),
            )
        }

        private fun invalid(field: String): Outcome<UpdateManifest> =
            Outcome.Err(AppError(AppError.Kind.ParseFailed, "update manifest: bad $field"))
    }
}
