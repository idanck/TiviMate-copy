package com.tivicopy.player.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The radio's equaliser, measured from the sound actually playing (ADR-031).
 *
 * Media3's [TeeAudioProcessor] sits in the player's own audio sink and hands every PCM buffer to
 * [Sink] on its way to the speaker, untouched. That is the whole reason this needs no
 * `RECORD_AUDIO` permission and no `Visualizer` session: it never listens to the device, only to
 * the samples this app is already decoding.
 *
 * The work is done only while something on screen is showing the bars ([listening]); the rest of
 * the time a buffer costs one volatile read. While listening, buffers are downmixed to mono into a
 * [WINDOW]-sample window, and at most every [PUBLISH_MS] (Studio's `--st-eq-step`) the window is
 * transformed and reduced to [BANDS] log-spaced bands in 0..1, which is what [bands] publishes.
 */
object AudioSpectrum {

    const val BANDS = 32

    private val _bands = MutableStateFlow(FloatArray(BANDS))

    /** The latest bands, 0..1, low frequencies first. All zero when nothing is being measured. */
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    /**
     * How many equalisers are on screen. A count, not a flag: the screensaver's equaliser is
     * composed over the radio's, and the one leaving must not switch off the one that stays —
     * which a flag did until 0.18.1, leaving the radio's bars at rest after the screensaver.
     */
    private val listeners = AtomicInteger(0)

    /** Whether anything is showing the bars. */
    val listening: Boolean get() = listeners.get() > 0

    /** Called by an equaliser entering the screen; pair each with one [stopListening]. */
    fun startListening() {
        listeners.incrementAndGet()
    }

    /** Called by an equaliser leaving the screen. The last one out rests the bars. */
    fun stopListening() {
        // A compare-and-set loop rather than updateAndGet, which needs API 24.
        while (true) {
            val current = listeners.get()
            val next = (current - 1).coerceAtLeast(0)
            if (listeners.compareAndSet(current, next)) {
                if (next == 0) _bands.value = FloatArray(BANDS)
                return
            }
        }
    }

    /** The sink [TeeAudioProcessor] writes to. One per player; the state it keeps is its own. */
    class Sink : TeeAudioProcessor.AudioBufferSink {
        private var sampleRate = 0
        private var channels = 0
        private var encoding = C.ENCODING_INVALID
        private val window = FloatArray(WINDOW)
        private var filled = 0
        private var lastPublish = 0L
        private val smoothed = FloatArray(BANDS)
        private val re = FloatArray(WINDOW)
        private val im = FloatArray(WINDOW)

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
            filled = 0
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!listening || channels <= 0 || sampleRate <= 0) return
            // A read-only view handed to us by the processor; its byte order is not the PCM's, so
            // it is set explicitly on a duplicate rather than trusted.
            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frames = data.remaining() / (2 * channels)
                    repeat(frames) {
                        var sum = 0f
                        repeat(channels) { sum += data.short / 32768f }
                        push(sum / channels)
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frames = data.remaining() / (4 * channels)
                    repeat(frames) {
                        var sum = 0f
                        repeat(channels) { sum += data.float }
                        push(sum / channels)
                    }
                }
                // Anything else is passed through to the output encoded (or is a format this does
                // not read); the bars simply rest.
                else -> return
            }
        }

        private fun push(sample: Float) {
            window[filled++] = sample
            if (filled < WINDOW) return
            filled = 0
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublish < PUBLISH_MS) return
            lastPublish = now
            val bands = SpectrumMath.bands(window, re, im, sampleRate, BANDS)
            for (i in 0 until BANDS) {
                // Up at once, down slowly: a bar that drops as fast as it rose flickers.
                smoothed[i] = max(bands[i], smoothed[i] * FALL)
            }
            _bands.value = smoothed.copyOf()
        }
    }

    /** Samples per transform: about 23ms at 44.1kHz, enough to resolve the lowest band. */
    internal const val WINDOW = 1024

    /** Studio's `--st-eq-step`: how often the bars move. */
    private const val PUBLISH_MS = 70L

    /** How much of its height a bar keeps from one step to the next when the sound drops. */
    private const val FALL = 0.82f
}

/** The arithmetic behind [AudioSpectrum], with no Android in it, so it can be tested on the JVM. */
internal object SpectrumMath {

    /** Lowest and highest frequencies the bars cover, in Hz. */
    const val LOW_HZ = 60f
    const val HIGH_HZ = 12_000f

    /** The quietest level a bar shows, in dB below full scale; anything quieter is a flat bar. */
    const val FLOOR_DB = -60f

    /**
     * [samples] reduced to [count] bands in 0..1, log-spaced between [LOW_HZ] and [HIGH_HZ].
     *
     * [re] and [im] are scratch arrays of the window's length, passed in so that a buffer arriving
     * dozens of times a second allocates nothing.
     */
    fun bands(samples: FloatArray, re: FloatArray, im: FloatArray, sampleRate: Int, count: Int): FloatArray {
        val n = samples.size
        // A Hann window, so a tone between two bins does not smear across the whole spectrum.
        for (i in 0 until n) {
            re[i] = samples[i] * (0.5f - 0.5f * cos(2 * PI * i / (n - 1)).toFloat())
            im[i] = 0f
        }
        fft(re, im)
        val out = FloatArray(count)
        val nyquist = sampleRate / 2f
        val high = min(HIGH_HZ, nyquist)
        val binHz = sampleRate.toFloat() / n
        val ratio = ln(high / LOW_HZ)
        for (b in 0 until count) {
            val f0 = LOW_HZ * kotlin.math.exp(ratio * b / count)
            val f1 = LOW_HZ * kotlin.math.exp(ratio * (b + 1) / count)
            val i0 = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0, n / 2 - 1)
            var peak = 0f
            for (i in i0..i1) {
                val magnitude = sqrt(re[i] * re[i] + im[i] * im[i]) / (n / 4f)
                if (magnitude > peak) peak = magnitude
            }
            val db = if (peak <= 0f) FLOOR_DB else 20f * log10(peak)
            out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        return out
    }

    /** In-place radix-2 FFT. [re] and [im] must be the same power-of-two length. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
