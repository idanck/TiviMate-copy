import { signal } from '@preact/signals';
import type Hls from 'hls.js';
import type Mpegts from 'mpegts.js';
import { encodeHeaders, probeUrl, remuxUrl, streamUrl } from '../data/api';
import type { Channel } from '../data/model';
import { settings, updateSettings } from '../state/settings';
import { serverInfo } from '../state/store';

/**
 * The one player: a single media element that every screen shows (or, for radio, only hears),
 * as the television shell keeps one ExoPlayer behind every overlay.
 *
 * A stream is played by whichever decoder fits it: hls.js for HLS, mpegts.js for the raw MPEG-TS
 * an Xtream panel serves by default, the browser itself for progressive audio and video. All of
 * it comes through the server's proxy. When the browser cannot decode a channel's audio (AC-3,
 * mostly), the stream is asked for again through /api/remux, which hands back the same video
 * with AAC audio.
 *
 * A channel that has played and then drops is reconnected after 5 s, 10 s, then every 15 s, as on
 * Android (player/ReconnectPolicy). One that never started says so and waits for the user: a
 * dead address retried for ever would only hold the provider's single connection.
 */

export type Status = 'idle' | 'loading' | 'playing' | 'paused' | 'blocked' | 'reconnecting' | 'error';

export interface Playback {
  status: Status;
  channel: Channel | null;
  role: 'tv' | 'radio';
  message?: string;
  remuxed?: boolean;
  /** The browser refused sound without a click first, so the stream plays muted until one. */
  autoMuted?: boolean;
}

export const playback = signal<Playback>({ status: 'idle', channel: null, role: 'tv' });
export const volume = signal({ level: settings.value.volume, muted: settings.value.muted });

type Kind = 'hls' | 'ts' | 'flv' | 'native';

const AUDIO_EXT = /\.(mp3|aac|m4a|ogg|oga|opus|flac|wav)$/i;
const VIDEO_EXT = /\.(mp4|m4v|webm|mkv|mov)$/i;

export function kindFromUrl(url: string): Kind | null {
  let path: string;
  try {
    path = new URL(url).pathname;
  } catch {
    return null;
  }
  if (/\.m3u8?$/i.test(path)) return 'hls';
  if (/\.ts$/i.test(path)) return 'ts';
  if (/\.flv$/i.test(path)) return 'flv';
  if (AUDIO_EXT.test(path) || VIDEO_EXT.test(path)) return 'native';
  return null;
}

const RETRY_DELAYS = [5_000, 10_000, 15_000];

/** An HTTP status from the proxy, in words: 502 and 504 are the proxy's own, about the provider. */
function statusMessage(code: number | undefined): string {
  if (code === 502) return 'אין חיבור לספק';
  if (code === 504) return 'הספק לא ענה בזמן';
  if (code === 401) return 'נדרשת התחברות לשרת';
  return code && code >= 400 ? `הספק החזיר שגיאה (${code})` : 'השידור נקטע';
}

/** `localStorage['shidur.debug'] = '1'` writes what the decoders report to the console. */
const DEBUG = (() => {
  try {
    return localStorage.getItem('shidur.debug') === '1';
  } catch {
    return false;
  }
})();
const log = (...args: unknown[]) => DEBUG && console.info('[player]', ...args);

class Engine {
  readonly media: HTMLVideoElement;
  private hls: Hls | null = null;
  private mpegts: Mpegts.Player | null = null;
  /** Bumped by every play and stop, so the work of a superseded attempt drops itself. */
  private token = 0;
  private urlIndex = 0;
  private attempts = 0;
  private everPlayed = false;
  private useRemux = false;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private headers = '';

  constructor() {
    const v = document.createElement('video');
    v.playsInline = true;
    v.preload = 'none';
    v.volume = volume.value.level;
    v.muted = volume.value.muted;
    v.addEventListener('playing', () => {
      this.attempts = 0;
      this.everPlayed = true;
      this.set({ status: 'playing', message: undefined });
    });
    v.addEventListener('waiting', () => {
      if (playback.value.status === 'playing') this.set({ status: 'loading' });
    });
    v.addEventListener('pause', () => {
      if (playback.value.status === 'playing') this.set({ status: 'paused' });
    });
    v.addEventListener('volumechange', () => {
      volume.value = { level: v.volume, muted: v.muted };
      if (playback.value.autoMuted && v.muted) return; // the browser's choice, not the user's
      updateSettings({ volume: v.volume, muted: v.muted });
    });
    v.addEventListener('ended', () => this.failed(false, 'השידור הסתיים'));
    v.addEventListener('error', () => {
      if (this.hls || this.mpegts || !v.getAttribute('src')) return; // those report their own errors
      const code = v.error?.code;
      this.failed(code === MediaError.MEDIA_ERR_DECODE || code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED, 'הדפדפן לא הצליח לנגן את השידור');
    });
    this.media = v;
  }

  private set(patch: Partial<Playback>) {
    playback.value = { ...playback.value, ...patch };
  }

  play(channel: Channel, role: 'tv' | 'radio') {
    this.stop(false);
    this.urlIndex = 0;
    this.attempts = 0;
    this.everPlayed = false;
    this.useRemux = settings.value.remux === 'always' && !!serverInfo.value?.remux;
    this.headers = encodeHeaders(channel.userAgent || settings.value.userAgent || undefined, channel.headers);
    playback.value = { status: 'loading', channel, role, remuxed: this.useRemux, autoMuted: playback.value.autoMuted };
    this.media.poster = '';
    void this.start(++this.token);
  }

  /** Starts again from the first address, as "נסה שוב" does. */
  retry() {
    const { channel, role } = playback.value;
    if (channel) this.play(channel, role);
  }

  stop(clear = true) {
    this.token++;
    clearTimeout(this.retryTimer);
    this.teardown();
    if (clear) playback.value = { status: 'idle', channel: null, role: playback.value.role };
  }

  togglePause() {
    const s = playback.value.status;
    if (s === 'playing' || s === 'loading') {
      this.media.pause();
      this.set({ status: 'paused' });
    } else if (s === 'paused' || s === 'blocked') {
      // A live stream resumes at the live edge, not where it was paused: start it again.
      if (this.mpegts || this.hls) this.retry();
      else void this.resume();
    }
  }

  setVolume(level: number) {
    this.media.volume = Math.min(1, Math.max(0, level));
    if (this.media.muted && level > 0) this.media.muted = false;
  }

  toggleMute() {
    if (playback.value.autoMuted) this.set({ autoMuted: false });
    this.media.muted = !this.media.muted;
  }

  /**
   * Plays, and when the browser will not allow sound before the user has clicked anything (a
   * fresh tab opening on the last channel), plays muted and says so rather than showing nothing.
   */
  private async resume() {
    try {
      await this.media.play();
    } catch (err) {
      if ((err as DOMException)?.name !== 'NotAllowedError') return;
      if (!this.media.muted) {
        this.set({ autoMuted: true });
        this.media.muted = true;
        try {
          await this.media.play();
          return;
        } catch {
          // not even muted
        }
      }
      this.set({ status: 'blocked' });
    }
  }

  private teardown() {
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    if (this.mpegts) {
      const p = this.mpegts;
      this.mpegts = null;
      try {
        p.pause();
        p.unload();
        p.detachMediaElement();
        p.destroy();
      } catch {
        // already torn down
      }
    }
    // Dropping the source closes the connection to the provider, which may allow only one.
    this.media.removeAttribute('src');
    this.media.load();
  }

  private async start(token: number) {
    const channel = playback.value.channel;
    if (!channel) return;
    const url = channel.urls[this.urlIndex];
    let kind = kindFromUrl(url);
    if (!kind && !this.useRemux) {
      try {
        const r = await fetch(probeUrl(url, this.headers), { credentials: 'same-origin' });
        const info = r.ok ? await r.json() : null;
        if (token !== this.token) return;
        if (!info || info.status >= 400) return this.failed(false, statusMessage(info?.status ?? r.status));
        kind = info.kind === 'hls' ? 'hls' : info.kind === 'ts' ? 'ts' : info.kind === 'flv' ? 'flv' : 'native';
      } catch {
        if (token !== this.token) return;
        return this.failed(false, 'אין חיבור לשרת');
      }
    }
    if (this.useRemux) {
      this.set({ remuxed: true });
      await this.attachMpegts(token, remuxUrl(url, this.headers), 'mpegts');
    } else if (kind === 'hls') {
      await this.attachHls(token, streamUrl(url, this.headers));
    } else if (kind === 'ts' || kind === 'flv') {
      await this.attachMpegts(token, streamUrl(url, this.headers), kind === 'flv' ? 'flv' : 'mpegts');
    } else {
      this.media.src = streamUrl(url, this.headers);
      await this.resume();
    }
  }

  private async attachHls(token: number, src: string) {
    const { default: HlsClass } = await import('hls.js');
    if (token !== this.token) return;
    if (!HlsClass.isSupported()) {
      // Safari plays HLS itself.
      this.media.src = src;
      return this.resume();
    }
    const hls = new HlsClass({ enableWorker: true, backBufferLength: 30, liveDurationInfinity: true, manifestLoadingMaxRetry: 2, levelLoadingMaxRetry: 2 });
    this.hls = hls;
    hls.on(HlsClass.Events.MANIFEST_PARSED, () => void this.resume());
    hls.on(HlsClass.Events.ERROR, (_e, data) => {
      log('hls error', data.type, data.details, data.fatal);
      if (token !== this.token) return;
      const codec = data.details === HlsClass.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR ||
        data.details === HlsClass.ErrorDetails.BUFFER_INCOMPATIBLE_CODECS_ERROR ||
        data.details === HlsClass.ErrorDetails.BUFFER_ADD_CODEC_ERROR;
      if (codec) return this.failed(true, 'הדפדפן לא מכיר את הקידוד של הערוץ');
      if (!data.fatal) return;
      if (data.type === HlsClass.ErrorTypes.MEDIA_ERROR && !this.mediaRecovered) {
        this.mediaRecovered = true;
        hls.recoverMediaError();
        return;
      }
      this.failed(false, statusMessage(data.response?.code));
    });
    this.mediaRecovered = false;
    hls.loadSource(src);
    hls.attachMedia(this.media);
  }

  private mediaRecovered = false;
  private failedFor = -1;

  private async attachMpegts(token: number, src: string, type: 'mpegts' | 'flv') {
    const { default: mpegts } = await import('mpegts.js');
    if (token !== this.token) return;
    // Not mpegts.isSupported(): that also asks for H.264, which an audio-only stream does not need.
    // What the stream really carries is checked when it arrives (MEDIA_INFO, MSE errors).
    if (typeof MediaSource === 'undefined') return this.failed(false, 'הדפדפן הזה לא יכול לנגן שידורי TS');
    mpegts.LoggingControl.applyConfig({ enableAll: false, enableError: DEBUG, enableWarn: DEBUG });
    const player = mpegts.createPlayer(
      // Absolute: mpegts.js fetches from a worker built from a blob, where "/api/…" means nothing.
      { type, isLive: true, url: new URL(src, location.href).href },
      {
        enableWorker: true,
        lazyLoad: false,
        autoCleanupSourceBuffer: true,
        liveBufferLatencyChasing: true,
        liveBufferLatencyMaxLatency: 6,
        liveBufferLatencyMinRemain: 1.5,
      },
    );
    this.mpegts = player;
    player.on(mpegts.Events.MEDIA_INFO, (info: Mpegts.MSEPlayerMediaInfo) => {
      log('media info', info?.mimeType, info?.videoCodec, info?.audioCodec);
      if (token !== this.token) return;
      // mpegts.js demuxes AC-3, but the browser may have nothing to decode it with: the picture
      // would play in silence. Better to ask the server for AAC straight away.
      const codec = info?.audioCodec;
      if (codec && /^(ac-3|ec-3)/i.test(codec) && !this.useRemux && typeof MediaSource !== 'undefined' &&
          !MediaSource.isTypeSupported(`audio/mp4; codecs="${codec}"`)) {
        this.failed(true, 'הדפדפן לא מכיר את קידוד השמע של הערוץ');
      }
    });
    player.on(mpegts.Events.ERROR, (type: string, detail: string, info: { code?: number } | undefined) => {
      log('mpegts error', type, detail, info);
      if (token !== this.token) return;
      const codec = detail === mpegts.ErrorDetails.MEDIA_CODEC_UNSUPPORTED || detail === mpegts.ErrorDetails.MEDIA_MSE_ERROR ||
        detail === mpegts.ErrorDetails.MEDIA_FORMAT_UNSUPPORTED;
      if (type === mpegts.ErrorTypes.MEDIA_ERROR && codec) return this.failed(true, 'הדפדפן לא מכיר את הקידוד של הערוץ');
      this.failed(false, statusMessage(info?.code));
    });
    player.on(mpegts.Events.LOADING_COMPLETE, () => {
      // A live stream has no end: the provider closed it. What is already buffered still plays;
      // the stream counts as over once the picture stops moving.
      if (token === this.token) this.watchForEnd(token);
    });
    player.attachMediaElement(this.media);
    player.load();
    await this.resume();
  }

  private watchForEnd(token: number) {
    let last = this.media.currentTime;
    let still = 0;
    const tick = () => {
      if (token !== this.token) return;
      const t = this.media.currentTime;
      still = t > last || this.media.paused ? 0 : still + 1;
      last = t;
      if (still >= 2) this.failed(false, 'הספק סגר את השידור');
      else setTimeout(tick, 1000);
    };
    setTimeout(tick, 1000);
  }

  /**
   * One attempt has failed. In order: the server's AAC remux, if the trouble is a codec; the
   * channel's next address; and then, for a channel that was playing, a reconnect.
   */
  private failed(codec: boolean, message: string) {
    const token = this.token;
    log('failed', { codec, message, url: this.urlIndex, remux: this.useRemux, played: this.everPlayed });
    // A dropped stream is often reported twice (the decoder's error and the element's "ended").
    if (this.failedFor === token) return;
    this.failedFor = token;
    const remuxOk = !!serverInfo.value?.remux && settings.value.remux !== 'never';
    if (codec && !this.useRemux && remuxOk) {
      this.useRemux = true;
      this.teardown();
      this.set({ status: 'loading', remuxed: true });
      void this.start(++this.token);
      return;
    }
    const channel = playback.value.channel;
    if (!channel) return;
    if (this.urlIndex < channel.urls.length - 1) {
      this.urlIndex++;
      this.teardown();
      void this.start(++this.token);
      return;
    }
    this.teardown();
    if (!this.everPlayed || codec) {
      this.set({ status: 'error', message });
      return;
    }
    const delay = RETRY_DELAYS[Math.min(this.attempts, RETRY_DELAYS.length - 1)];
    this.attempts++;
    this.set({ status: 'reconnecting', message: `${message} · מתחבר מחדש בעוד ${Math.round(delay / 1000)} שניות` });
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => {
      if (token !== this.token) return;
      this.urlIndex = 0;
      this.set({ status: 'loading' });
      void this.start(++this.token);
    }, delay);
  }
}

export const engine = new Engine();
