/**
 * The web app's data model: the Android app's (data/model/Models.kt), cut down to what a browser
 * player uses. Everything is plain JSON so it can go into IndexedDB as it is.
 */

export type SourceType = 'M3U_URL' | 'M3U_FILE' | 'XTREAM';

/** What a source feeds: the channel list, or the radio screen (ADR-015). */
export type SourceRole = 'tv' | 'radio';

export interface Source {
  id: string;
  name: string;
  type: SourceType;
  role: SourceRole;
  /** The playlist URL, or the Xtream panel root. Empty for an uploaded file. */
  url: string;
  username?: string;
  password?: string;
  /** Xtream live output: TS (the panel default) or HLS. */
  outputFormat: 'TS' | 'HLS';
  userAgent?: string;
  headers: Record<string, string>;
  /** An explicit guide address; otherwise the playlist's `url-tvg`, or the panel's xmltv.php. */
  epgUrl?: string;
  enabled: boolean;
  /** One of the built-in playlists (ADR-013, ADR-015), which the user can switch off but not edit. */
  builtIn?: 'israel-tv' | 'israel-radio';
  /** The uploaded playlist itself, for M3U_FILE. */
  fileText?: string;
  sortIndex: number;
  lastRefreshUtc?: number;
  lastRefreshOk?: boolean;
  lastRefreshMessage?: string;
  channelCount?: number;
  /** The guide address the last refresh settled on: [epgUrl], the playlist's own, or the panel's. */
  resolvedEpgUrl?: string;
}

export type CatchupMode = 'NONE' | 'DEFAULT' | 'APPEND' | 'SHIFT' | 'FLUSSONIC' | 'XC';

export interface Channel {
  /** `<sourceId>|<identity>`: stable across refreshes, and the key favourites are kept under. */
  key: string;
  sourceId: string;
  identity: string;
  name: string;
  tvgId?: string;
  logoUrl?: string;
  group: string;
  number?: number;
  /** The stream, then any further URLs the playlist gave the same channel (failover). */
  urls: string[];
  userAgent?: string;
  headers: Record<string, string>;
  tvgShiftMinutes: number;
  catchupMode: CatchupMode;
  catchupDays: number;
  /** Where the channel sits in its source, for "playlist order". */
  order: number;
}

export interface EpgChannel {
  id: string;
  displayNames: string[];
  iconUrl?: string;
}

export interface Programme {
  start: number;
  end: number;
  title: string;
  subtitle?: string;
  description?: string;
  category?: string;
  season?: number;
  episode?: number;
}

/** A source's guide, already bound to its channels: channel key → programmes in start order. */
export interface SourceGuide {
  sourceId: string;
  fetchedUtc: number;
  programmes: Record<string, Programme[]>;
  matched: number;
  total: number;
}
