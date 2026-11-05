import { effect, signal } from '@preact/signals';

/**
 * Per-browser preferences. Small and needed before the first frame, so they live in
 * localStorage; a private window or blocked storage just means the defaults.
 */
export interface Settings {
  /** The global guide offset (FR-EPG-11), in minutes; a channel's own tvg-shift wins. */
  epgOffsetMinutes: number;
  /** Sent to providers instead of the service's default; empty for the default. */
  userAgent: string;
  /** Start the last channel when the app opens. */
  autoplay: boolean;
  volume: number;
  muted: boolean;
  lastChannelKey?: string;
  lastRadioKey?: string;
  liveFilter?: string;
  /** Sport mode's panels as they were left, by channel key (ADR-053). */
  sportSlots: Array<string | null>;
  /** News mode's panels as they were left: a channel key, the headlines, or nothing. */
  newsSlots: Array<string | null>;
  /** News mode's two panels or four (ADR-045). */
  newsPanels: 2 | 4;
  /** News mode's running strip of headlines across the top (ADR-045). */
  newsTicker: boolean;
  /** The headlines' sources (FR-UI-33), empty until the viewer picks some. */
  newsSources: Array<{ url: string; enabled: boolean }>;
}

const KEY = 'shidur.settings';
const DEFAULTS: Settings = {
  epgOffsetMinutes: 0,
  userAgent: '',
  autoplay: true,
  volume: 1,
  muted: false,
  sportSlots: [],
  newsSlots: [],
  newsPanels: 2,
  newsTicker: false,
  newsSources: [],
};

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
