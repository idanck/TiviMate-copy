import { effect, signal } from '@preact/signals';

/**
 * Per-browser preferences. Small and needed before the first frame, so they live in
 * localStorage; a private window or blocked storage just means the defaults.
 */
export interface Settings {
  /** The global guide offset (FR-EPG-11), in minutes; a channel's own tvg-shift wins. */
  epgOffsetMinutes: number;
  /** Sent to providers instead of the server's default; empty for the default. */
  userAgent: string;
  /** Re-encode a channel's audio on the server when the browser cannot play it. */
  remux: 'auto' | 'always' | 'never';
  /** Start the last channel when the app opens. */
  autoplay: boolean;
  volume: number;
  muted: boolean;
  lastChannelKey?: string;
  lastRadioKey?: string;
  liveFilter?: string;
}

const KEY = 'shidur.settings';
const DEFAULTS: Settings = { epgOffsetMinutes: 0, userAgent: '', remux: 'auto', autoplay: true, volume: 1, muted: false };

function load(): Settings {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}

export const settings = signal<Settings>(load());

effect(() => {
  const value = settings.value;
  try {
    localStorage.setItem(KEY, JSON.stringify(value));
  } catch {
    // storage unavailable: the preference lasts until the page closes
  }
});

export function updateSettings(patch: Partial<Settings>) {
  settings.value = { ...settings.value, ...patch };
}
