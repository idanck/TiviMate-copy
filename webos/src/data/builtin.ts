import type { Source } from './model';

/**
 * The two playlists every install starts with, as on Android: Israel's free-to-air channels
 * (ADR-013, `data/builtin/IsraelPublicChannels.kt`) and Israeli radio (ADR-015,
 * `data/builtin/IsraelRadioStations.kt`). Both are published by a script on the Pi, not by this
 * repository.
 */
export const BUILT_IN_SOURCES: Source[] = [
  {
    id: 'builtin-israel-tv',
    name: 'ערוצים פתוחים בישראל',
    type: 'M3U_URL',
    role: 'tv',
    url: 'https://il-epg.idanck.com/channels.m3u',
    epgUrl: 'https://il-epg.idanck.com/guide.xml',
    outputFormat: 'TS',
    headers: {},
    enabled: true,
    builtIn: 'israel-tv',
    sortIndex: 0,
  },
  {
    id: 'builtin-israel-radio',
    name: 'רדיו ישראלי',
    type: 'M3U_URL',
    role: 'radio',
    url: 'https://il-epg.idanck.com/radio.m3u',
    outputFormat: 'TS',
    headers: {},
    enabled: true,
    builtIn: 'israel-radio',
    sortIndex: 1,
  },
];

/** Addresses a backup may carry for the same two playlists; they are already here. */
export const BUILT_IN_URLS = new Set([
  'https://il-epg.idanck.com/channels.m3u',
  'https://updates.idanck.com/il-epg/channels.m3u',
  'https://il-epg.idanck.com/radio.m3u',
]);
