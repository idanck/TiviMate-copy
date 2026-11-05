import { computed, signal } from '@preact/signals';
import { fetchText } from '../data/api';
import { kv } from '../data/db';
import { acceptableSource, CHECK_INTERVAL_MS, MAX_SOURCES, mergeFlashes, parseFeed, type NewsDigest, type NewsSource } from '../data/news';
import { now } from './store';
import { settings, updateSettings } from './settings';

/**
 * The headlines News mode shows (ADR-053), as Android's `NewsRepository` keeps them: what each
 * source last said is stored, so the screen has headlines before the first fetch returns; every
 * source is read at most once in ten minutes, side by side, and one that fails keeps its last
 * headlines while the others update. Reading goes through Shidur's service (data/api.ts), as a
 * packaged app's requests must.
 */

export const digests = signal<Record<string, NewsDigest>>({});
let lastCheckUtc = 0;
let refreshing: Promise<void> | null = null;

void Promise.all([kv.get<Record<string, NewsDigest>>('news:digests'), kv.get<number>('news:checked')])
  .then(([stored, checked]) => {
    if (stored && !Object.keys(digests.value).length) digests.value = stored;
    if (checked) lastCheckUtc = checked;
  })
  .catch(() => {
    // no storage: the first refresh fills the screen
  });

/** Every enabled source's headlines as one list, newest first. */
export const flashes = computed(() => mergeFlashes(settings.value.newsSources, digests.value, now.value));

/** Reads every enabled source if it is due, or at once when [force]. One refresh at a time. */
export function refreshNews(force = false): Promise<void> {
  if (refreshing) return refreshing;
  const sources = settings.value.newsSources;
  const listed = new Set(sources.map((s) => s.url));
  // Sources removed in Settings take their headlines with them.
  const kept: Record<string, NewsDigest> = {};
  for (const [url, d] of Object.entries(digests.value)) if (listed.has(url)) kept[url] = d;
  if (Object.keys(kept).length !== Object.keys(digests.value).length) {
    digests.value = kept;
    void kv.set('news:digests', kept).catch(() => undefined);
  }
  const enabled = sources.filter((s) => s.enabled && acceptableSource(s.url));
  const age = Date.now() - lastCheckUtc;
  if (!enabled.length || (!force && lastCheckUtc > 0 && age >= 0 && age < CHECK_INTERVAL_MS)) return Promise.resolve();
  refreshing = Promise.all(
    enabled.map((s) =>
      fetchText(s.url)
        .then((text) => ({ url: s.url, feed: parseFeed(text) }))
        .catch(() => null),
    ),
  ).then((results) => {
    const t = Date.now();
    const next = { ...digests.value };
    for (const r of results) if (r) next[r.url] = { title: r.feed.title, items: r.feed.items, fetchedUtc: t };
    digests.value = next;
    lastCheckUtc = t;
    void kv.set('news:digests', next).catch(() => undefined);
    void kv.set('news:checked', t).catch(() => undefined);
  }).finally(() => {
    refreshing = null;
  });
  return refreshing;
}

export type AddResult = 'added' | 'refused' | 'unreadable';

/** Adds a source the viewer typed, once it has been read as a feed. */
export async function addNewsSource(raw: string): Promise<AddResult> {
  const url = raw.trim();
  const list = settings.value.newsSources;
  if (!acceptableSource(url) || list.length >= MAX_SOURCES || list.some((s) => s.url.toLowerCase() === url.toLowerCase())) return 'refused';
  try {
    const feed = parseFeed(await fetchText(url));
    digests.value = { ...digests.value, [url]: { title: feed.title, items: feed.items, fetchedUtc: Date.now() } };
  } catch {
    return 'unreadable';
  }
  updateSettings({ newsSources: [...settings.value.newsSources, { url, enabled: true }] });
  return 'added';
}

/** Switches a source on or off; a suggested one not yet listed is added. */
export function setNewsSource(url: string, enabled: boolean) {
  const list = settings.value.newsSources;
  const next: NewsSource[] = list.some((s) => s.url === url)
    ? list.map((s) => (s.url === url ? { ...s, enabled } : s))
    : list.length < MAX_SOURCES && enabled
      ? [...list, { url, enabled: true }]
      : list;
  updateSettings({ newsSources: next });
  if (enabled) void refreshNews(true);
}

export function removeNewsSource(url: string) {
  updateSettings({ newsSources: settings.value.newsSources.filter((s) => s.url !== url) });
  void refreshNews(false);
}
