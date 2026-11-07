import { signal } from '@preact/signals';
import { HttpError, probeStream, requestHeaders, type RequestHeaders } from '../data/api';
import type { Channel } from '../data/model';
import { describe } from '../data/sources';
import { settings, updateSettings } from '../state/settings';

/**
 * The one player: a single media element that every screen shows (or, for radio, only hears),
 * as the television shell keeps one ExoPlayer behind every overlay.
 *
 * On the television the stream is played by the television itself (ADR-057). The page cannot read
 * a provider's stream through MSE — hls.js and mpegts.js would need CORS headers that providers do
 * not send — but a media element may load any address, and webOS's own player decodes HLS,
 * MPEG-TS and AC-3 in hardware. So the address goes straight to `<video>`, with its type when
 * the address does not show one: the service (service/net.js `probe`) reads the first bytes and
 * follows the redirects an Xtream panel answers with.
 *
 * What the element cannot do is send a channel's own User-Agent or Referer: a provider that
 * insists on them plays here only if it accepts the television's own.
 *
 * A channel that has played and then drops is reconnected after 5 s, 10 s, then every 15 s, as on
 * Android (player/ReconnectPolicy). One that never started says so and waits for the user: a
 * dead address retried for ever would only hold the provider's single connection.
 *
 * "Never started" includes a stream that shows no picture within START_TIMEOUT_MS: the
 * television's player may sit in "loading" for ever on an address it cannot play, and with a
 * `<source>` it reports no error at all.
 */

export type Status = 'idle' | 'loading' | 'playing' | 'paused' | 'blocked' | 'reconnecting' | 'error';

export interface Playback {
  status: Status;
  channel: Channel | null;
  role: 'tv' | 'radio';
  message?: string;
  /** The browser refused sound without a click first, so the stream plays muted until one. */
  autoMuted?: boolean;
}

export const playback = signal<Playback>({ status: 'idle', channel: null, role: 'tv' });
export const volume = signal({ level: settings.value.volume, muted: settings.value.muted });

export type Kind = 'hls' | 'ts' | 'flv' | 'native';

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

/** The type the television's player is told, for a stream whose address does not say. */
export function mimeOf(kind: Kind | null): string | undefined {
  if (kind === 'hls') return 'application/vnd.apple.mpegurl';
  if (kind === 'ts') return 'video/mp2t';
  if (kind === 'flv') return 'video/x-flv';
  return undefined;
}

const RETRY_DELAYS = [5_000, 10_000, 15_000];
export const START_TIMEOUT_MS = 25_000;

function statusMessage(code: number | undefined): string {
  if (code === 401 || code === 403) return `הספק סירב לבקשה (${code})`;
  if (code === 404) return 'הערוץ לא נמצא אצל הספק (404)';
  return code && code >= 400 ? `הספק החזיר שגיאה (${code})` : 'השידור נקטע';
}

/** `localStorage['shidur.debug'] = '1'` writes what the player reports to the console. */
const DEBUG = (() => {
  try {
    return localStorage.getItem('shidur.debug') === '1';
  } catch {
    return false;
  }
})();
const log = (...args: unknown[]) => DEBUG && console.info('[player]', ...args);

/** The last few things the player did, for tools/cdp.mjs (`shidur.state()`, ui/debug.ts). */
export const playerLog: string[] = [];
function note(line: string) {
  playerLog.push(`${new Date().toISOString().slice(11, 19)} ${line}`);
  if (playerLog.length > 30) playerLog.shift();
}

class Engine {
  readonly media: HTMLVideoElement;
  /** Bumped by every play and stop, so the work of a superseded attempt drops itself. */
  private token = 0;
  private urlIndex = 0;
  private attempts = 0;
  private everPlayed = false;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private headers: RequestHeaders = {};
  private failedFor = -1;
  private startTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    const v = document.createElement('video');
    v.playsInline = true;
    v.preload = 'none';
    v.volume = volume.value.level;
    v.muted = volume.value.muted;
    v.addEventListener('playing', () => {
      note('playing');
      clearTimeout(this.startTimer);
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
    v.addEventListener('error', () => this.mediaError());
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
    this.headers = requestHeaders(channel.userAgent || settings.value.userAgent || undefined, channel.headers);
    playback.value = { status: 'loading', channel, role, autoMuted: playback.value.autoMuted };
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
    clearTimeout(this.startTimer);
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
      this.retry();
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
   * Plays, and when the platform will not allow sound before a key press (seen in browsers, not
   * expected on the television), plays muted and says so rather than showing nothing.
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
    // Dropping the source closes the connection to the provider, which may allow only one.
    while (this.media.firstChild) this.media.removeChild(this.media.firstChild);
    this.media.removeAttribute('src');
    this.media.load();
  }

  private async start(token: number) {
    const channel = playback.value.channel;
    if (!channel) return;
    clearTimeout(this.startTimer);
    this.startTimer = setTimeout(() => {
      if (token === this.token && playback.value.status !== 'playing') this.failed(false, 'השידור לא התחיל');
    }, START_TIMEOUT_MS);
    let url = channel.urls[this.urlIndex];
    let kind = kindFromUrl(url);
    if (!kind) {
      try {
        const info = await probeStream(url, this.headers);
        if (token !== this.token) return;
        if (info.status >= 400) return this.failed(false, statusMessage(info.status));
        if (info.kind !== 'unknown') kind = info.kind;
        url = info.finalUrl || url;
      } catch (err) {
        if (token !== this.token) return;
        return this.failed(false, err instanceof HttpError ? describe(err) : 'אין חיבור לספק');
      }
    }
    if (Object.keys(this.headers).length > 0) log('the television player cannot send these headers', this.headers);
    log('play', kind, url);
    note(`play ${kind ?? '?'} #${this.urlIndex}`);
    const mime = mimeOf(kind);
    if (mime) {
      const source = document.createElement('source');
      source.src = url;
      source.type = mime;
      // With a <source>, a failure is reported on the source element, not on the video.
      source.addEventListener('error', () => {
        if (token === this.token) this.mediaError();
      });
      this.media.appendChild(source);
      this.media.load();
    } else {
      this.media.src = url;
    }
    await this.resume();
  }

  private mediaError() {
    if (!this.media.getAttribute('src') && !this.media.firstChild) return; // torn down on purpose
    const code = this.media.error?.code;
    log('media error', code, this.media.error?.message);
    note(`media error ${code ?? 'none'}`);
    this.failed(code === MediaError.MEDIA_ERR_DECODE || code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED, 'הטלוויזיה לא הצליחה לנגן את השידור');
  }

  /**
   * One attempt has failed. In order: the channel's next address, and then, for a channel that
   * was playing, a reconnect.
   */
  private failed(codec: boolean, message: string) {
    const token = this.token;
    clearTimeout(this.startTimer);
    log('failed', { codec, message, url: this.urlIndex, played: this.everPlayed });
    note(`failed: ${message}`);
    // A dropped stream is often reported twice (the element's error and its "ended").
    if (this.failedFor === token) return;
    this.failedFor = token;
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
