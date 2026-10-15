# ADR-031 — The radio's equaliser listens to the player, not the room

**Status:** accepted. Adds the equaliser to the radio screen (ADR-027, ADR-030).

## Context

Studio's radio screen (`docs/design/studio/canvas/Radio.dc.html`) has a row of 32 bars under the
song that move with the music. On the canvas the rhythm is simulated. In the app it has to come
from somewhere, and Android offers two places:

- **`android.media.audiofx.Visualizer`** on the player's audio session. It needs the
  `RECORD_AUDIO` permission — a microphone permission, for an app that has no reason to ask for
  one — is deprecated in spirit on recent Android, and on several television boxes returns silence
  for sessions it does not own.
- **The PCM the player is already decoding.** Media3's `TeeAudioProcessor` sits in the audio sink
  and hands each buffer to a callback on its way to the output, unchanged.

`MIGRATION.md` records the choice as closed: the second.

## Decision

- `SelectiveRenderersFactory` builds the same `DefaultAudioSink` `DefaultRenderersFactory` would —
  its defaults and the float-output flag it is given — with one processor in it: a
  `TeeAudioProcessor` writing to `AudioSpectrum.Sink` (`player/AudioSpectrum.kt`).
- The sink does work only while the equaliser is on screen (`AudioSpectrum.listening`, switched by
  the composable as it enters and leaves composition). Otherwise a buffer costs one volatile read.
- While listening it downmixes 16-bit or float PCM to mono into a 1024-sample window, applies a
  Hann window and a radix-2 FFT, and reduces the result to 32 log-spaced bands from 60 Hz to
  12 kHz on a −60 dB floor. Bars rise at once and fall by 18% a step, published at most every
  70 ms (Studio's `--st-eq-step`).
- `ui/common/Equalizer.kt` draws them in a `Canvas` that reads the spectrum in its draw lambda, so
  a step redraws one node and recomposes nothing. Paused, the bars rest at the canvas's 0.06.

## Alternatives rejected

- **`Visualizer`.** The microphone permission alone rules it out.
- **A simulated rhythm, as on the canvas.** It would look right and mean nothing; a paused or
  silent station with dancing bars is a small lie on the one screen that is about the sound.

## Consequences

- No new permission. The audio path gains one pass-through processor; the samples reaching the
  speaker are the ones that would have reached it anyway.
- Audio passed through encoded (AC-3/E-AC-3 to an HDMI receiver) does not go through processors,
  so on such a setup the bars rest. That is the honest answer: the app is not decoding that sound.
- The arithmetic is pure and tested (`SpectrumMathTest`); the audio path needs a device, as
  `docs/10` notes everywhere else.
