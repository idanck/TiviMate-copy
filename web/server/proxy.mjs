// @ts-check
import { spawn, spawnSync } from 'node:child_process';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { createGunzip } from 'node:zlib';
import { assertReachable } from './guard.mjs';
import { redactUrl } from './redact.mjs';

/**
 * The proxy between the browser and IPTV providers.
 *
 * A browser cannot talk to a provider directly: providers send no CORS headers, most streams are
 * plain HTTP (mixed content on an HTTPS page), a page cannot set User-Agent or Referer, and an
 * Xtream panel's live streams are raw MPEG-TS behind a redirect. Everything a playlist names
 * therefore goes through here: text (playlists, guides, panel JSON) through /api/fetch, media
 * through /api/stream — which rewrites HLS playlists so every segment, key and variant comes back
 * through the proxy too — and a stream the browser cannot decode through /api/remux, when ffmpeg
 * is installed.
 *
 * Nothing is stored and nothing is logged but failures, with credentials redacted.
 */

/** The Android app's own default (data/net/Http.kt): providers filter on it (ADR-018). */
export const DEFAULT_USER_AGENT = 'TiviCopy/1.0 (Android)';

const REDIRECTS = [301, 302, 303, 307, 308];
const MAX_HOPS = 5;
const PLAYLIST_LIMIT = 8 * 1024 * 1024;

/** Request headers a playlist may not set: they belong to the connection, not to the request. */
const NOT_FORWARDED = new Set([
  'host', 'connection', 'content-length', 'transfer-encoding', 'upgrade', 'te', 'trailer',
  'keep-alive', 'expect', 'proxy-authorization', 'proxy-connection', 'accept-encoding', 'range',
]);

/**
 * Per-request headers travel as `h=<base64url JSON>`, so that a rewritten HLS playlist can hand
 * them on to every segment URL without the client knowing what is inside it.
 *
 * @param {string | null} h
 * @returns {Record<string, string>}
 */
export function decodeHeaders(h) {
  if (!h) return {};
  try {
    const parsed = JSON.parse(Buffer.from(h, 'base64url').toString('utf8'));
    /** @type {Record<string, string>} */
    const out = {};
    for (const [k, v] of Object.entries(parsed ?? {})) {
      const name = k.trim().toLowerCase();
      if (typeof v === 'string' && v && /^[a-z0-9-]+$/.test(name) && !NOT_FORWARDED.has(name)) out[name] = v;
    }
    return out;
  } catch {
    return {};
  }
}

/**
 * Rewrites every URI in an HLS playlist to come back through /api/stream, resolved against the
 * playlist's own final address (after redirects — panels commonly redirect to a tokenised host).
 *
 * @param {string} text
 * @param {string} baseUrl
 * @param {string | null} h the encoded headers, carried on to every rewritten URI
 */
export function rewritePlaylist(text, baseUrl, h) {
  /** @param {string} uri */
  const through = (uri) => {
    let absolute;
    try {
      absolute = new URL(uri, baseUrl);
    } catch {
      return uri;
    }
    if (absolute.protocol !== 'http:' && absolute.protocol !== 'https:') return uri;
    return `/api/stream?u=${encodeURIComponent(absolute.href)}${h ? `&h=${h}` : ''}`;
  };
  return text
    .split(/\r?\n/)
    .map((line) => {
      const t = line.trim();
      if (!t) return '';
      if (t.startsWith('#')) return t.replace(/URI="([^"]*)"/g, (_m, uri) => `URI="${through(uri)}"`);
      return through(t);
    })
    .join('\n');
}

/**
 * What kind of stream the first bytes and the content type say this is, so the client can pick
 * hls.js, mpegts.js or the browser's own decoder for a URL whose extension says nothing.
 *
 * @param {string} contentType
 * @param {Uint8Array} head
 * @returns {'hls' | 'ts' | 'flv' | 'audio' | 'mp4' | 'unknown'}
 */
export function sniffKind(contentType, head) {
  const ct = contentType.toLowerCase();
  const text = Buffer.from(head.subarray(0, 16)).toString('latin1').trimStart();
  if (ct.includes('mpegurl') || text.startsWith('#EXTM3U')) return 'hls';
  if (ct.includes('mp2t') || (head[0] === 0x47 && (head.length < 189 || head[188] === 0x47))) return 'ts';
  if (ct.includes('flv') || text.startsWith('FLV')) return 'flv';
  if (ct.startsWith('audio/') || ct.includes('ogg') || text.startsWith('ID3') || text.startsWith('OggS') || text.startsWith('fLaC') ||
      (head[0] === 0xff && (head[1] & 0xe0) === 0xe0)) return 'audio';
  if (ct.includes('mp4') || Buffer.from(head.subarray(4, 8)).toString('latin1') === 'ftyp') return 'mp4';
  return 'unknown';
}

export class Proxy {
  /**
   * @param {{ allowPrivate: boolean, userAgent?: string, connectTimeoutMs?: number }} options
   */
  constructor({ allowPrivate, userAgent, connectTimeoutMs }) {
    this.allowPrivate = allowPrivate;
    this.userAgent = userAgent || DEFAULT_USER_AGENT;
    this.connectTimeoutMs = connectTimeoutMs ?? 20_000;
    this.ffmpeg = spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' }).status === 0;
  }

  /**
   * Fetches [target], following redirects by hand so each hop passes the address guard.
   *
   * @param {URL} target
   * @param {{ headers: Record<string, string>, signal: AbortSignal, media: boolean, range?: string }} options
   */
  async upstream(target, { headers, signal, media, range }) {
    let current = target;
    for (let hop = 0; hop <= MAX_HOPS; hop++) {
      await assertReachable(current, { allowPrivate: this.allowPrivate });
      /** @type {Record<string, string>} */
      const sent = { accept: '*/*', 'user-agent': this.userAgent, ...headers };
      // Media is passed through byte for byte, ranges and lengths intact, so it must not be
      // compressed on the way; text may be, and fetch decodes it.
      if (media) sent['accept-encoding'] = 'identity';
      if (range) sent.range = range;
      const res = await fetch(current, { headers: sent, redirect: 'manual', signal });
      const location = res.headers.get('location');
      if (REDIRECTS.includes(res.status) && location) {
        await res.body?.cancel().catch(() => {});
        current = new URL(location, current);
        continue;
      }
      return { res, finalUrl: current };
    }
    throw Object.assign(new Error('too many redirects'), { code: 'redirects' });
  }

  /**
   * Common front half of every route: parse the target, tie the upstream request to the client
   * connection, and give up on an upstream that has not answered within the connect timeout.
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URLSearchParams} params
   * @param {{ media: boolean, range?: string }} options
   */
  async open(req, res, params, { media, range }) {
    const target = parseTarget(params.get('u'));
    const h = params.get('h');
    const controller = new AbortController();
    res.on('close', () => controller.abort());
    req.on('aborted', () => controller.abort());
    const timer = setTimeout(() => controller.abort(new Error('timeout')), this.connectTimeoutMs);
    try {
      const up = await this.upstream(target, { headers: decodeHeaders(h), signal: controller.signal, media, range });
      return { ...up, h, target, controller };
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * GET /api/fetch?u=&h= — a playlist, a guide or panel JSON, decompressed when it is gzip
   * whether or not the server said so (a `.xml.gz` guide usually does not).
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URLSearchParams} params
   */
  async fetchText(req, res, params) {
    const { res: up } = await this.open(req, res, params, { media: false });
    res.statusCode = up.status;
    res.setHeader('content-type', up.headers.get('content-type') || 'text/plain; charset=utf-8');
    res.setHeader('cache-control', 'no-store');
    if (!up.body) return void res.end();
    const reader = up.body.getReader();
    const first = await reader.read();
    const head = first.done ? new Uint8Array() : first.value;
    const body = Readable.from(chunks(reader, head));
    if (head[0] === 0x1f && head[1] === 0x8b) {
      res.setHeader('content-type', 'application/xml; charset=utf-8');
      await pipeline(body, createGunzip(), res);
    } else {
      await pipeline(body, res);
    }
  }

  /**
   * GET /api/stream?u=&h= — media and images. HLS playlists come back rewritten; everything else
   * is piped with its range headers so seeking and progressive audio work.
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URLSearchParams} params
   */
  async stream(req, res, params) {
    const range = typeof req.headers.range === 'string' ? req.headers.range : undefined;
    const { res: up, finalUrl, h } = await this.open(req, res, params, { media: true, range });
    const contentType = up.headers.get('content-type') || '';
    const reader = up.body?.getReader();
    if (!reader) {
      res.statusCode = up.status;
      return void res.end();
    }

    const ambiguous = !contentType || /^(text\/|application\/(octet-stream|x-mpegurl|vnd\.apple\.mpegurl)|audio\/(x-)?mpegurl)/i.test(contentType);
    const looksLikePlaylist = /mpegurl/i.test(contentType) || /\.m3u8?$/i.test(finalUrl.pathname);
    if (up.ok && (ambiguous || looksLikePlaylist)) {
      const first = await reader.read();
      const head = first.done ? new Uint8Array() : first.value;
      if (Buffer.from(head.subarray(0, 32)).toString('utf8').replace(/^﻿/, '').trimStart().startsWith('#EXTM3U')) {
        const text = await readAll(reader, head, PLAYLIST_LIMIT);
        res.statusCode = 200;
        res.setHeader('content-type', 'application/vnd.apple.mpegurl');
        res.setHeader('cache-control', 'no-store');
        return void res.end(rewritePlaylist(text, finalUrl.href, h));
      }
      copyMediaHeaders(up, res);
      return void (await pipeline(Readable.from(chunks(reader, head)), res));
    }

    copyMediaHeaders(up, res);
    await pipeline(Readable.from(chunks(reader, null)), res);
  }

  /**
   * GET /api/probe?u=&h= — what kind of stream this is, from its first bytes. The upstream
   * connection is closed straight after, before the player opens its own: a provider that allows
   * one connection at a time sees them one after the other, not together.
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URLSearchParams} params
   */
  async probe(req, res, params) {
    const { res: up, controller } = await this.open(req, res, params, { media: true });
    const contentType = up.headers.get('content-type') || '';
    let head = new Uint8Array();
    if (up.ok && up.body) {
      const reader = up.body.getReader();
      const first = await reader.read();
      if (!first.done) head = first.value;
    }
    controller.abort();
    sendJson(res, 200, { status: up.status, contentType, kind: up.ok ? sniffKind(contentType, head) : 'unknown' });
  }

  /**
   * GET /api/remux?u=&h= — the stream with its video copied and its audio re-encoded to AAC, as
   * MPEG-TS. For channels whose audio a browser cannot decode (AC-3 above all, which Chrome's
   * media pipeline lacks); costs one ffmpeg process per viewer and next to no CPU for the video.
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URLSearchParams} params
   */
  async remux(req, res, params) {
    if (!this.ffmpeg) return sendJson(res, 501, { error: 'no-ffmpeg' });
    const target = parseTarget(params.get('u'));
    await assertReachable(target, { allowPrivate: this.allowPrivate });
    const headers = decodeHeaders(params.get('h'));
    const ua = headers['user-agent'] || this.userAgent;
    delete headers['user-agent'];
    const extra = Object.entries(headers).map(([k, v]) => `${k}: ${v}\r\n`).join('');
    const args = [
      '-hide_banner', '-loglevel', 'error', '-nostdin',
      '-user_agent', ua,
      ...(extra ? ['-headers', extra] : []),
      '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '4',
      '-i', target.href,
      '-map', '0:v:0?', '-map', '0:a:0?',
      '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-ac', '2',
      '-f', 'mpegts', 'pipe:1',
    ];
    const child = spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let errors = '';
    child.stderr.on('data', (d) => { if (errors.length < 2000) errors += d; });
    const kill = () => { if (child.exitCode === null) child.kill('SIGKILL'); };
    res.on('close', kill);
    req.on('aborted', kill);
    let started = false;
    child.stdout.once('data', () => {
      started = true;
      res.statusCode = 200;
      res.setHeader('content-type', 'video/mp2t');
      res.setHeader('cache-control', 'no-store');
    });
    child.on('close', (code) => {
      if (!started && !res.headersSent) {
        console.warn(`remux failed (${code}) for ${redactUrl(target.href)}: ${redactUrl(errors.trim())}`);
        sendJson(res, 502, { error: 'remux-failed' });
      }
    });
    child.stdout.pipe(res);
  }
}

/** @param {string | null} raw */
function parseTarget(raw) {
  if (!raw) throw Object.assign(new Error('missing u'), { code: 'bad-request' });
  try {
    return new URL(raw);
  } catch {
    throw Object.assign(new Error('bad url'), { code: 'bad-request' });
  }
}

/**
 * @param {ReadableStreamDefaultReader<Uint8Array>} reader
 * @param {Uint8Array | null} first
 */
async function* chunks(reader, first) {
  if (first && first.length) yield first;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return;
    yield value;
  }
}

/**
 * @param {ReadableStreamDefaultReader<Uint8Array>} reader
 * @param {Uint8Array} first
 * @param {number} limit
 */
async function readAll(reader, first, limit) {
  const parts = [first];
  let size = first.length;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > limit) {
      await reader.cancel();
      throw Object.assign(new Error('playlist too large'), { code: 'too-large' });
    }
    parts.push(value);
  }
  return Buffer.concat(parts).toString('utf8').replace(/^﻿/, '');
}

/**
 * @param {Response} up
 * @param {import('node:http').ServerResponse} res
 */
function copyMediaHeaders(up, res) {
  res.statusCode = up.status;
  for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag']) {
    const v = up.headers.get(name);
    if (v) res.setHeader(name, v);
  }
  res.setHeader('cache-control', up.headers.get('cache-control') || 'no-store');
}

/**
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} body
 */
export function sendJson(res, status, body) {
  if (res.headersSent) return void res.end();
  res.statusCode = status;
  res.setHeader('content-type', 'application/json; charset=utf-8');
  res.setHeader('cache-control', 'no-store');
  res.end(JSON.stringify(body));
}
