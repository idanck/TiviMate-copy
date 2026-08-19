package com.tivicopy.player.data.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** User-Agent presets (FR-NET-01). Providers commonly gate on this string alone. */
object UserAgents {
    const val DEFAULT = "TiviCopy/1.0 (Android)"
    val PRESETS: List<Pair<String, String>> = listOf(
        "App default" to DEFAULT,
        "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
        "Kodi" to "Kodi/20.2 (Linux; Android 12) libcurl/7.79.1",
        "Lavf" to "Lavf/60.16.100",
        "Android TV browser" to
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Smart TV" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36",
        "MX Player" to "MXPlayer/1.0",
    )
}

/** Request shaping for one source: UA, extra headers, timeouts, retry budget. */
data class RequestProfile(
    val userAgent: String = UserAgents.DEFAULT,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 30,
    val retries: Int = 2,
    /** FR-NET-05: opt-in, warned, off by default. */
    val allowInsecureTls: Boolean = false,
)

/**
 * Builds OkHttp clients that carry a source's request profile.
 *
 * Clients are cached per profile so connection pools are shared: a playlist refresh, the
 * guide download, the player's data source and the recorder all reuse the same sockets.
 */
object HttpFactory {

    private val cache = HashMap<RequestProfile, OkHttpClient>()

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Synchronized
    fun client(profile: RequestProfile): OkHttpClient = cache.getOrPut(profile) {
        val builder = base.newBuilder()
            .connectTimeout(profile.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(profile.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(profile.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor(profile))
        if (profile.allowInsecureTls) InsecureTls.apply(builder)
        builder.build()
    }

    fun request(url: String, profile: RequestProfile): Request {
        val b = Request.Builder().url(url).header("User-Agent", profile.userAgent)
        profile.headers.forEach { (k, v) -> if (k.isNotBlank()) b.header(k, v) }
        return b.build()
    }

    private class HeaderInterceptor(private val profile: RequestProfile) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                builder.header("User-Agent", profile.userAgent)
            }
            profile.headers.forEach { (k, v) ->
                if (k.isNotBlank() && original.header(k) == null) builder.header(k, v)
            }
            return chain.proceed(builder.build())
        }
    }
}

/**
 * Transparent decompression for guide downloads (FR-EPG-01).
 * Detects gzip and zip from the magic bytes rather than trusting the file extension, because
 * providers routinely serve `.xml` that is actually gzipped.
 */
object Decompressor {

    fun wrap(input: InputStream): InputStream {
        val buffered = input.buffered(64 * 1024)
        buffered.mark(4)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()
        if (read < 2) return buffered

        val b0 = magic[0].toInt() and 0xFF
        val b1 = magic[1].toInt() and 0xFF
        return when {
            b0 == 0x1F && b1 == 0x8B -> GZIPInputStream(buffered, 64 * 1024)
            b0 == 0x50 && b1 == 0x4B -> ZipInputStream(buffered).also { zip ->
                // Position on the first non-directory entry; guide zips hold a single file.
                var entry = zip.nextEntry
                while (entry != null && entry.isDirectory) entry = zip.nextEntry
            }
            else -> buffered
        }
    }
}

/** Opt-in trust-everything TLS. Isolated here so the unsafe code has exactly one home. */
internal object InsecureTls {
    fun apply(builder: OkHttpClient.Builder) {
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
        builder.sslSocketFactory(context.socketFactory, trustAll)
        builder.hostnameVerifier { _, _ -> true }
    }
}
