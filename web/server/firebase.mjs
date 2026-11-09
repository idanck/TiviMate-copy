// @ts-check
import { createHmac, randomBytes } from 'node:crypto';
import { readCookie, safeEqual } from './auth.mjs';

/**
 * Sign-in with a Google account (ADR-059, stage 5): the mode the server runs in when a Firebase
 * project is configured, in place of the password.
 *
 * The browser signs in with Firebase Auth and hands the server its ID token (`POST /api/login`).
 * The server checks the token with the Admin SDK — which needs only the project ID, no service
 * account: the signature is checked against Google's public keys — and then reads the account's
 * own `users/{uid}` through Firestore's REST API *with that same token*, which the rules already
 * let an account do. Only an account whose `entitlements.web` is true gets a session.
 *
 * The session is a cookie, `<expiry>.<uid>.<hmac>`, signed like the password mode's and stored
 * nowhere. It lasts as long as the token it was made from (an hour at most). The page renews it
 * every RENEW_MINUTES with a fresh token, and every renewal checks the entitlement again, so an
 * entitlement switched off ends the session at the next renewal — within RENEW_MINUTES while the
 * app is open, and within the hour in any case, since an unrenewed session simply expires.
 */

/** Where the project's Cloud Functions run (`firebase/functions/src/config.ts`, ADR-059). */
export const FUNCTIONS_REGION = 'me-west1';
/** How often the page renews its session, and so how soon a lost entitlement is noticed. */
export const RENEW_MINUTES = 30;

const MAX_FAILURES = 20;
const FAILURE_WINDOW_MS = 10 * 60_000;

/**
 * @typedef {{ uid: string, exp: number }} VerifiedToken  exp in seconds, as in the token
 * @typedef {(idToken: string) => Promise<VerifiedToken>} Verifier
 * @typedef {(uid: string, idToken: string) => Promise<boolean>} EntitlementReader
 * @typedef {{ ok: true, cookie: { value: string, maxAgeSeconds: number } }
 *   | { ok: false, status: number, error: 'invalid-token' | 'not-entitled' | 'unavailable' | 'locked' }} SignInResult
 */

export class FirebaseGate {
  /**
   * @param {{
   *   projectId: string,
   *   webConfig: Record<string, unknown>,
   *   secret?: string,
   *   verify?: Verifier,
   *   readEntitlement?: EntitlementReader,
   * }} options  `verify` and `readEntitlement` default to the real ones; tests pass their own.
   */
  constructor({ projectId, webConfig, secret, verify, readEntitlement }) {
    this.projectId = projectId;
    // The page's Firebase needs the project too, whether or not the configuration named it.
    this.webConfig = { ...webConfig, projectId };
    // A secret given is kept across restarts; without one a restart signs every browser out,
    // and the page signs straight back in with the account Firebase still holds.
    this.key = secret || randomBytes(32).toString('hex');
    this.verify = verify || adminVerifier(projectId);
    this.readEntitlement = readEntitlement || firestoreEntitlement(projectId);
    /** @type {Map<string, { count: number, since: number }>} */
    this.failures = new Map();
  }

  /**
   * The Firebase mode's configuration from the environment, or null when no project is set.
   *
   * @param {NodeJS.ProcessEnv} env
   */
  static fromEnv(env) {
    const projectId = (env.SHIDUR_FIREBASE_PROJECT_ID || '').trim();
    if (!projectId) return null;
    let webConfig;
    try {
      webConfig = JSON.parse(env.SHIDUR_FIREBASE_WEB_CONFIG || '');
    } catch {
      throw new Error('SHIDUR_FIREBASE_WEB_CONFIG must be the web app\'s Firebase configuration, as JSON');
    }
    if (!webConfig || typeof webConfig !== 'object' || !webConfig.apiKey || !webConfig.appId) {
      throw new Error('SHIDUR_FIREBASE_WEB_CONFIG needs at least apiKey and appId');
    }
    if (webConfig.projectId && webConfig.projectId !== projectId) {
      throw new Error('SHIDUR_FIREBASE_WEB_CONFIG is for another project than SHIDUR_FIREBASE_PROJECT_ID');
    }
    return new FirebaseGate({ projectId, webConfig, secret: env.SHIDUR_WEB_SECRET });
  }

  /** What the page needs to start Firebase: none of it is secret. `authDomain` is the page's own host. */
  clientConfig() {
    /** @type {Record<string, unknown>} */
    const config = { ...this.webConfig, region: FUNCTIONS_REGION, renewMinutes: RENEW_MINUTES };
    delete config.authDomain;
    return config;
  }

  /** The Firebase Hosting site whose sign-in pages `/__/auth/*` is relayed to. */
  get authOrigin() {
    return `https://${this.projectId}.firebaseapp.com`;
  }

  /** The hosts the page reaches while signing in, for its Content-Security-Policy. */
  get connectSources() {
    return [
      'https://identitytoolkit.googleapis.com',
      'https://securetoken.googleapis.com',
      'https://www.googleapis.com',
      `https://${FUNCTIONS_REGION}-${this.projectId}.cloudfunctions.net`,
    ];
  }

  /** @param {number} expiry @param {string} uid */
  sign(expiry, uid) {
    return createHmac('sha256', this.key).update(`${expiry}.${uid}`).digest('base64url');
  }

  /**
   * Checks an ID token and the account's entitlement, and makes a session of it.
   *
   * @param {string} idToken
   * @param {string} client a key for rate limiting, the remote address
   * @returns {Promise<SignInResult>}
   */
  async signIn(idToken, client, now = Date.now()) {
    const entry = this.failures.get(client);
    if (entry && now - entry.since > FAILURE_WINDOW_MS) this.failures.delete(client);
    if ((this.failures.get(client)?.count ?? 0) >= MAX_FAILURES) return { ok: false, status: 429, error: 'locked' };

    /** @type {VerifiedToken} */
    let token;
    try {
      token = await this.verify(idToken);
    } catch {
      const current = this.failures.get(client);
      this.failures.set(client, { count: (current?.count ?? 0) + 1, since: current?.since ?? now });
      return { ok: false, status: 401, error: 'invalid-token' };
    }
    const expiry = token.exp * 1000;
    if (!token.uid || !Number.isFinite(expiry) || expiry <= now) return { ok: false, status: 401, error: 'invalid-token' };

    let entitled;
    try {
      entitled = await this.readEntitlement(token.uid, idToken);
    } catch (e) {
      console.warn(`entitlement check failed: ${/** @type {Error} */ (e).message}`);
      return { ok: false, status: 502, error: 'unavailable' };
    }
    if (!entitled) return { ok: false, status: 403, error: 'not-entitled' };

    this.failures.delete(client);
    return {
      ok: true,
      cookie: { value: `${expiry}.${token.uid}.${this.sign(expiry, token.uid)}`, maxAgeSeconds: Math.floor((expiry - now) / 1000) },
    };
  }

  /** @param {string | undefined} cookieHeader @param {string} name */
  valid(cookieHeader, name, now = Date.now()) {
    const value = readCookie(cookieHeader, name);
    if (!value) return false;
    const parts = value.split('.');
    if (parts.length !== 3) return false;
    const [expiryText, uid, mac] = parts;
    const expiry = Number(expiryText);
    if (!Number.isFinite(expiry) || expiry < now || !uid || !mac) return false;
    return safeEqual(mac, this.sign(expiry, uid));
  }
}

/**
 * The Admin SDK's own check: signature against Google's rotating public keys, `aud` and `iss` for
 * this project, `exp`, `iat` and `auth_time`. Without credentials it cannot ask whether the token
 * was revoked; the entitlement read below, with the token itself, is the live check instead.
 *
 * @param {string} projectId
 * @returns {Verifier}
 */
function adminVerifier(projectId) {
  /** @type {Promise<import('firebase-admin/auth').Auth> | undefined} */
  let auth;
  const load = async () => {
    const { initializeApp, getApps } = await import('firebase-admin/app');
    const { getAuth } = await import('firebase-admin/auth');
    const app = getApps().find((a) => a.name === 'shidur-web') || initializeApp({ projectId }, 'shidur-web');
    return getAuth(app);
  };
  return async (idToken) => {
    auth ??= load();
    const decoded = await (await auth).verifyIdToken(idToken);
    return { uid: decoded.uid, exp: decoded.exp };
  };
}

/**
 * Reads `users/{uid}.entitlements.web` through Firestore's REST API, as the account itself. A
 * missing document is no entitlement; any answer but 200 or 404 is an error, so a misconfigured
 * project is reported rather than read as "not subscribed".
 *
 * @param {string} projectId
 * @param {typeof fetch} [fetchImpl]
 * @returns {EntitlementReader}
 */
export function firestoreEntitlement(projectId, fetchImpl = fetch) {
  return async (uid, idToken) => {
    const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}` +
      `/databases/(default)/documents/users/${encodeURIComponent(uid)}`;
    const res = await fetchImpl(url, {
      headers: { authorization: `Bearer ${idToken}` },
      signal: AbortSignal.timeout(10_000),
    });
    if (res.status === 404) return false;
    if (!res.ok) throw new Error(`Firestore answered ${res.status}`);
    return webEntitlement(await res.json());
  };
}

/**
 * Whether a Firestore REST document says `entitlements.web == true`. Anything else — no field,
 * another type, false — is no.
 *
 * @param {any} document
 */
export function webEntitlement(document) {
  return document?.fields?.entitlements?.mapValue?.fields?.web?.booleanValue === true;
}

/**
 * Relays Firebase Auth's sign-in pages from the project's Hosting site, so they are served from
 * this site's own host and `authDomain` can be that host (the "proxy" route of Firebase's *Best
 * practices for using signInWithRedirect*). Sign-in then never needs third-party storage, which
 * Safari, iOS and the current Chrome and Firefox no longer give a page.
 *
 * Only GETs of `/__/auth/…` and `/__/firebase/…`; the pages keep their own headers (their scripts
 * and frames are Google's), minus the ones that describe the connection rather than the content.
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {URL} url
 * @param {FirebaseGate} gate
 * @param {typeof fetch} [fetchImpl]
 */
export async function relayAuthPage(req, res, url, gate, fetchImpl = fetch) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.statusCode = 405;
    return void res.end();
  }
  const upstream = await fetchImpl(`${gate.authOrigin}${url.pathname}${url.search}`, {
    method: req.method,
    redirect: 'manual',
    headers: {
      accept: String(req.headers.accept || '*/*'),
      'accept-language': String(req.headers['accept-language'] || ''),
      'user-agent': String(req.headers['user-agent'] || ''),
    },
    signal: AbortSignal.timeout(15_000),
  });
  res.statusCode = upstream.status;
  for (const [name, value] of upstream.headers) {
    if (!DROPPED_HEADERS.has(name)) res.setHeader(name, value);
  }
  const body = req.method === 'HEAD' ? Buffer.alloc(0) : Buffer.from(await upstream.arrayBuffer());
  res.setHeader('content-length', body.length);
  res.end(body);
}

/** Connection-level headers, and the body's encoding, which `fetch` has already undone. */
const DROPPED_HEADERS = new Set([
  'connection', 'keep-alive', 'transfer-encoding', 'content-encoding', 'content-length',
  'set-cookie', 'alt-svc', 'strict-transport-security',
]);
