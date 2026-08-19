package com.tivicopy.player.core

/**
 * Every user-visible failure carries a cause the UI can turn into plain language.
 * NFR-REL-06 forbids a bare "an error occurred", so the presentation layer is expected to
 * map [kind] to a localised string and show [detail] as supporting text.
 */
data class AppError(
    val kind: Kind,
    val detail: String? = null,
    val cause: Throwable? = null,
) {
    enum class Kind {
        Network,
        Timeout,
        Unauthorized,
        Forbidden,
        NotFound,
        EmptyResponse,
        ParseFailed,
        UnsupportedFormat,
        GeoBlocked,
        DrmUnsupported,
        DecoderFailed,
        OutsideCatchupWindow,
        CatchupUnsupported,
        InsufficientStorage,
        PermissionDenied,
        Cancelled,
        Unknown,
    }

    companion object {
        fun of(t: Throwable): AppError = when (t) {
            is java.net.SocketTimeoutException -> AppError(Kind.Timeout, t.message, t)
            is java.net.UnknownHostException -> AppError(Kind.Network, t.message, t)
            is java.net.ConnectException -> AppError(Kind.Network, t.message, t)
            is javax.net.ssl.SSLException -> AppError(Kind.Network, t.message, t)
            is java.io.IOException -> AppError(Kind.Network, t.message, t)
            else -> AppError(Kind.Unknown, t.message, t)
        }

        fun fromHttp(code: Int, body: String? = null): AppError = when (code) {
            401, 407 -> AppError(Kind.Unauthorized, "HTTP $code")
            403 -> AppError(if (looksGeoBlocked(body)) Kind.GeoBlocked else Kind.Forbidden, "HTTP $code")
            404, 410 -> AppError(Kind.NotFound, "HTTP $code")
            408, 504 -> AppError(Kind.Timeout, "HTTP $code")
            else -> AppError(Kind.Network, "HTTP $code")
        }

        /** FR-NET-06: recognise the common provider phrasing for a region/IP refusal. */
        private fun looksGeoBlocked(body: String?): Boolean {
            val b = body?.lowercase() ?: return false
            return listOf("geo", "region", "country", "not available in your", "blocked ip", "vpn")
                .any { it in b }
        }
    }
}

/** Minimal Result type so the data layer stays free of Android and of exceptions-as-flow-control. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val error: AppError) : Outcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AppError? = (this as? Err)?.error
    val isOk: Boolean get() = this is Ok

    inline fun <R> map(f: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }
}

inline fun <T> runOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    Outcome.Err(AppError.of(t))
}
