import { batch, computed, signal } from '@preact/signals';
import workerSource from 'virtual:epg-worker';
import { HttpError, streamText } from '../data/api';
import { BUILT_IN_SOURCES } from '../data/builtin';
import { isRussianStation } from '../data/classify';
import { kv } from '../data/db';
import type { Channel, Programme, Source, SourceGuide } from '../data/model';
import { describe, loadSource, SourceError, sourceHeaders } from '../data/sources';
import { maskChannels, sealSource, unmaskChannels, unsealSource } from '../data/vault';
import type { EpgReply, EpgRequest } from '../workers/epg.worker';
import { settings } from './settings';

/**
 * The app's state: sources, their channels and guides, favourites — kept in IndexedDB so the app
 * opens on the last list and refreshes behind it, as the Android app does from its database.
 * The computer version's (web/src/state/store.ts), with the television's way to providers
 * (data/api.ts) and the fixes ADR-057 lists.
 */

export const sources = signal<Source[]>([]);
export const channelsBySource = signal<Record<string, Channel[]>>({});
export const guides = signal<Record<string, SourceGuide>>({});
export const favourites = signal<Set<string>>(new Set());
export const recents = signal<string[]>([]);
/** What is being refreshed right now, per source. */
export const refreshing = signal<Record<string, 'playlist' | 'guide'>>({});
export const guideProgress = signal<Record<string, string>>({});
export const ready = signal(false);
/** False when IndexedDB would not open: the app runs, but forgets everything when it closes. */
export const storageOk = signal(true);
/** The clock every "now" on screen reads, ticking on the half minute. */
export const now = signal(Date.now());

const STALE_MS = 12 * 3600_000;
const GUIDE_BEHIND_MS = 12 * 3600_000;
const GUIDE_AHEAD_MS = 48 * 3600_000;
/** A guide that has not finished by then is stopped: nothing may stay "refreshing" for ever. */
const GUIDE_LIMIT_MS = 15 * 60_000;
const RECENTS = 30;

const enabledSources = (role: Source['role']) =>
  sources.value.filter((s) => s.enabled && s.role === role).sort((a, b) => a.sortIndex - b.sortIndex);

export const tvChannels = computed<Channel[]>(() =>
  enabledSources('tv').flatMap((s) => channelsBySource.value[s.id] ?? []),
);

export const radioStations = computed<Channel[]>(() =>
  enabledSources('radio').flatMap((s) => (channelsBySource.value[s.id] ?? []).filter((c) => !isRussianStation(c.name, c.group))),
);

export const channelByKey = computed(() => {
  const map = new Map<string, Channel>();
  for (const list of Object.values(channelsBySource.value)) for (const c of list) map.set(c.key, c);
  return map;
});

export function programmesOf(channel: Channel): Programme[] | undefined {
  return guides.value[channel.sourceId]?.programmes[channel.key];
}

/** Reads a stored value; a storage that fails reads as nothing stored. */
async function read<T>(key: string): Promise<T | undefined> {
  try {
    return await kv.get<T>(key);
  } catch {
    storageOk.value = false;
    return undefined;
  }
}

/** Writes a value; a storage that fails is noted, never thrown into the caller. */
async function write(key: string, value: unknown): Promise<void> {
  try {
    await kv.set(key, value);
    storageOk.value = true;
  } catch {
    storageOk.value = false;
  }
}

async function remove(key: string): Promise<void> {
  try {
    await kv.del(key);
  } catch {
    storageOk.value = false;
  }
}

/* ---------- start-up ---------- */

export async function init() {
  setInterval(() => (now.value = Date.now()), 30_000);

  let stored = await Promise.all(((await read<Source[]>('sources')) ?? []).map(unsealSource));
  // Every install has the two built-in playlists (ADR-013, ADR-015); the user may switch them
  // off, and a switched-off one stays as it is.
  for (const b of BUILT_IN_SOURCES) if (!stored.some((s) => s.id === b.id)) stored = [...stored, { ...b }];
  const channels: Record<string, Channel[]> = {};
  const loadedGuides: Record<string, SourceGuide> = {};
  await Promise.all(
    stored.map(async (s) => {
      channels[s.id] = unmaskChannels((await read<Channel[]>(`channels:${s.id}`)) ?? [], s);
      const g = await read<SourceGuide>(`guide:${s.id}`);
      if (g) loadedGuides[s.id] = g;
    }),
  );
  const favs = (await read<string[]>('favourites')) ?? [];
  const rec = (await read<string[]>('recents')) ?? [];
  batch(() => {
    sources.value = stored;
    channelsBySource.value = channels;
    guides.value = loadedGuides;
    favourites.value = new Set(favs);
    recents.value = rec;
    ready.value = true;
  });
  await saveSources();
  void refreshStale();
}

/** Refreshes what is older than half a day, one source at a time. */
export async function refreshStale() {
  for (const s of [...sources.value].sort((a, b) => a.sortIndex - b.sortIndex)) {
    if (!s.enabled) continue;
    const fresh = s.lastRefreshUtc && Date.now() - s.lastRefreshUtc < STALE_MS && (channelsBySource.value[s.id]?.length ?? 0) > 0;
    if (!fresh) {
      await refreshSource(s.id);
    } else {
      const g = guides.value[s.id];
      if (s.resolvedEpgUrl && (!g || Date.now() - g.fetchedUtc > STALE_MS)) await refreshGuide(s.id);
    }
  }
}

export async function refreshAll() {
  for (const s of sources.value) if (s.enabled) await refreshSource(s.id);
}

function setRefreshing(id: string, what: 'playlist' | 'guide' | null) {
  const next = { ...refreshing.value };
  if (what) next[id] = what;
  else delete next[id];
  refreshing.value = next;
}

/** Writes the sources, their passwords sealed (data/vault.ts). */
async function saveSources(): Promise<void> {
  try {
    const sealed = await Promise.all(sources.value.map(sealSource));
    await write('sources', sealed);
  } catch {
    storageOk.value = false;
  }
}

function patchSource(id: string, patch: Partial<Source>) {
  sources.value = sources.value.map((s) => (s.id === id ? { ...s, ...patch } : s));
  void saveSources();
}

export async function refreshSource(id: string, withGuide = true) {
  const source = sources.value.find((s) => s.id === id);
  if (!source || refreshing.value[id]) return;
  setRefreshing(id, 'playlist');
  try {
    const loaded = await loadSource(source, settings.value.userAgent || undefined);
    channelsBySource.value = { ...channelsBySource.value, [id]: loaded.channels };
    await write(`channels:${id}`, maskChannels(loaded.channels, source));
    patchSource(id, {
      lastRefreshUtc: Date.now(),
      lastRefreshOk: true,
      lastRefreshMessage: loaded.message,
      channelCount: loaded.channels.length,
      resolvedEpgUrl: loaded.epgUrl,
    });
  } catch (err) {
    // The channels the user had stay: a failed refresh must not empty the list.
    patchSource(id, {
      lastRefreshUtc: Date.now(),
      lastRefreshOk: false,
      lastRefreshMessage: err instanceof SourceError ? err.message : 'הרענון נכשל',
    });
  } finally {
    setRefreshing(id, null);
  }
  if (withGuide) await refreshGuide(id);
}

/**
 * Starts the guide worker from its own script (vite.config.ts `epgWorker`). A packaged app cannot
 * start one from a file:// address, so it is a Blob, or a data: address where a Blob is refused.
 */
export function startGuideWorker(): Worker {
  try {
    const url = URL.createObjectURL(new Blob([workerSource], { type: 'text/javascript' }));
    try {
      return new Worker(url);
    } finally {
      // The worker has its script once it is constructed; the address is not needed after.
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    }
  } catch {
    return new Worker(`data:text/javascript;charset=utf-8,${encodeURIComponent(workerSource)}`);
  }
}

/**
 * Downloads a source's guide and parses it in the worker, part by part (data/api.ts streamText).
 *
 * Always ends, and always clears "refreshing" (ADR-057): in the first version a failed write of
 * the parsed guide threw past the cleanup, the source stayed "refreshing" until the app was
 * closed, and the download went on with nobody reading it. Now every way out (done, a worker
 * error, a failed download, the time limit) goes through one `finish`, which stops the download
 * and the worker.
 */
export function refreshGuide(id: string): Promise<void> {
  const source = sources.value.find((s) => s.id === id);
  const channels = channelsBySource.value[id] ?? [];
  if (!source?.resolvedEpgUrl || channels.length === 0 || refreshing.value[id]) return Promise.resolve();
  setRefreshing(id, 'guide');
  const t = Date.now();
  const download = new AbortController();
  /** Parts handed to the worker and not yet parsed, by number. */
  const parsing = new Map<number, () => void>();
  let worker: Worker | null = null;
  let over = false;
  let partNo = 0;

  return new Promise<void>((resolve) => {
    const finish = (message?: string) => {
      if (over) return;
      over = true;
      clearTimeout(limit);
      download.abort();
      for (const release of parsing.values()) release();
      parsing.clear();
      try {
        worker?.terminate();
      } catch {
        // already gone
      }
      setRefreshing(id, null);
      const p = { ...guideProgress.value };
      if (message) p[id] = message;
      else delete p[id];
      guideProgress.value = p;
      resolve();
    };
    const limit = setTimeout(() => finish('לוח השידורים לא נטען בזמן'), GUIDE_LIMIT_MS);

    try {
      worker = startGuideWorker();
    } catch {
      finish('לוח השידורים לא נטען (אין worker)');
      return;
    }
    worker.onmessage = (e: MessageEvent<EpgReply>) => {
      if (over) return;
      const m = e.data;
      if (m.type === 'consumed') {
        parsing.get(m.seq)?.();
        parsing.delete(m.seq);
      } else if (m.type === 'progress') {
        guideProgress.value = { ...guideProgress.value, [id]: `${m.programmes.toLocaleString('he-IL')} תוכניות` };
      } else if (m.type === 'done') {
        const guide: SourceGuide = { sourceId: id, fetchedUtc: Date.now(), programmes: m.programmes, matched: m.matched, total: m.total };
        guides.value = { ...guides.value, [id]: guide };
        const message = `לוח שידורים: ${m.matched.toLocaleString('he-IL')} מתוך ${m.total.toLocaleString('he-IL')} ערוצים`;
        void write(`guide:${id}`, guide).then(() => finish(message), () => finish(message));
      } else {
        finish(`לוח השידורים לא נטען (${m.code})`);
      }
    };
    worker.onerror = () => finish('לוח השידורים לא נטען');
    worker.postMessage({
      type: 'start',
      channels: channels.map((c) => ({ key: c.key, name: c.name, tvgId: c.tvgId })),
      from: t - GUIDE_BEHIND_MS,
      to: t + GUIDE_AHEAD_MS,
    } satisfies EpgRequest);

    streamText(source.resolvedEpgUrl!, sourceHeaders(source, settings.value.userAgent || undefined), (text) => {
      if (over || !worker) return;
      const seq = partNo++;
      // Resolved when the worker has parsed it: only then is the part acknowledged to the service.
      const parsed = new Promise<void>((release) => parsing.set(seq, release));
      worker.postMessage({ type: 'part', seq, text } satisfies EpgRequest);
      return parsed;
    }, download.signal)
      .then(() => {
        if (!over) worker?.postMessage({ type: 'end' } satisfies EpgRequest);
      })
      .catch((err: unknown) => {
        if (over) return;
        if (err instanceof HttpError && err.status === 404) finish('לוח השידורים לא נמצא (404)');
        else finish(`לוח השידורים לא נטען: ${describe(err)}`);
      });
  });
}

/* ---------- editing sources ---------- */

export async function addSources(added: Source[]) {
  sources.value = [...sources.value, ...added];
  await saveSources();
  for (const s of added) if (s.enabled) await refreshSource(s.id);
}

export async function saveSource(source: Source) {
  const exists = sources.value.some((s) => s.id === source.id);
  if (!exists) return addSources([source]);
  sources.value = sources.value.map((s) => (s.id === source.id ? source : s));
  await saveSources();
  if (source.enabled) await refreshSource(source.id);
}

export async function setSourceEnabled(id: string, enabled: boolean) {
  patchSource(id, { enabled });
  if (enabled && (channelsBySource.value[id]?.length ?? 0) === 0) await refreshSource(id);
}

export async function removeSource(id: string) {
  batch(() => {
    sources.value = sources.value.filter((s) => s.id !== id);
    const { [id]: _c, ...restChannels } = channelsBySource.value;
    channelsBySource.value = restChannels;
    const { [id]: _g, ...restGuides } = guides.value;
    guides.value = restGuides;
  });
  await Promise.all([saveSources(), remove(`channels:${id}`), remove(`guide:${id}`)]);
}

/* ---------- favourites and history ---------- */

export function isFavourite(key: string) {
  return favourites.value.has(key);
}

export function toggleFavourite(key: string) {
  const next = new Set(favourites.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  favourites.value = next;
  void write('favourites', [...next]);
}

export function markWatched(key: string) {
  recents.value = [key, ...recents.value.filter((k) => k !== key)].slice(0, RECENTS);
  void write('recents', recents.value);
}
