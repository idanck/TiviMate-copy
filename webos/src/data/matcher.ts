import type { EpgChannel } from './model';
import { canonical, prepared, similarityPrepared } from './normalize';

/** How a channel came to be bound to a guide entry. */
export type MatchKind = 'EXACT_ID' | 'NORMALIZED_NAME' | 'FUZZY' | 'NONE';

export interface Matchable {
  key: string;
  name: string;
  tvgId?: string;
}

/**
 * Binds playlist channels to XMLTV entries — the Android app's `data/epg/EpgMatcher.kt`. The
 * stages run in order and stop at the first hit, so a correct `tvg-id` is never overruled by a
 * lucky fuzzy score: exact tvg-id, then normalised name against every display-name (and the id
 * read as a name), then a fuzzy score of at least [fuzzyThreshold].
 */
export function matchChannels(
  channels: Matchable[],
  epgChannels: EpgChannel[],
  fuzzyThreshold = 0.78,
): Map<string, { epgId: string | null; kind: MatchKind }> {
  const byId = new Map<string, string>();
  const byName = new Map<string, string>();
  for (const e of epgChannels) {
    byId.set(e.id.toLowerCase(), e.id);
    for (const n of e.displayNames) {
      const key = canonical(n);
      // The first guide entry wins, so a later duplicate name cannot steal the binding.
      if (key && !byName.has(key)) byName.set(key, e.id);
    }
    const dot = e.id.lastIndexOf('.');
    const idAsName = canonical(dot > 0 ? e.id.slice(0, dot) : e.id);
    if (idAsName && !byName.has(idAsName)) byName.set(idAsName, e.id);
  }

  const out = new Map<string, { epgId: string | null; kind: MatchKind }>();
  const unmatched: Matchable[] = [];
  for (const ch of channels) {
    const tvg = ch.tvgId?.trim();
    const exact = tvg ? byId.get(tvg.toLowerCase()) : undefined;
    if (exact) {
      out.set(ch.key, { epgId: exact, kind: 'EXACT_ID' });
      continue;
    }
    const named = [ch.name, ch.tvgId].map((n) => (n ? byName.get(canonical(n)) : undefined)).find(Boolean);
    if (named) {
      out.set(ch.key, { epgId: named, kind: 'NORMALIZED_NAME' });
      continue;
    }
    out.set(ch.key, { epgId: null, kind: 'NONE' });
    unmatched.push(ch);
  }

  // The fuzzy stage is quadratic. A provider's full list against its full guide can mean
  // thousands by thousands; past a budget it costs the browser more than the few bindings it adds.
  const candidates = epgChannels.flatMap((e) =>
    (e.displayNames.length ? e.displayNames : [e.id]).map((n) => ({ id: e.id, ...prepared(n) })).filter((c) => c.canonical),
  );
  if (unmatched.length * candidates.length > FUZZY_BUDGET) return out;
  for (const ch of unmatched) {
    const probe = prepared(ch.name);
    if (!probe.canonical) continue;
    let best: string | null = null;
    let bestScore = 0;
    for (const c of candidates) {
      const score = similarityPrepared(probe, c);
      if (score > bestScore) {
        bestScore = score;
        best = c.id;
      }
    }
    if (best && bestScore >= fuzzyThreshold) out.set(ch.key, { epgId: best, kind: 'FUZZY' });
  }
  return out;
}

const FUZZY_BUDGET = 3_000_000;
