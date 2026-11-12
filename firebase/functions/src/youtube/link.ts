import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { HttpsError } from 'firebase-functions/v2/https';
import { ytGetAsUser } from './api';

/**
 * Connecting a YouTube account (ADR-061): read access (`youtube.readonly`) for the subscriptions,
 * playlists and likes, and, since ADR-069, a scope for the one thing Shidur writes — a like given to
 * a song in the music tab (`videos.rate`): `youtube.force-ssl` on a phone, `youtube` on a television. The account's refresh token is kept on
 * the server alone, in
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
/** A scope `videos.rate` takes (ADR-069), asked for by the phone: optional — without it likes stay in Shidur. */
export const RATE_SCOPE = 'https://www.googleapis.com/auth/youtube.force-ssl';
/**
 * The other scope `videos.rate` takes, asked for by the television: Google's flow for TVs accepts
 * only `youtube` and `youtube.readonly` of YouTube's scopes, so `youtube.force-ssl` is refused there
 * ("OAuth 2.0 for TV and Limited-Input Device Applications", Allowed scopes).
 */
export const MANAGE_SCOPE = 'https://www.googleapis.com/auth/youtube';
/** What the phone asks Google for. */
export const SCOPES = `${SCOPE} ${RATE_SCOPE}`;
/** What the television asks Google for. */
export const TV_SCOPES = `${SCOPE} ${MANAGE_SCOPE}`;

/** Whether a granted scope list lets Shidur give a like on the account's behalf. */
export function canRate(scope: string): boolean {
  const granted = scope.split(' ');
  return granted.includes(RATE_SCOPE) || granted.includes(MANAGE_SCOPE);
}

/** Whether a granted scope list lets Shidur read the account (any of the three does). */
export function canRead(scope: string): boolean {
  const granted = scope.split(' ');
  return granted.includes(SCOPE) || granted.includes(RATE_SCOPE) || granted.includes(MANAGE_SCOPE);
}
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
  const { status, body } = await post(DEVICE_URL, { client_id: tv.id, scope: TV_SCOPES });
  if (status !== 200 || typeof body.device_code !== 'string') {
    logger.error('device code refused', { status, error: body.error, description: body.error_description });
    // With a reason, so the app does not take Google's refusal for a lost connection.
    throw new HttpsError('unavailable', 'Google did not give a code. Try again.', { reason: 'code-refused' });
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
    await saveToken(uid, body.refresh_token, 'tv', String(body.scope ?? ''), typeof body.access_token === 'string' ? body.access_token : null);
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
  await saveToken(uid, body.refresh_token, 'web', String(body.scope ?? ''), typeof body.access_token === 'string' ? body.access_token : null);
}

/** The YouTube channel a connection belongs to, shown as "connected as …" (ADR-069). */
export interface LinkedChannel {
  id: string;
  title: string;
  thumb: string | null;
}

/** The channel in a `channels.list mine=true` answer, or null when the Google account has none. */
export function channelOf(body: unknown): LinkedChannel | null {
  const item = (body as { items?: unknown[] } | null)?.items?.[0] as
    | { id?: unknown; snippet?: { title?: unknown; thumbnails?: Record<string, { url?: unknown } | undefined> } }
    | undefined;
  if (!item || typeof item.id !== 'string' || !item.id) return null;
  const t = item.snippet?.thumbnails ?? {};
  const thumb = [t.medium, t.default, t.high].map((x) => x?.url).find((u): u is string => typeof u === 'string' && u.startsWith('https://'));
  const title = typeof item.snippet?.title === 'string' ? item.snippet.title.slice(0, 200) : '';
  return { id: item.id, title, thumb: thumb ?? null };
}

/** Reads the connected channel with the connection's own access token (1 unit); null when it cannot. */
async function identify(accessToken: string): Promise<LinkedChannel | null> {
  try {
    return channelOf(await ytGetAsUser('channels', { part: 'snippet', mine: 'true', maxResults: '1' }, accessToken));
  } catch (e) {
    logger.warn('linked channel not read', { error: String(e) });
    return null;
  }
}

/** What is kept per account from a connection that is no longer the one in use. */
async function forgetCaches(uid: string): Promise<void> {
  const db = getFirestore();
  await Promise.all([db.collection('ytSubs').doc(uid).delete(), db.collection('ytmLib').doc(uid).delete()]);
}

/**
 * Whether connecting again should revoke the connection it replaces. Google's grant is one per
 * account and client, and revoking a refresh token revokes that grant, so the old token is revoked
 * only when it is surely another grant: another client, or another channel.
 */
export function revokesOld(old: { client?: string; channel?: { id?: string } | null }, client: string, channel: LinkedChannel | null): boolean {
  if (old.client && old.client !== client) return true;
  return !!old.channel?.id && !!channel?.id && old.channel.id !== channel.id;
}

async function saveToken(uid: string, refreshToken: string, client: 'tv' | 'web', scope: string, accessToken: string | null): Promise<void> {
  if (scope && !canRead(scope)) {
    throw new HttpsError('permission-denied', 'Read access to YouTube was not granted.');
  }
  const ref = tokens().doc(uid);
  const old = (await ref.get()).data() as { refreshToken?: string; client?: string; channel?: LinkedChannel | null } | undefined;
  const channel = accessToken ? await identify(accessToken) : null;
  // Connecting again replaces the connection ("החלפת חשבון", "חיבור מחדש"): what was kept for the
  // one before is forgotten, and its token revoked when it is another grant.
  if (old?.refreshToken && old.refreshToken !== refreshToken) {
    if (revokesOld(old, client, channel)) await post(REVOKE_URL, { token: old.refreshToken }).catch(() => undefined);
    await forgetCaches(uid);
  }
  // The scope is kept so a connection made before ADR-069, or one that declined the write scope,
  // is known to be unable to give likes, and the app can offer to connect again.
  await ref.set({ refreshToken, client, scope, channel, channelReadAt: Timestamp.now(), linkedAt: Timestamp.now() });
}

export async function isLinked(uid: string): Promise<boolean> {
  return (await tokens().doc(uid).get()).exists;
}

/** Whether the account is connected with the scope that gives likes. */
export async function linkCanRate(uid: string): Promise<boolean> {
  const t = (await tokens().doc(uid).get()).data() as { scope?: string } | undefined;
  return !!t && canRate(String(t.scope ?? ''));
}

/**
 * The connection as the app shows it: whether there is one, whether it gives likes, and the
 * channel it belongs to. A connection made before the channel was kept is read once, here.
 */
export async function linkInfo(uid: string, clients: { tv: OAuthClient; web: OAuthClient }): Promise<{ linked: boolean; canRate: boolean; channel: LinkedChannel | null }> {
  const ref = tokens().doc(uid);
  const t = (await ref.get()).data() as { scope?: string; channel?: LinkedChannel | null; channelReadAt?: Timestamp } | undefined;
  if (!t) return { linked: false, canRate: false, channel: null };
  let channel = t.channel ?? null;
  if (!t.channelReadAt) {
    const token = await accessTokenFor(uid, clients).catch(() => null);
    if (token) {
      channel = await identify(token);
      await ref.set({ channel, channelReadAt: Timestamp.now() }, { merge: true });
    } else if (!(await ref.get()).exists) {
      return { linked: false, canRate: false, channel: null };
    }
  }
  return { linked: true, canRate: canRate(String(t.scope ?? '')), channel };
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
  await forgetCaches(uid);
  await links().doc(uid).delete().catch(() => undefined);
}
