import { signal, type Signal } from '@preact/signals';
import { HttpError, probeStream } from '../data/api';
import type { Channel } from '../data/model';
import { settings } from '../state/settings';
import { kindFromUrl, mimeOf } from './engine';

/**
 * One of Sport and News mode's panels (ADR-053): a `<video>` of its own, apart from the app's one
 * full-screen player (player/engine.ts), as Android keeps a `PanelPlayer` apart from its
 * `PlayerEngine`. It keeps what a panel needs — failover down a channel's addresses, a retry when
 * a live stream is closed — and leaves out the rest (the media session, resume marks).
 *
 * Every panel decodes its sound all the time and is muted unless it is the one heard: turning a
 * muted panel's audio off and on again would make it re-buffer on every press.
 *
 * Whether webOS 6 plays two or four `<video>` elements at once in a web app is not known yet
 * (ADR-053, Consequences). A panel whose picture the television refuses while another panel plays
 * says that the device cannot play another channel at the same time, and the others play on.
 */

export type PanelStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'failed';

export interface PanelState {
  status: PanelStatus;
  message?: string;
}

/** What the session needs of a panel; the tests give it a stand-in. */
export interface Panel {
  readonly id: number;
  readonly state: Signal<PanelState>;
  play(channel: Channel): void;
  release(): void;
  setAudible(audible: boolean): void;
  setPaused(paused: boolean): void;
}

const FREE_SOURCE_ID = 'builtin-israel-tv';
/** A stream that played steadily this long earns its one automatic retry back. */
const STEADY_MS = 60_000;
const RETRY_DELAY_MS = 2_000;

export const PANEL_MESSAGES = {
  deviceLimit: 'המכשיר לא מצליח לנגן עוד ערוץ במקביל.',
  closed: 'הספק סגר את השידור. ייתכן שהחשבון מתיר פחות חיבורים ממספר המסכים.',
  closedFree: 'שרת המשדר סגר את השידור. כנראה תקלה זמנית אצלו, לא אצל הספק.',
  unplayable: 'הטלוויזיה לא הצליחה לנגן את השידור',
  unreachable: 'אין חיבור לספק',
  timeout: 'הספק לא ענה בזמן',
} as const;

function statusMessage(code: number): string {
  if (code === 401 || code === 403) return `הספק סירב לבקשה (${code})`;
  if (code === 404) return 'הערוץ לא נמצא אצל הספק (404)';
  return `הספק החזיר שגיאה (${code})`;
}

export class PanelPlayer implements Panel {
  readonly state = signal<PanelState>({ status: 'idle' });
  readonly media: HTMLVideoElement;
  private channel: Channel | null = null;
  private token = 0;
  private urlIndex = 0;
  private retriesLeft = 1;
  private everPlayed = false;
  private audible = false;
  private failedFor = -1;
  private steadyTimer: ReturnType<typeof setTimeout> | undefined;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;

  /** @param othersPlaying whether another panel is showing a picture, which is what a refused decoder points at */
  constructor(readonly id: number, private readonly othersPlaying: () => boolean) {
    const v = document.createElement('video');
    v.playsInline = true;
    v.preload = 'none';
    v.muted = true;
    v.addEventListener('playing', () => {
      this.everPlayed = true;
      this.set({ status: 'playing', message: undefined });
      clearTimeout(this.steadyTimer);
      this.steadyTimer = setTimeout(() => (this.retriesLeft = 1), STEADY_MS);
    });
    v.addEventListener('waiting', () => {
      if (this.state.value.status === 'playing') this.set({ status: 'loading' });
    });
    v.addEventListener('ended', () => this.dropped());
    v.addEventListener('error', () => this.mediaError());
    this.media = v;
  }

  private set(patch: Partial<PanelState>) {
    this.state.value = { ...this.state.value, ...patch };
  }

  play(channel: Channel) {
    this.halt();
    this.channel = channel;
    this.urlIndex = 0;
    this.retriesLeft = 1;
    this.everPlayed = false;
    this.state.value = { status: 'loading' };
    void this.start(++this.token);
  }

  release() {
    this.halt();
    this.channel = null;
    this.state.value = { status: 'idle' };
  }

  setAudible(audible: boolean) {
    this.audible = audible;
    this.media.volume = settings.value.volume;
    this.media.muted = !audible || settings.value.muted;
  }

  setPaused(paused: boolean) {
    if (!this.channel) return;
    if (paused) {
      this.halt();
      this.set({ status: 'paused', message: undefined });
    } else if (this.state.value.status === 'paused') {
      // A live stream resumes at the live edge: start it again.
      this.play(this.channel);
    }
  }

  private halt() {
    this.token++;
    clearTimeout(this.retryTimer);
    clearTimeout(this.steadyTimer);
    this.teardown();
  }

  private teardown() {
    // Dropping the source closes the connection to the provider, which may allow only one.
    while (this.media.firstChild) this.media.removeChild(this.media.firstChild);
    this.media.removeAttribute('src');
    this.media.load();
  }

  private async start(token: number) {
    const channel = this.channel;
    if (!channel) return;
    let url = channel.urls[this.urlIndex];
    let kind = kindFromUrl(url);
    if (!kind) {
      try {
        const info = await probeStream(url, {});
        if (token !== this.token) return;
        if (info.status >= 400) return this.fail(statusMessage(info.status));
        if (info.kind !== 'unknown') kind = info.kind;
        url = info.finalUrl || url;
      } catch (err) {
        if (token !== this.token) return;
        return this.fail(err instanceof HttpError && err.code === 'timeout' ? PANEL_MESSAGES.timeout : PANEL_MESSAGES.unreachable);
      }
    }
    const mime = mimeOf(kind);
    if (mime) {
      const source = document.createElement('source');
      source.src = url;
      source.type = mime;
      source.addEventListener('error', () => {
        if (token === this.token) this.mediaError();
      });
      this.media.appendChild(source);
      this.media.load();
    } else {
      this.media.src = url;
    }
    this.setAudible(this.audible);
    try {
      await this.media.play();
    } catch {
      // Reported by the element's own error event, or a later play.
    }
  }

  private mediaError() {
    if (!this.media.getAttribute('src') && !this.media.firstChild) return; // torn down on purpose
    const code = this.media.error?.code;
    const codec = code === MediaError.MEDIA_ERR_DECODE || code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED;
    if (codec && this.othersPlaying()) return this.fail(PANEL_MESSAGES.deviceLimit);
    if (this.everPlayed && !codec) return this.dropped();
    if (this.nextAddress()) return;
    this.fail(PANEL_MESSAGES.unplayable);
  }

  /**
   * A stream that played has stopped: the provider closed it, or the server restarted. The
   * channel's next address, then the same one once more; and then the panel stops and says so,
   * rather than reconnecting by itself — past the account's limit, each reconnect would close
   * another panel's stream, and the panels would take the one connection from each other in turn.
   */
  private dropped() {
    if (this.failedFor === this.token) return; // reported twice: the error and the "ended"
    this.failedFor = this.token;
    if (!this.channel) return;
    if (this.nextAddress()) return;
    if (this.retriesLeft > 0) {
      this.retriesLeft--;
      this.teardown();
      this.set({ status: 'loading' });
      const token = ++this.token;
      this.retryTimer = setTimeout(() => {
        if (token !== this.token) return;
        this.urlIndex = 0;
        void this.start(token);
      }, RETRY_DELAY_MS);
      return;
    }
    this.fail(this.channel.sourceId === FREE_SOURCE_ID ? PANEL_MESSAGES.closedFree : PANEL_MESSAGES.closed);
  }

  private nextAddress(): boolean {
    const channel = this.channel;
    if (!channel || this.urlIndex >= channel.urls.length - 1) return false;
    this.urlIndex++;
    this.teardown();
    void this.start(++this.token);
    return true;
  }

  private fail(message: string) {
    this.halt();
    this.set({ status: 'failed', message });
  }
}
