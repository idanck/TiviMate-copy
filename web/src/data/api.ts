/**
 * Addresses on Shidur's own server (web/server). Every provider URL goes through it: a browser
 * cannot reach providers directly (no CORS, plain HTTP, headers a page may not set).
 */

/** A source's or channel's request headers, as the proxy reads them back (`h=<base64url JSON>`). */
export function encodeHeaders(userAgent: string | undefined, headers: Record<string, string> | undefined): string {
  const all: Record<string, string> = { ...(headers ?? {}) };
  if (userAgent) all['User-Agent'] = userAgent;
  if (Object.keys(all).length === 0) return '';
  const bytes = new TextEncoder().encode(JSON.stringify(all));
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function route(path: string, url: string, h: string): string {
  return `/api/${path}?u=${encodeURIComponent(url)}${h ? `&h=${h}` : ''}`;
}

export const fetchUrl = (url: string, h = '') => route('fetch', url, h);
export const streamUrl = (url: string, h = '') => route('stream', url, h);
export const probeUrl = (url: string, h = '') => route('probe', url, h);
export const remuxUrl = (url: string, h = '') => route('remux', url, h);

/**
 * A logo: HTTPS straight from where it is (an image needs no CORS), anything else through the
 * proxy, which a page on HTTPS could not load otherwise.
 */
export function imageUrl(url: string | undefined): string | undefined {
  if (!url) return undefined;
  if (url.startsWith('https://') || url.startsWith('data:')) return url;
  if (url.startsWith('http://')) return streamUrl(url);
  return undefined;
}

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(`${code} (${status})`);
  }
}

/** Reads a proxied text resource, turning the proxy's own failures into [HttpError]. */
export async function fetchText(url: string, h = '', signal?: AbortSignal): Promise<string> {
  const res = await fetch(fetchUrl(url, h), { signal, credentials: 'same-origin' });
  if (!res.ok) throw await httpError(res);
  return res.text();
}

export async function httpError(res: Response): Promise<HttpError> {
  let code = 'http';
  if ((res.headers.get('content-type') ?? '').includes('application/json')) {
    try {
      code = (await res.json()).error ?? code;
    } catch {
      // the upstream's own body
    }
  }
  return new HttpError(res.status, code);
}
