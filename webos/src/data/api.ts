import { onTelevision, send, SERVICE_URI, subscribe } from './luna';

/**
 * How the app reaches providers on the television (ADR-057). The page cannot: it runs from
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

/**
 * A failed request. `code` is the service's (service/net.js): `http` for a provider's error
 * status, else `dns`, `refused`, `reset`, `timeout`, `tls-cert`, `tls`, `unreachable`,
 * `bad-url`, `redirects`, `too-large`, `gzip`, `stalled`. `detail` is what Node itself said.
 */
export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  constructor(status: number, code: string, detail = '') {
    super(`${code} (${status})`);
    this.status = status;
    this.code = code;
    this.detail = detail;
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
  await streamText(url, h, (part) => {
    parts.push(part);
  }, signal);
  return parts.join('');
}

let requests = 0;

/**
 * Reads a text resource part by part, in order, for a guide too large to hold twice. Rejects with
 * [HttpError] for a provider's error status or a failed connection.
 *
 * `onPart` may return a promise: the next part is not handed on until it settles, and only then
 * is the part acknowledged to the service, which holds the provider back while too many parts are
 * unacknowledged (service/index.js). That is the whole way from the provider to the guide worker
 * held to the pace of the slowest step.
 */
export function streamText(
  url: string,
  h: RequestHeaders,
  onPart: (part: string) => void | Promise<void>,
  signal?: AbortSignal,
): Promise<TextInfo> {
  if (!onTelevision()) return streamDirect(url, h, onPart, signal);
  const id = `r${Date.now().toString(36)}-${++requests}`;
  return new Promise<TextInfo>((resolve, reject) => {
    // Parts are numbered: the bus keeps them in order, but a part out of place would corrupt the
    // text silently, so they are put back in order rather than trusted.
    const waiting = new Map<number, string>();
    let next = 0;
    let expected = -1;
    let info: TextInfo | null = null;
    let finished = false;
    let chain: Promise<void> = Promise.resolve();
    let handed = 0;
    let stop: (() => void) | null = null;
    const settle = (err?: unknown) => {
      if (finished) return;
      finished = true;
      stop?.();
      signal?.removeEventListener('abort', onAbort);
      if (err) reject(err);
      else resolve(info!);
    };
    const maybeDone = () => {
      if (!info || handed < expected) return;
      if (info.status >= 400) settle(new HttpError(info.status, 'http'));
      else settle();
    };
    const drain = () => {
      while (waiting.has(next)) {
        const seq = next;
        const part = waiting.get(seq)!;
        waiting.delete(seq);
        next++;
        chain = chain
          .then(() => (finished ? undefined : onPart(part)))
          .then(() => {
            if (finished) return;
            handed = seq + 1;
            send(SERVICE_URI, 'ack', { id, seq });
            maybeDone();
          })
          .catch((err: unknown) => settle(err));
      }
      chain = chain.then(maybeDone);
    };
    stop = subscribe(SERVICE_URI, 'fetch', { id, url, headers: h }, (reply) => {
      if (reply.returnValue === false) {
        settle(new HttpError(Number(reply.status) || 0, String(reply.errorText || 'failed'), String(reply.detail || '')));
        return;
      }
      if (typeof reply.part === 'string') waiting.set(Number(reply.seq), reply.part);
      if (reply.done) {
        expected = Number(reply.parts) || 0;
        info = { status: Number(reply.status) || 0, contentType: String(reply.contentType ?? ''), finalUrl: String(reply.finalUrl ?? url) };
      }
      drain();
    });
    if (finished) stop();
    const onAbort = () => settle(new DOMException('Aborted', 'AbortError'));
    if (signal?.aborted) onAbort();
    else signal?.addEventListener('abort', onAbort);
  });
}

async function streamDirect(url: string, h: RequestHeaders, onPart: (part: string) => void | Promise<void>, signal?: AbortSignal): Promise<TextInfo> {
  let res: Response;
  try {
    res = await fetch(url, { headers: h, signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new HttpError(0, 'unreachable');
  }
  const info = { status: res.status, contentType: res.headers.get('content-type') ?? '', finalUrl: res.url || url };
  if (!res.ok) throw new HttpError(res.status, 'http');
  await onPart(await res.text());
  return info;
}

export interface ProbeInfo {
  status: number;
  kind: 'hls' | 'ts' | 'flv' | 'native' | 'unknown';
  contentType: string;
  /** The address after redirects: what the television's player is given. */
  finalUrl: string;
}

/**
 * How long a probe may take before the player gives up on it. The service has its own timeout for
 * a silent connection, but not for a service that never answers at all (stopped by webOS, or not
 * installed): the first version waited for ever there, with the spinner up.
 */
export const PROBE_TIMEOUT_MS = 15_000;

/** What a stream is, from its first bytes (service/net.js `probe`). */
export function probeStream(url: string, h: RequestHeaders, timeoutMs = PROBE_TIMEOUT_MS): Promise<ProbeInfo> {
  if (!onTelevision()) return Promise.resolve({ status: 0, kind: 'unknown', contentType: '', finalUrl: url });
  return new Promise<ProbeInfo>((resolve, reject) => {
    let over = false;
    let stop: (() => void) | null = null;
    const timer = setTimeout(() => {
      if (over) return;
      over = true;
      stop?.();
      reject(new HttpError(0, 'timeout'));
    }, timeoutMs);
    stop = subscribe(SERVICE_URI, 'probe', { url, headers: h }, (reply) => {
      if (over) return;
      over = true;
      clearTimeout(timer);
      stop?.();
      if (reply.returnValue === false) {
        reject(new HttpError(Number(reply.status) || 0, String(reply.errorText || 'failed'), String(reply.detail || '')));
        return;
      }
      resolve({
        status: Number(reply.status) || 0,
        kind: (reply.kind as ProbeInfo['kind']) ?? 'unknown',
        contentType: String(reply.contentType ?? ''),
        finalUrl: String(reply.finalUrl ?? url),
      });
    });
    if (over) stop();
  });
}
