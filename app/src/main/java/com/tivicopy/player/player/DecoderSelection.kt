package com.tivicopy.player.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.tivicopy.player.data.model.DecoderMode

/**
 * Codec selection that honours an independent hardware/software choice for video and audio
 * (FR-PLB-04).
 *
 * `MediaCodecSelector` is the only lever that actually changes which decoder MediaCodec hands
 * back, so the two renderer families are built with two different selectors rather than the
 * single one `DefaultRenderersFactory` would otherwise share between them.
 *
 * "Software" here means a non-hardware-accelerated MediaCodec decoder, which is what fixes the
 * common "this one channel is green/blocky/silent" report. It does not bundle an external
 * software codec library; when the device exposes no software decoder for a mime type the
 * hardware one is still offered rather than failing the channel outright.
 */
class SelectiveRenderersFactory(
    context: Context,
    private val videoMode: DecoderMode,
    private val audioMode: DecoderMode,
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        // Fall through to the next decoder rather than surfacing an init failure immediately;
        // FR-PLB-05's explicit software retry sits above this as a second line of defence.
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            selectorFor(videoMode, mediaCodecSelector),
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableFloatOutput: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            selectorFor(audioMode, mediaCodecSelector),
            enableFloatOutput,
            spectrumSink(context, enableFloatOutput),
            eventHandler,
            eventListener,
            out,
        )
    }

    /**
     * The same sink `DefaultRenderersFactory` builds — its defaults, and the float-output choice it
     * was given — with one pass-through processor in it: a [TeeAudioProcessor] that lets the radio's
     * equaliser read the samples on their way out (ADR-031). It changes nothing it hands on; when
     * nothing is showing the bars it does nothing but hand on.
     */
    private fun spectrumSink(context: Context, enableFloatOutput: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(AudioSpectrum.Sink())))
            .build()

    private fun selectorFor(mode: DecoderMode, base: MediaCodecSelector): MediaCodecSelector =
        when (mode) {
            DecoderMode.AUTO -> base
            DecoderMode.HARDWARE -> preferring(base) { it.hardwareAccelerated }
            DecoderMode.SOFTWARE -> preferring(base) { !it.hardwareAccelerated }
        }

    /**
     * Reorders the platform's decoder list so the preferred kind comes first, keeping the rest
     * as fallbacks. Filtering the others out entirely would turn "prefer software" into "no
     * playback at all" on devices that expose only hardware decoders for a codec.
     */
    private fun preferring(
        base: MediaCodecSelector,
        preferred: (MediaCodecInfo) -> Boolean,
    ): MediaCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = base.getDecoderInfos(mimeType, secure, tunneling)
        val (first, rest) = all.partition(preferred)
        first + rest
    }
}
