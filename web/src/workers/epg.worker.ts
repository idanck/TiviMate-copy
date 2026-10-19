/// <reference lib="webworker" />
import { matchChannels, type Matchable } from '../data/matcher';
import type { EpgChannel, Programme } from '../data/model';
import { XmltvParser } from '../data/xmltv';

/**
 * Reads one guide off the main thread: streams the XMLTV through the proxy, binds its channels to
 * the source's (data/matcher.ts), and keeps only the programmes of bound channels inside the
 * window. A provider guide is hundreds of megabytes of text for thousands of channels; what comes
 * back is the part this player will draw.
 */

export interface EpgRequest {
  url: string;
  channels: Matchable[];
  from: number;
  to: number;
}

export type EpgReply =
  | { type: 'progress'; channels: number; programmes: number }
  | { type: 'done'; programmes: Record<string, Programme[]>; matched: number; total: number; epgChannels: number; read: number }
  | { type: 'error'; status: number; code: string };

const DESCRIPTION_LIMIT = 600;

const post = (m: EpgReply) => (self as unknown as DedicatedWorkerGlobalScope).postMessage(m);

self.onmessage = async (event: MessageEvent<EpgRequest>) => {
  const { url, channels, from, to } = event.data;
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    if (!res.ok || !res.body) {
      let code = 'http';
      try {
        code = (await res.json()).error ?? code;
      } catch {
        // not the proxy's own JSON
      }
      return post({ type: 'error', status: res.status, code });
    }

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

    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let lastReport = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(value);
      if (parser.programmes - lastReport >= 5000) {
        lastReport = parser.programmes;
        post({ type: 'progress', channels: parser.channels, programmes: parser.programmes });
      }
    }
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
  } catch (err) {
    post({ type: 'error', status: 0, code: err instanceof Error ? err.message : 'failed' });
  }
};
