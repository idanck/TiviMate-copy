import { onTelevision, SERVICE_URI, subscribe } from './luna';

/**
 * How the app reaches providers on the television (ADR-052). The page cannot: it runs from
 * file:// under CORS, and providers send no CORS headers. Shidur's JS service (service/) reads
 * for it, over the Luna bus, and sends text back in parts. Video is not read here at all: the
 * television's own player opens a stream's address itself (player/engine.ts).
 *
 * Off the television these fall back to the browser's fetch, which works only with providers
 * that do send CORS headers: enough for developing and for the tests.
 */

/** Headers for one request: a source's or a channel's, with its user agent. */
export type RequestHeaders = Record<string, string>;

export function requestHeaders(userAgent: string | undefined, headers: Record<string, string> | undefined): RequestHeaders {
  const all: RequestHeaders = { ...(headers ?? {}) };
  if (userAgent) all['User-Agent'] = userAgent;
  return all;
}

/**
 * A logo. An image needs no CORS and a packaged app has no mixed-content rule, so it loads
 * straight from where it is.
 */
export function imageUrl(url: string | undefined): string | undefined {
  if (!url) return undefined;
  return /^(https?:|data:)/i.test(url) ? url : undefined;
}

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(`${code} (${status})`);
  }
}

export interface TextInfo {
  status: number;
  contentType: string;
  finalUrl: string;
}

/** Reads a text resource whole. */
export async function fetchText(url: string, h: RequestHeaders = {}, signal?: AbortSignal): Promise<string> {
  const parts: string[] = [];
  await streamText(url, h, (part) => parts.push(part), signal);
  return parts.join('');
}

/**
 * Reads a text resource part by part, in order, for a guide too large to hold twice. Rejects with
 * [HttpError] for a provider's error status or a failed connection.
 */
export function streamText(url: string, h: RequestHeaders, onPart: (part: string) => void, signal?: AbortSignal): Promise<TextInfo> {
  if (!onTelevision) return streamDirect(url, h, onPart, signal);
  return new Promise<TextInfo>((resolve, reject) => {
    // Parts are numbered: the bus keeps them in order, but a part out of place would corrupt the
    // text silently, so they are put back in order rather than trusted.
    const waiting = new Map<number, string>();
    let next = 0;
    let expected = -1;
    let info: TextInfo | null = null;
    let finished = false;
    const settle = (err?: unknown) => {
      if (finished) return;
      finished = true;
      stop();
      signal?.removeEventListener('abort', onAbort);
      if (err) reject(err);
      else resolve(info!);
    };
    const drain = () => {
      while (waiting.has(next)) {
        onPart(waiting.get(next)!);
        waiting.delete(next);
        next++;
      }
      if (info && next >= expected) {
        if (info.status >= 400) settle(new HttpError(info.status, 'http'));
        else settle();
      }
    };
    const stop = subscribe(SERVICE_URI, 'fetch', { url, headers: h }, (reply) => {
      if (reply.returnValue === false) {
        settle(new HttpError(Number(reply.status) || 0, String(reply.errorText || 'failed')));
        return;
      }
      if (typeof reply.part === 'string') waiting.set(Number(reply.seq), reply.part);
      if (reply.done) {
        expected = Number(reply.parts) || 0;
        info = { status: Number(reply.status) || 0, contentType: String(reply.contentType ?? ''), finalUrl: String(reply.finalUrl ?? url) };
      }
      drain();
    });
    const onAbort = () => settle(new DOMException('Aborted', 'AbortError'));
    signal?.addEventListener('abort', onAbort);
  });
}

async function streamDirect(url: string, h: RequestHeaders, onPart: (part: string) => void, signal?: AbortSignal): Promise<TextInfo> {
  let res: Response;
  try {
    res = await fetch(url, { headers: h, signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new HttpError(0, 'unreachable');
  }
  const info = { status: res.status, contentType: res.headers.get('content-type') ?? '', finalUrl: res.url || url };
  if (!res.ok) throw new HttpError(res.status, 'http');
  onPart(await res.text());
  return info;
}

export interface ProbeInfo {
  status: number;
  kind: 'hls' | 'ts' | 'flv' | 'native' | 'unknown';
  contentType: string;
  /** The address after redirects: what the television's player is given. */
  finalUrl: string;
}

/** What a stream is, from its first bytes (service/net.js `probe`). */
export function probeStream(url: string, h: RequestHeaders): Promise<ProbeInfo> {
  if (!onTelevision) return Promise.resolve({ status: 0, kind: 'unknown', contentType: '', finalUrl: url });
  return new Promise<ProbeInfo>((resolve, reject) => {
    const stop = subscribe(SERVICE_URI, 'probe', { url, headers: h }, (reply) => {
      stop();
      if (reply.returnValue === false) return reject(new HttpError(Number(reply.status) || 0, String(reply.errorText || 'failed')));
      resolve({
        status: Number(reply.status) || 0,
        kind: (reply.kind as ProbeInfo['kind']) ?? 'unknown',
        contentType: String(reply.contentType ?? ''),
        finalUrl: String(reply.finalUrl ?? url),
      });
    });
  });
}
