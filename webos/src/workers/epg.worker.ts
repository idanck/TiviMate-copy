/// <reference lib="webworker" />
import { matchChannels, type Matchable } from '../data/matcher';
import type { EpgChannel, Programme } from '../data/model';
import { XmltvParser } from '../data/xmltv';

/**
 * Reads one guide off the main thread: the XMLTV arrives in parts from the service (the page
 * forwards each one as it comes, data/api.ts `streamText`), its channels are bound to the
 * source's (data/matcher.ts), and only the programmes of bound channels inside the window are
 * kept. A provider guide is hundreds of megabytes of text for thousands of channels; what comes
 * back is the part this player will draw.
 *
 * Every part is answered with `consumed` once parsed, and the page acknowledges it to the service
 * only then, so the download goes no faster than this worker reads (ADR-057).
 *
 * The worker cannot fetch for itself on the television: the Luna bus is the page's alone. Its
 * script starts with public/polyfills.js (vite.config.ts `epgWorker`).
 */

export type EpgRequest =
  | { type: 'start'; channels: Matchable[]; from: number; to: number }
  | { type: 'part'; seq: number; text: string }
  | { type: 'end' };

export type EpgReply =
  | { type: 'progress'; channels: number; programmes: number }
  | { type: 'consumed'; seq: number }
  | { type: 'done'; programmes: Record<string, Programme[]>; matched: number; total: number; epgChannels: number; read: number }
  | { type: 'error'; code: string };

const DESCRIPTION_LIMIT = 600;

const post = (m: EpgReply) => (self as unknown as DedicatedWorkerGlobalScope).postMessage(m);

let run: {
  parser: XmltvParser;
  finish: () => void;
} | null = null;
let lastReport = 0;

function begin(channels: Matchable[], from: number, to: number) {
  const epgChannels: EpgChannel[] = [];
  const byEpg = new Map<string, Programme[]>();
  let bound: Map<string, { epgId: string | null }> | null = null;
  // Guides list every <channel> before the first <programme>, so the binding is worked out
  // once, there, and decides which programmes are worth keeping.
  const bind = () => {
    bound = matchChannels(channels, epgChannels);
    for (const r of bound.values()) if (r.epgId && !byEpg.has(r.epgId)) byEpg.set(r.epgId, []);
  };

  const parser = new XmltvParser(
    (c) => epgChannels.push(c),
    (p) => {
      if (!bound) bind();
      if (p.end < from || p.start > to) return;
      const list = byEpg.get(p.channel);
      if (!list) return;
      list.push({
        start: p.start,
        end: p.end,
        title: p.title,
        subtitle: p.subtitle,
        description: p.description && p.description.length > DESCRIPTION_LIMIT ? `${p.description.slice(0, DESCRIPTION_LIMIT)}…` : p.description,
        category: p.category,
        season: p.season,
        episode: p.episode,
      });
    },
  );

  const finish = () => {
    parser.end();
    if (!bound) bind();
    const programmes: Record<string, Programme[]> = {};
    let matched = 0;
    for (const [key, r] of bound!) {
      if (!r.epgId) continue;
      matched++;
      const list = byEpg.get(r.epgId)!;
      list.sort((a, b) => a.start - b.start);
      programmes[key] = list;
    }
    post({ type: 'done', programmes, matched, total: channels.length, epgChannels: epgChannels.length, read: parser.programmes });
  };

  run = { parser, finish };
  lastReport = 0;
}

self.onmessage = (event: MessageEvent<EpgRequest>) => {
  const m = event.data;
  try {
    if (m.type === 'start') {
      begin(m.channels, m.from, m.to);
    } else if (m.type === 'part') {
      if (!run) return;
      run.parser.push(m.text);
      post({ type: 'consumed', seq: m.seq });
      if (run.parser.programmes - lastReport >= 5000) {
        lastReport = run.parser.programmes;
        post({ type: 'progress', channels: run.parser.channels, programmes: run.parser.programmes });
      }
    } else if (run) {
      run.finish();
      run = null;
    }
  } catch (err) {
    run = null;
    post({ type: 'error', code: err instanceof Error ? err.message : 'failed' });
  }
};
