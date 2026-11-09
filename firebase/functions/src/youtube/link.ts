import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { HttpsError } from 'firebase-functions/v2/https';

/**
 * Connecting a YouTube account (ADR-061), for the subscriptions rail only: read-only access
 * (`youtube.readonly`), and the account's refresh token kept on the server alone, in
 * `ytTokens/{uid}`, which no client can read (firestore.rules). The app never holds a YouTube
 * token.
 *
 * - **Television:** Google's flow for TVs and limited-input devices. The television shows a code;
 *   the person enters it at the address Google gives (google.com/device) on a phone or computer.
 *   The device code is kept server-side in `ytLinks/{uid}` while it waits, so the television holds
 *   nothing but the code it shows.
 * - **Phone:** Google's authorization on the device returns a one-time server code for the
 *   project's web client, exchanged here.
 */

export const SCOPE = 'https://www.googleapis.com/auth/youtube.readonly';
const DEVICE_URL = 'https://oauth2.googleapis.com/device/code';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const REVOKE_URL = 'https://oauth2.googleapis.com/revoke';

export interface OAuthClient {
  id: string;
  secret: string;
}

/** A client is configured once both its values are set (the secrets hold "unset" until then). */
export function configured(c: OAuthClient): boolean {
  return !!c.id && !!c.secret && c.id !== 'unset' && c.secret !== 'unset';
}

const links = () => getFirestore().collection('ytLinks');
const tokens = () => getFirestore().collection('ytTokens');

async function post(url: string, form: Record<string, string>): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams(form).toString(),
  });
  return { status: res.status, body: (await res.json().catch(() => ({}))) as Record<string, unknown> };
}

/** The television asks for a code to show. */
export async function startDeviceLink(uid: string, tv: OAuthClient) {
  const { status, body } = await post(DEVICE_URL, { client_id: tv.id, scope: SCOPE });
  if (status !== 200 || typeof body.device_code !== 'string') {
    logger.error('device code refused', { status, error: body.error });
    throw new HttpsError('unavailable', 'Google did not give a code. Try again.');
  }
  const expiresIn = Number(body.expires_in ?? 1800);
  const interval = Math.max(5, Number(body.interval ?? 5));
  await links().doc(uid).set({
    deviceCode: body.device_code,
    interval,
    expiresAt: Timestamp.fromMillis(Date.now() + expiresIn * 1000),
  });
  return {
    userCode: String(body.user_code ?? ''),
    // Shown as Google sends it, never assumed: the page is google.com/device today.
    verificationUrl: String(body.verification_url ?? body.verification_uri ?? ''),
    interval,
    expiresIn,
  };
}

export type PollStatus = 'pending' | 'linked' | 'denied' | 'expired';

/** The television asks whether the code was approved yet, every `interval` seconds. */
export async function pollDeviceLink(uid: string, tv: OAuthClient): Promise<PollStatus> {
  const ref = links().doc(uid);
  const snap = await ref.get();
  const link = snap.data() as { deviceCode: string; expiresAt: Timestamp } | undefined;
  if (!link || link.expiresAt.toMillis() < Date.now()) {
    await ref.delete().catch(() => undefined);
    return 'expired';
  }
  const { status, body } = await post(TOKEN_URL, {
    client_id: tv.id,
    client_secret: tv.secret,
    device_code: link.deviceCode,
    grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
  });
  if (status === 200 && typeof body.refresh_token === 'string') {
    await saveToken(uid, body.refresh_token, 'tv', String(body.scope ?? ''));
    await ref.delete();
    return 'linked';
  }
  const error = String(body.error ?? '');
  if (error === 'authorization_pending' || error === 'slow_down') return 'pending';
  await ref.delete().catch(() => undefined);
  if (error === 'access_denied') return 'denied';
  return 'expired';
}

/** The phone hands over the one-time code its authorization returned. */
export async function exchangeServerCode(uid: string, code: string, web: OAuthClient): Promise<void> {
  const { status, body } = await post(TOKEN_URL, {
    client_id: web.id,
    client_secret: web.secret,
    code,
    grant_type: 'authorization_code',
    // A server code from Android is exchanged with an empty redirect address.
    redirect_uri: '',
  });
  if (status !== 200 || typeof body.refresh_token !== 'string') {
    logger.warn('server code refused', { status, error: body.error });
    throw new HttpsError('invalid-argument', 'The authorization could not be completed. Try again.');
  }
  await saveToken(uid, body.refresh_token, 'web', String(body.scope ?? ''));
}

async function saveToken(uid: string, refreshToken: string, client: 'tv' | 'web', scope: string): Promise<void> {
  if (scope && !scope.split(' ').includes(SCOPE)) {
    throw new HttpsError('permission-denied', 'Read access to YouTube was not granted.');
  }
  await tokens().doc(uid).set({ refreshToken, client, linkedAt: Timestamp.now() });
}

export async function isLinked(uid: string): Promise<boolean> {
  return (await tokens().doc(uid).get()).exists;
}

/** A fresh access token for the account, or null when the link was revoked (the token is then forgotten). */
export async function accessTokenFor(uid: string, clients: { tv: OAuthClient; web: OAuthClient }): Promise<string | null> {
  const snap = await tokens().doc(uid).get();
  const t = snap.data() as { refreshToken: string; client: 'tv' | 'web' } | undefined;
  if (!t) return null;
  const c = t.client === 'tv' ? clients.tv : clients.web;
  const { status, body } = await post(TOKEN_URL, { client_id: c.id, client_secret: c.secret, refresh_token: t.refreshToken, grant_type: 'refresh_token' });
  if (status === 200 && typeof body.access_token === 'string') return body.access_token;
  if (body.error === 'invalid_grant') {
    logger.info('youtube link revoked', { uid });
    await tokens().doc(uid).delete();
    return null;
  }
  throw new HttpsError('unavailable', 'Google could not be reached. Try again.');
}

/** Disconnects: revokes the token at Google, then forgets it. */
export async function unlink(uid: string): Promise<void> {
  const snap = await tokens().doc(uid).get();
  const t = snap.data() as { refreshToken?: string } | undefined;
  if (t?.refreshToken) await post(REVOKE_URL, { token: t.refreshToken }).catch(() => undefined);
  await tokens().doc(uid).delete();
  await getFirestore().collection('ytSubs').doc(uid).delete();
  await links().doc(uid).delete().catch(() => undefined);
}
