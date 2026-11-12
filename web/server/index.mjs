// @ts-check
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Auth, COOKIE } from './auth.mjs';
import { FirebaseGate, relayAuthPage } from './firebase.mjs';
import { INTERNAL_HEADER, Proxy, sendJson } from './proxy.mjs';
import { redactUrl } from './redact.mjs';

/**
 * Shidur's web server: the built PWA, and the proxy it needs to reach providers (proxy.mjs).
 *
 * Configuration, all optional:
 *   PORT                      default 8787
 *   HOST                      default 127.0.0.1 (set 0.0.0.0 inside a container)
 *   SHIDUR_FIREBASE_PROJECT_ID   the Firebase project: sign-in with Google, and only an account
 *                             whose `entitlements.web` is true gets in (ADR-059). Wins over the
 *                             password when both are set.
 *   SHIDUR_FIREBASE_WEB_CONFIG   that project's web app configuration, as JSON (needed with it)
 *   SHIDUR_WEB_PASSWORD       a password in front of the proxy, when there is no Firebase project;
 *                             strongly advised when reachable from anywhere but this machine
 *   SHIDUR_WEB_SECRET         the session-signing key; derived from the password when unset, and
 *                             random at each start in the Firebase mode
 *   SHIDUR_WEB_ALLOW_PRIVATE  1 to let playlists name LAN addresses (refused by default)
 *   SHIDUR_WEB_USER_AGENT     the default User-Agent towards providers
 *   SHIDUR_WEB_DIST           where the built app is (default ../dist)
 */

const here = fileURLToPath(new URL('.', import.meta.url));
const config = {
  port: Number(process.env.PORT || 8787),
  host: process.env.HOST || '127.0.0.1',
  dist: resolve(process.env.SHIDUR_WEB_DIST || join(here, '..', 'dist')),
};

const auth = new Auth({ password: process.env.SHIDUR_WEB_PASSWORD, secret: process.env.SHIDUR_WEB_SECRET });
// With a Firebase project, Google sign-in is the only way in and the password is not read at all.
const firebase = FirebaseGate.fromEnv(process.env);
// Loaded now rather than at the first sign-in, so an install without it fails here, loudly, instead
// of refusing every account as if its token were bad.
if (firebase) await import('firebase-admin/auth');
/** @type {'firebase' | 'password' | 'open'} */
const mode = firebase ? 'firebase' : auth.required ? 'password' : 'open';
const authRequired = mode !== 'open';
/** @param {import('node:http').IncomingMessage} req */
const authenticated = (req) => (firebase ? firebase.valid(req.headers.cookie, COOKIE) : auth.valid(req.headers.cookie));
const proxy = new Proxy({
  allowPrivate: process.env.SHIDUR_WEB_ALLOW_PRIVATE === '1',
  userAgent: process.env.SHIDUR_WEB_USER_AGENT,
});
// ffmpeg, started by /api/remux, reads the stream back through /api/stream on this server; it has
// no session, so it carries a key made at each start and known to nobody else.
const internalKey = randomBytes(32).toString('hex');
/** @param {import('node:http').IncomingMessage} req */
const internal = (req) => {
  const given = req.headers[INTERNAL_HEADER];
  return typeof given === 'string' && given.length === internalKey.length &&
    timingSafeEqual(Buffer.from(given), Buffer.from(internalKey));
};
const version = readVersion();

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

// Signing in with Google loads Google's frame script and talks to Firebase Auth and the project's
// Functions; the sign-in frame itself is this site's own (/__/auth/iframe, relayed).
const SIGN_IN_SCRIPTS = firebase ? ' https://apis.google.com' : '';
const SIGN_IN_CONNECT = firebase ? ` ${firebase.connectSources.join(' ')}` : '';

const SECURITY_HEADERS = {
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'x-frame-options': 'DENY',
  'content-security-policy': [
    "default-src 'self'",
    `script-src 'self'${SIGN_IN_SCRIPTS}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' https: data: blob:",
    "media-src 'self' blob:",
    `connect-src 'self'${SIGN_IN_CONNECT}`,
    "frame-src 'self'",
    "worker-src 'self' blob:",
    "font-src 'self'",
    "frame-ancestors 'none'",
  ].join('; '),
};

/** Routes reachable without a session: the shell needs them to show the sign-in screen. */
const PUBLIC_API = new Set(['/api/health', '/api/session', '/api/login', '/api/logout']);

const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://local');
  try {
    // Firebase Auth's own pages, relayed with their own headers: they are framed by this site and
    // run Google's scripts, which this site's policy would refuse.
    if (firebase && url.pathname.startsWith('/__/')) return await relayAuthPage(req, res, url, firebase);
    for (const [k, v] of Object.entries(SECURITY_HEADERS)) res.setHeader(k, v);
    if (url.pathname.startsWith('/api/')) return await api(req, res, url);
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendJson(res, 405, { error: 'method' });
    return serveStatic(req, res, url.pathname);
  } catch (err) {
    return failure(res, err, url);
  }
});

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {URL} url
 */
async function api(req, res, url) {
  const path = url.pathname;
  // ffmpeg's own way in (/api/remux): /api/stream/<name>, with the key.
  if (path.startsWith('/api/stream/')) {
    if (!internal(req)) return sendJson(res, 401, { error: 'auth' });
    return await proxy.stream(req, res, url.searchParams, { named: true });
  }
  if (!PUBLIC_API.has(path) && !authenticated(req)) return sendJson(res, 401, { error: 'auth' });

  switch (path) {
    case '/api/health':
      return sendJson(res, 200, { ok: true, version, remux: proxy.ffmpeg, authRequired, mode });
    case '/api/session':
      return sendJson(res, 200, {
        authRequired,
        authenticated: authenticated(req),
        mode,
        ...(firebase ? { firebase: firebase.clientConfig() } : {}),
      });
    case '/api/login': {
      if (req.method !== 'POST') return sendJson(res, 405, { error: 'method' });
      if (firebase) return await firebaseLogin(req, res, firebase);
      if (!auth.required) return sendJson(res, 200, { ok: true });
      const body = await readBody(req, 4096);
      let password = '';
      try {
        password = String(JSON.parse(body).password ?? '');
      } catch {
        return sendJson(res, 400, { error: 'bad-request' });
      }
      const verdict = auth.check(password, req.socket.remoteAddress || 'unknown');
      if (verdict === 'locked') return sendJson(res, 429, { error: 'locked' });
      if (verdict === 'wrong') return sendJson(res, 401, { error: 'wrong-password' });
      const session = auth.issue();
      res.setHeader('set-cookie', cookie(req, session.value, session.maxAgeSeconds));
      return sendJson(res, 200, { ok: true });
    }
    case '/api/logout':
      res.setHeader('set-cookie', cookie(req, '', 0));
      return sendJson(res, 200, { ok: true });
    case '/api/fetch':
      return await proxy.fetchText(req, res, url.searchParams);
    case '/api/stream':
      return await proxy.stream(req, res, url.searchParams);
    case '/api/probe':
      return await proxy.probe(req, res, url.searchParams);
    case '/api/remux':
      return await proxy.remux(req, res, url.searchParams);
    default:
      return sendJson(res, 404, { error: 'not-found' });
  }
}

/**
 * `POST /api/login` in the Firebase mode: `{ idToken }`, a fresh Firebase ID token. The page sends
 * one when it signs in and again at every renewal (`RENEW_MINUTES`).
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {FirebaseGate} gate
 */
async function firebaseLogin(req, res, gate) {
  // An ID token is a JWT of a kilobyte or two.
  const body = await readBody(req, 16_384);
  let idToken = '';
  try {
    idToken = String(JSON.parse(body).idToken ?? '');
  } catch {
    return sendJson(res, 400, { error: 'bad-request' });
  }
  if (!idToken) return sendJson(res, 400, { error: 'bad-request' });
  const result = await gate.signIn(idToken, req.socket.remoteAddress || 'unknown');
  if (!result.ok) {
    // A session that no longer holds — a lost entitlement, a dead token — ends here too.
    if (result.status === 401 || result.status === 403) res.setHeader('set-cookie', cookie(req, '', 0));
    return sendJson(res, result.status, { error: result.error });
  }
  res.setHeader('set-cookie', cookie(req, result.cookie.value, result.cookie.maxAgeSeconds));
  return sendJson(res, 200, { ok: true });
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {string} value
 * @param {number} maxAge
 */
function cookie(req, value, maxAge) {
  // Behind a TLS-terminating proxy (Cloudflare, a reverse proxy) the request arrives as plain
  // HTTP; the forwarded protocol says whether the browser is on HTTPS.
  const secure = req.headers['x-forwarded-proto'] === 'https' ? '; Secure' : '';
  return `${COOKIE}=${encodeURIComponent(value)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure}`;
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {string} pathname
 */
function serveStatic(req, res, pathname) {
  if (!existsSync(config.dist)) {
    res.statusCode = 503;
    return void res.end('The web app is not built yet: run `npm run build`.');
  }
  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return sendJson(res, 400, { error: 'bad-path' });
  }
  let file = normalize(join(config.dist, decoded));
  if (file !== config.dist && !file.startsWith(config.dist + sep)) return sendJson(res, 400, { error: 'bad-path' });
  const isFile = existsSync(file) && statSync(file).isFile();
  // The app is one page: any path that is not a file is the page.
  if (!isFile) file = join(config.dist, 'index.html');
  const type = TYPES[/** @type {keyof typeof TYPES} */ (extname(file))] || 'application/octet-stream';
  res.setHeader('content-type', type);
  // Built assets are named by their content hash and never change; everything else — the page,
  // the service worker, the manifest — must be revalidated so a new build is picked up.
  res.setHeader('cache-control', pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache');
  if (req.method === 'HEAD') return void res.end();
  createReadStream(file).pipe(res);
}

/**
 * @param {import('node:http').ServerResponse} res
 * @param {any} err
 * @param {URL} url
 */
function failure(res, err, url) {
  const code = err?.code || err?.cause?.code || err?.name;
  if (code === 'ERR_STREAM_PREMATURE_CLOSE' || code === 'AbortError' && res.destroyed) return;
  const target = url.searchParams.get('u');
  const where = target ? ` ${redactUrl(target)}` : '';
  if (code === 'bad-request') return sendJson(res, 400, { error: 'bad-request' });
  if (code === 'blocked') return sendJson(res, 403, { error: 'blocked', detail: err.message });
  if (code === 'too-large') return sendJson(res, 413, { error: 'too-large' });
  if (err?.message === 'timeout' || code === 'TimeoutError' || code === 'UND_ERR_CONNECT_TIMEOUT') {
    console.warn(`timeout${where}`);
    return sendJson(res, 504, { error: 'timeout' });
  }
  if (code === 'ENOTFOUND' || code === 'ECONNREFUSED' || code === 'ECONNRESET' || code === 'EAI_AGAIN' ||
      code === 'redirects' || code === 'UND_ERR_SOCKET' || err?.message === 'fetch failed') {
    console.warn(`unreachable (${code})${where}`);
    return sendJson(res, 502, { error: 'unreachable', detail: String(code) });
  }
  if (res.headersSent) return void res.destroy();
  console.error(`error${where}:`, err);
  return sendJson(res, 500, { error: 'internal' });
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {number} limit
 */
function readBody(req, limit) {
  return new Promise((resolveBody, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > limit) {
        reject(Object.assign(new Error('body too large'), { code: 'too-large' }));
        req.destroy();
      }
    });
    req.on('end', () => resolveBody(data));
    req.on('error', reject);
  });
}

function readVersion() {
  try {
    const props = readFileSync(join(here, '..', '..', 'version.properties'), 'utf8');
    const get = (/** @type {string} */ k) => props.match(new RegExp(`^${k}=(\\d+)`, 'm'))?.[1] ?? '0';
    return `${get('versionMajor')}.${get('versionMinor')}.${get('versionPatch')}`;
  } catch {
    return process.env.SHIDUR_VERSION || 'dev';
  }
}

server.listen(config.port, config.host, () => {
  const bound = server.address();
  if (bound && typeof bound === 'object') {
    // The address ffmpeg reaches this server at: the one it listens on, or loopback when it
    // listens on all of them.
    const any = bound.address === '0.0.0.0' || bound.address === '::';
    const host = any ? '127.0.0.1' : bound.family === 'IPv6' ? `[${bound.address}]` : bound.address;
    proxy.internal = { origin: `http://${host}:${bound.port}`, key: internalKey };
  }
  const access = mode === 'firebase' ? `Google sign-in (${firebase?.projectId})` : mode === 'password' ? 'password' : 'open';
  console.log(`Shidur web ${version} on http://${config.host}:${config.port}` +
    ` · access ${access} · remux ${proxy.ffmpeg ? 'on' : 'off (no ffmpeg)'}`);
  if (firebase && auth.required) {
    console.warn('Note: SHIDUR_FIREBASE_PROJECT_ID is set, so SHIDUR_WEB_PASSWORD is ignored: only Google sign-in lets anyone in.');
  }
  const loopback = config.host === '127.0.0.1' || config.host === '::1' || config.host === 'localhost';
  if (mode === 'open' && !loopback) {
    console.warn('Warning: listening beyond this machine without SHIDUR_WEB_PASSWORD or a Firebase project — anyone who reaches it can use the proxy.');
  }
});
