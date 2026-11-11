package com.idanck.shidur.player.timeshift

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Serves a [TimeshiftSession]'s playlist at `timeshift://<session>/live.m3u8` (ADR-065).
 *
 * The first load waits for the session's first segment, so the player starts as soon as there is
 * one; a session that failed throws its error from here, which is how the provider's 404 or a
 * dropped connection reaches the engine as the same exception a direct stream would have raised.
 * The segments themselves are `file://` addresses the platform's own source reads.
 */
class TimeshiftPlaylistDataSource : BaseDataSource(/* isNetwork = */ false) {
    private var uri: Uri? = null
    private var data: ByteArray = ByteArray(0)
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val session = TimeshiftSession.find(dataSpec.uri.host)
            ?: throw IOException("no timeshift session ${dataSpec.uri.host}")
        session.awaitPlayable(FIRST_SEGMENT_TIMEOUT_MS)
        data = session.playlist()
        position = dataSpec.position.toInt().coerceIn(0, data.size)
        transferStarted(dataSpec)
        return (data.size - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, data.size - position)
        System.arraycopy(data, position, buffer, offset, n)
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    companion object {
        /** A channel whose first picture to start from has not come in this long is played directly. */
        const val FIRST_SEGMENT_TIMEOUT_MS = 15_000L
    }
}

/**
 * The player's data sources: the timeshift playlist for its own scheme, [base] for everything
 * else, segment files and every direct stream included.
 */
class TimeshiftRoutingDataSourceFactory(private val base: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = Routing(base.createDataSource())

    private class Routing(private val base: DataSource) : DataSource {
        private var active: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            base.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.uri.scheme == TimeshiftSession.SCHEME) {
                TimeshiftPlaylistDataSource().also { s -> listeners.forEach(s::addTransferListener) }
            } else {
                base
            }
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(active).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}
