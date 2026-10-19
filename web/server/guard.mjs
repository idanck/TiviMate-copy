// @ts-check
import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';

/**
 * Which upstream addresses the proxy may reach.
 *
 * The proxy fetches whatever URL a playlist names, so on a machine inside a home network it would
 * otherwise be a way in to the router, the NAS and everything else on the LAN. Private, loopback,
 * link-local and carrier-grade NAT ranges are refused unless SHIDUR_WEB_ALLOW_PRIVATE is set —
 * which a provider on the LAN (tvheadend, a local test server) needs.
 *
 * The check resolves the name and looks at every address it gets back. It is not proof against a
 * DNS answer that changes between this lookup and the fetch's own, which is acceptable for a
 * proxy that only a signed-in owner can use.
 */

/** @param {string} ip */
export function isPrivateAddress(ip) {
  const v = isIP(ip);
  if (v === 4) return isPrivateV4(ip);
  if (v === 6) {
    const lower = ip.toLowerCase();
    const mapped = lower.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    if (mapped) return isPrivateV4(mapped[1]);
    if (lower === '::' || lower === '::1') return true;
    const first = parseInt(lower.split(':')[0] || '0', 16);
    if ((first & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
    if ((first & 0xffc0) === 0xfe80) return true; // fe80::/10 link local
    if ((first & 0xff00) === 0xff00) return true; // multicast
    return false;
  }
  return true;
}

/** @param {string} ip */
function isPrivateV4(ip) {
  const [a, b] = ip.split('.').map(Number);
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    a >= 224
  );
}

export class BlockedError extends Error {
  /** @param {string} message */
  constructor(message) {
    super(message);
    this.code = 'blocked';
  }
}

/**
 * Throws [BlockedError] when [url] is not an http(s) address on the public internet.
 *
 * @param {URL} url
 * @param {{ allowPrivate: boolean }} options
 */
export async function assertReachable(url, { allowPrivate }) {
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new BlockedError(`unsupported scheme ${url.protocol}`);
  }
  if (allowPrivate) return;
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.localhost')) throw new BlockedError('private address');
  const addresses = isIP(host) ? [{ address: host }] : await lookup(host, { all: true });
  if (addresses.length === 0 || addresses.some((a) => isPrivateAddress(a.address))) {
    throw new BlockedError('private address');
  }
}
