// @ts-check
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { FirebaseGate, firestoreEntitlement, relayAuthPage, webEntitlement } from '../server/firebase.mjs';

/*
 * The server's Firebase mode (ADR-059, stage 5): Google sign-in, and only an account with
 * `entitlements.web`. Nothing here reaches Google: the gate is given its own verifier and
 * entitlement reader, the Firestore reader and the sign-in relay a fake `fetch`, and the end-to-end
 * checks below use tokens the Admin SDK refuses before it would fetch a key.
 */

const PROJECT = 'demo-shidur';
const WEB_CONFIG = { apiKey: 'demo-key', appId: '1:1:web:1', authDomain: 'elsewhere.example', messagingSenderId: '1' };
const NOW = 1_800_000_000_000;

/**
 * @param {{ entitled?: boolean, exp?: number, fails?: boolean, readFails?: boolean }} [o]
 */
function gate(o = {}) {
  const calls = { verify: 0, read: 0 };
  const g = new FirebaseGate({
    projectId: PROJECT,
    webConfig: WEB_CONFIG,
    secret: 'test-secret',
    verify: async (token) => {
      calls.verify++;
      if (o.fails || token === 'bad') throw new Error('auth/argument-error');
      return { uid: 'u1', exp: o.exp ?? NOW / 1000 + 3600 };
    },
    readEntitlement: async () => {
      calls.read++;
      if (o.readFails) throw new Error('Firestore answered 500');
      return o.entitled ?? true;
    },
  });
  return { g, calls };
}

describe('FirebaseGate', () => {
  it('makes a session of a valid token for a subscribed account, lasting as long as the token', async () => {
    const { g } = gate();
    const r = await g.signIn('good', 'ip', NOW);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.cookie.maxAgeSeconds).toBe(3600);
    expect(g.valid(`shidur_session=${r.cookie.value}`, 'shidur_session', NOW + 60_000)).toBe(true);
    // The session ends with the token.
    expect(g.valid(`shidur_session=${r.cookie.value}`, 'shidur_session', NOW + 3601_000)).toBe(false);
  });

  it('refuses a token the verifier refuses', async () => {
    const { g, calls } = gate();
    expect(await g.signIn('bad', 'ip', NOW)).toEqual({ ok: false, status: 401, error: 'invalid-token' });
    expect(calls.read).toBe(0);
  });

  it('refuses a token that has already expired', async () => {
    const { g, calls } = gate({ exp: NOW / 1000 - 1 });
    expect(await g.signIn('good', 'ip', NOW)).toEqual({ ok: false, status: 401, error: 'invalid-token' });
    expect(calls.read).toBe(0);
  });

  it('refuses an account without entitlements.web', async () => {
    const { g } = gate({ entitled: false });
    expect(await g.signIn('good', 'ip', NOW)).toEqual({ ok: false, status: 403, error: 'not-entitled' });
  });

  it('says so when the subscription cannot be read, rather than calling it no subscription', async () => {
    const { g } = gate({ readFails: true });
    expect(await g.signIn('good', 'ip', NOW)).toEqual({ ok: false, status: 502, error: 'unavailable' });
  });

  it('refuses a forged, altered or password-mode cookie', async () => {
    const { g } = gate();
    const r = await g.signIn('good', 'ip', NOW);
    if (!r.ok) throw new Error('no session');
    const [expiry, , mac] = r.cookie.value.split('.');
    expect(g.valid(`shidur_session=${expiry}.u2.${mac}`, 'shidur_session', NOW)).toBe(false);
    expect(g.valid(`shidur_session=${Number(expiry) + 1}.u1.${mac}`, 'shidur_session', NOW)).toBe(false);
    expect(g.valid(`shidur_session=${expiry}.${mac}`, 'shidur_session', NOW)).toBe(false);
    const other = new FirebaseGate({ projectId: PROJECT, webConfig: WEB_CONFIG, secret: 'another', verify: async () => ({ uid: 'u1', exp: 0 }), readEntitlement: async () => true });
    expect(other.valid(`shidur_session=${r.cookie.value}`, 'shidur_session', NOW)).toBe(false);
  });

  it('locks an address out after twenty refused tokens', async () => {
    const { g } = gate();
    for (let i = 0; i < 20; i++) expect((await g.signIn('bad', 'ip', NOW)).ok).toBe(false);
    expect(await g.signIn('good', 'ip', NOW)).toEqual({ ok: false, status: 429, error: 'locked' });
    expect((await g.signIn('good', 'other-ip', NOW)).ok).toBe(true);
    expect((await g.signIn('good', 'ip', NOW + 11 * 60_000)).ok).toBe(true);
  });

  it('hands the page its configuration with this site as the sign-in domain, and the region', () => {
    const { g } = gate();
    const c = g.clientConfig();
    expect(c.authDomain).toBeUndefined();
    expect(c).toMatchObject({ apiKey: 'demo-key', appId: '1:1:web:1', projectId: PROJECT, region: 'me-west1', renewMinutes: 30 });
  });

  it('is configured from the environment, or not at all', () => {
    expect(FirebaseGate.fromEnv({})).toBeNull();
    expect(() => FirebaseGate.fromEnv({ SHIDUR_FIREBASE_PROJECT_ID: PROJECT })).toThrow(/SHIDUR_FIREBASE_WEB_CONFIG/);
    expect(() => FirebaseGate.fromEnv({ SHIDUR_FIREBASE_PROJECT_ID: PROJECT, SHIDUR_FIREBASE_WEB_CONFIG: '{"apiKey":"x"}' })).toThrow(/appId/);
    expect(() => FirebaseGate.fromEnv({
      SHIDUR_FIREBASE_PROJECT_ID: PROJECT,
      SHIDUR_FIREBASE_WEB_CONFIG: JSON.stringify({ ...WEB_CONFIG, projectId: 'other' }),
    })).toThrow(/another project/);
    const g = FirebaseGate.fromEnv({ SHIDUR_FIREBASE_PROJECT_ID: PROJECT, SHIDUR_FIREBASE_WEB_CONFIG: JSON.stringify(WEB_CONFIG) });
    expect(g?.authOrigin).toBe(`https://${PROJECT}.firebaseapp.com`);
  });
});

describe('the entitlement', () => {
  const doc = (/** @type {any} */ web) => ({ fields: { entitlements: { mapValue: { fields: { web } } } } });

  it('is true only for a boolean true', () => {
    expect(webEntitlement(doc({ booleanValue: true }))).toBe(true);
    expect(webEntitlement(doc({ booleanValue: false }))).toBe(false);
    expect(webEntitlement(doc({ stringValue: 'true' }))).toBe(false);
    expect(webEntitlement({ fields: {} })).toBe(false);
    expect(webEntitlement(null)).toBe(false);
  });

  it('is read from the account\'s own document with its own token', async () => {
    /** @type {Array<[string, any]>} */
    const seen = [];
    const fakeFetch = /** @type {typeof fetch} */ (/** @type {unknown} */ (async (/** @type {string} */ url, /** @type {any} */ init) => {
      seen.push([url, init]);
      if (url.endsWith('/users/missing')) return new Response('{}', { status: 404 });
      if (url.endsWith('/users/broken')) return new Response('{}', { status: 403 });
      return new Response(JSON.stringify(doc({ booleanValue: true })), { status: 200 });
    }));
    const read = firestoreEntitlement(PROJECT, fakeFetch);
    expect(await read('u1', 'TOKEN')).toBe(true);
    expect(seen[0][0]).toBe(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/users/u1`);
    expect(seen[0][1].headers.authorization).toBe('Bearer TOKEN');
    expect(await read('missing', 'TOKEN')).toBe(false);
    await expect(read('broken', 'TOKEN')).rejects.toThrow(/403/);
  });
});

describe('the sign-in relay', () => {
  it('serves the project\'s /__/auth pages from this site, with their own headers', async () => {
    const { g } = gate();
    /** @type {string[]} */
    const asked = [];
    const fakeFetch = /** @type {typeof fetch} */ (/** @type {unknown} */ (async (/** @type {string} */ url) => {
      asked.push(url);
      return new Response('<html>handler</html>', {
        status: 200,
        headers: { 'content-type': 'text/html', 'content-encoding': 'gzip', 'x-test': '1' },
      });
    }));
    /** @type {Record<string, any>} */
    const headers = {};
    let body = '';
    const res = /** @type {any} */ ({
      statusCode: 0,
      setHeader: (/** @type {string} */ k, /** @type {any} */ v) => { headers[k] = v; },
      end: (/** @type {Buffer} */ b) => { body = b ? b.toString() : ''; },
    });
    const req = /** @type {any} */ ({ method: 'GET', headers: { accept: 'text/html' } });
    await relayAuthPage(req, res, new URL('http://local/__/auth/handler?apiKey=x'), g, fakeFetch);
    expect(asked).toEqual([`https://${PROJECT}.firebaseapp.com/__/auth/handler?apiKey=x`]);
    expect(res.statusCode).toBe(200);
    expect(body).toBe('<html>handler</html>');
    expect(headers['x-test']).toBe('1');
    expect(headers['content-encoding']).toBeUndefined();
    expect(headers['content-length']).toBe(body.length);
  });

  it('refuses anything but reading', async () => {
    const { g } = gate();
    const res = /** @type {any} */ ({ statusCode: 0, setHeader() {}, end() {} });
    await relayAuthPage(/** @type {any} */ ({ method: 'POST', headers: {} }), res, new URL('http://local/__/auth/handler'), g);
    expect(res.statusCode).toBe(405);
  });
});

// ---------------------------------------------------------------- the server as it runs

const SERVER = fileURLToPath(new URL('../server/index.mjs', import.meta.url));
/** @type {import('node:child_process').ChildProcess[]} */
const running = [];
afterEach(() => {
  for (const p of running.splice(0)) p.kill();
});

async function freePort() {
  return new Promise((resolve) => {
    const s = createServer().listen(0, '127.0.0.1', () => {
      const port = /** @type {import('node:net').AddressInfo} */ (s.address()).port;
      s.close(() => resolve(port));
    });
  });
}

/** Starts the server with [env] and waits for it; the base URL. @param {Record<string, string>} env */
async function serve(env) {
  const port = await freePort();
  const child = spawn(process.execPath, [SERVER], {
    env: { PATH: process.env.PATH, PORT: String(port), HOST: '127.0.0.1', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  running.push(child);
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 100; i++) {
    try {
      await fetch(`${base}/api/health`);
      return base;
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  throw new Error('the server did not start');
}

/** A JWT-shaped token for another project: the Admin SDK refuses its audience before any key. */
function foreignToken() {
  const part = (/** @type {object} */ o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  return [
    part({ alg: 'RS256', kid: 'k', typ: 'JWT' }),
    part({ aud: 'someone-else', iss: 'https://securetoken.google.com/someone-else', sub: 'u1', iat: now - 10, exp: now + 3600, auth_time: now - 10 }),
    'c2ln',
  ].join('.');
}

const FIREBASE_ENV = { SHIDUR_FIREBASE_PROJECT_ID: PROJECT, SHIDUR_FIREBASE_WEB_CONFIG: JSON.stringify(WEB_CONFIG) };

describe('the server in its Firebase mode', () => {
  it('says so, hands out the configuration, and keeps /api/ closed without a session', async () => {
    const base = await serve(FIREBASE_ENV);
    const session = await (await fetch(`${base}/api/session`)).json();
    expect(session).toMatchObject({ authRequired: true, authenticated: false, mode: 'firebase' });
    expect(session.firebase).toMatchObject({ apiKey: 'demo-key', projectId: PROJECT, region: 'me-west1' });
    expect(session.firebase.authDomain).toBeUndefined();
    expect((await fetch(`${base}/api/fetch?u=${encodeURIComponent('https://example.com/a.m3u')}`)).status).toBe(401);
    expect((await fetch(`${base}/api/health`)).status).toBe(200);
  });

  it('lets the page reach Google for sign-in, and nothing else', async () => {
    const base = await serve(FIREBASE_ENV);
    const csp = (await fetch(`${base}/api/health`)).headers.get('content-security-policy') || '';
    expect(csp).toContain("script-src 'self' https://apis.google.com");
    expect(csp).toContain('https://identitytoolkit.googleapis.com');
    expect(csp).toContain(`https://me-west1-${PROJECT}.cloudfunctions.net`);
  });

  it('refuses a malformed token, a token for another project, and an empty body', async () => {
    const base = await serve(FIREBASE_ENV);
    const login = (/** @type {object} */ body) => fetch(`${base}/api/login`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
    });
    const garbage = await login({ idToken: 'not-a-token' });
    expect(garbage.status).toBe(401);
    expect(await garbage.json()).toEqual({ error: 'invalid-token' });
    expect((await login({ idToken: foreignToken() })).status).toBe(401);
    expect((await login({})).status).toBe(400);
  });

  it('ignores the password when a Firebase project is set', async () => {
    const base = await serve({ ...FIREBASE_ENV, SHIDUR_WEB_PASSWORD: 'pw' });
    const r = await fetch(`${base}/api/login`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ password: 'pw' }),
    });
    expect(r.status).toBe(400);
    expect(r.headers.get('set-cookie')).toBeNull();
  });

  it('will not start with a project and no web configuration', async () => {
    const child = spawn(process.execPath, [SERVER], {
      env: { PATH: process.env.PATH, PORT: String(await freePort()), SHIDUR_FIREBASE_PROJECT_ID: PROJECT },
      stdio: 'ignore',
    });
    const code = await new Promise((resolve) => child.on('exit', resolve));
    expect(code).not.toBe(0);
  });
});

describe('the server in its password mode, unchanged', () => {
  it('opens /api/ with the right password and only with it', async () => {
    const base = await serve({ SHIDUR_WEB_PASSWORD: 'pw' });
    const session = await (await fetch(`${base}/api/session`)).json();
    expect(session).toMatchObject({ authRequired: true, authenticated: false, mode: 'password' });
    expect(session.firebase).toBeUndefined();
    const login = (/** @type {string} */ password) => fetch(`${base}/api/login`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ password }),
    });
    expect((await login('nope')).status).toBe(401);
    const ok = await login('pw');
    expect(ok.status).toBe(200);
    const cookie = (ok.headers.get('set-cookie') || '').split(';')[0];
    expect((await fetch(`${base}/api/session`, { headers: { cookie } }).then((r) => r.json())).authenticated).toBe(true);
    // Past the guard: the proxy answers for itself, not 401.
    expect((await fetch(`${base}/api/fetch`, { headers: { cookie } })).status).not.toBe(401);
    // No sign-in relay and no Google in the policy without a project.
    const csp = ok.headers.get('content-security-policy') || '';
    expect(csp).not.toContain('apis.google.com');
  });

  it('is open with neither', async () => {
    const base = await serve({});
    expect(await (await fetch(`${base}/api/session`)).json()).toMatchObject({ authRequired: false, authenticated: true, mode: 'open' });
  });
});
