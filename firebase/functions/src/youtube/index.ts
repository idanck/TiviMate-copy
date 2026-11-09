import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { MAX_INSTANCES, REGION, YT_LIMITS } from '../config';
import { clientIp, enforce } from '../limits';
import { QuotaError, YtApiError, ytGetAsUser } from './api';
import { channelIdForHandle, channelStates, SAVED_TTL_MS, SUBSCRIBED_TTL_MS } from './channels';
import { accessTokenFor, configured, exchangeServerCode, isLinked, pollDeviceLink, startDeviceLink, unlink, type OAuthClient } from './link';
import { channelIds, mergeFeed, normalizeQuery, parseChannelInput, searchKind, searchParams } from './model';
import { search } from './search';

/**
 * The YouTube callables (ADR-061). Every one runs in me-west1 like the rest, and none hands the
 * app a key or a token: the app sends channel ids and phrases, and gets back what to draw.
 *
 * Secrets (Secret Manager, set once by the owner; docs/13-youtube-setup.md):
 * - `YOUTUBE_API_KEY`: a key restricted to the YouTube Data API v3.
 * - `YOUTUBE_TV_CLIENT_ID` / `YOUTUBE_TV_CLIENT_SECRET`: the OAuth client of type "TVs and Limited
 *   Input devices", for connecting an account on a television.
 * - `GOOGLE_WEB_CLIENT_ID` / `GOOGLE_WEB_CLIENT_SECRET`: the project's web client, which the phone's
 *   authorization returns a server code for.
 * A client secret set to "unset" means that route is not configured yet; the rest still works.
 */
const API_KEY = defineSecret('YOUTUBE_API_KEY');
const TV_ID = defineSecret('YOUTUBE_TV_CLIENT_ID');
const TV_SECRET = defineSecret('YOUTUBE_TV_CLIENT_SECRET');
const WEB_ID = defineSecret('GOOGLE_WEB_CLIENT_ID');
const WEB_SECRET = defineSecret('GOOGLE_WEB_CLIENT_SECRET');

const base = { region: REGION, maxInstances: MAX_INSTANCES };
const tv = (): OAuthClient => ({ id: TV_ID.value(), secret: TV_SECRET.value() });
const web = (): OAuthClient => ({ id: WEB_ID.value(), secret: WEB_SECRET.value() });

/** Who is asking: the account, or the address of a device that has not signed in (sign-in is behind a switch, ADR-059). */
function caller(request: CallableRequest): string {
  return request.auth?.uid ?? `ip:${clientIp(request.rawRequest)}`;
}

function signedIn(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first.');
  return uid;
}

/** What the app is told when YouTube refuses, so it can show the right message (TvYouTubeStates). */
function translate(e: unknown): never {
  if (e instanceof QuotaError) throw new HttpsError('resource-exhausted', 'YouTube quota exhausted.', { reason: e.bucket === 'search' ? 'search-quota' : 'quota' });
  if (e instanceof YtApiError) throw new HttpsError('unavailable', 'YouTube refused the request.', { reason: e.reason });
  throw e;
}

/** Channels and what is on them now: `{ ids: string[] }` (up to 50) → `{ channels, stale }`. */
export const ytChannels = onCall({ ...base, secrets: [API_KEY] }, async (request) => {
  const ids = channelIds(request.data?.ids);
  if (ids.length === 0) return { channels: [], stale: false };
  await enforce('yt_channels', caller(request), YT_LIMITS.channels.max, YT_LIMITS.channels.windowMs);
  try {
    const { states, stale } = await channelStates(ids, API_KEY.value(), SAVED_TTL_MS);
    return { channels: states, stale };
  } catch (e) {
    translate(e);
  }
});

/** A channel from what a person typed or pasted (an ID, @handle or youtube.com address): `{ input }` → `{ channel }`. */
export const ytResolve = onCall({ ...base, secrets: [API_KEY] }, async (request) => {
  const ref = parseChannelInput(request.data?.input);
  if (!ref) throw new HttpsError('invalid-argument', 'Not a YouTube channel.', { reason: 'not-a-channel' });
  await enforce('yt_resolve', caller(request), YT_LIMITS.resolve.max, YT_LIMITS.resolve.windowMs);
  try {
    const id = ref.kind === 'id' ? ref.value : await channelIdForHandle(ref.value, API_KEY.value());
    if (!id) throw new HttpsError('not-found', 'No such channel.', { reason: 'not-found' });
    const { states } = await channelStates([id], API_KEY.value(), SAVED_TTL_MS);
    if (states.length === 0) throw new HttpsError('not-found', 'No such channel.', { reason: 'not-found' });
    return { channel: states[0] };
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    translate(e);
  }
});

/** Search: `{ q, kind: 'all' | 'channel' | 'live' | 'video' }` → `{ channels, videos, cached }`. */
export const ytSearch = onCall({ ...base, secrets: [API_KEY] }, async (request) => {
  const q = normalizeQuery(request.data?.q);
  if (!q) throw new HttpsError('invalid-argument', 'Type at least two letters.');
  const kind = searchKind(request.data?.kind);
  await enforce('yt_search', caller(request), YT_LIMITS.search.max, YT_LIMITS.search.windowMs);
  try {
    return await search(q, kind, searchParams(q, kind), API_KEY.value());
  } catch (e) {
    translate(e);
  }
});

/** Whether this account has YouTube connected, and whether each route can be offered at all. */
export const ytLinkStatus = onCall({ ...base, secrets: [TV_ID, TV_SECRET, WEB_ID, WEB_SECRET] }, async (request) => {
  const uid = signedIn(request);
  return { linked: await isLinked(uid), tvReady: configured(tv()), phoneReady: configured(web()) };
});

/** The television starts connecting: `{}` → `{ userCode, verificationUrl, interval, expiresIn }`. */
export const ytLinkStart = onCall({ ...base, secrets: [TV_ID, TV_SECRET] }, async (request) => {
  const uid = signedIn(request);
  if (!configured(tv())) throw new HttpsError('failed-precondition', 'Not configured.', { reason: 'not-configured' });
  await enforce('yt_link', uid, YT_LIMITS.link.max, YT_LIMITS.link.windowMs);
  return startDeviceLink(uid, tv());
});

/** The television asks every few seconds: `{}` → `{ status: 'pending' | 'linked' | 'denied' | 'expired' }`. */
export const ytLinkPoll = onCall({ ...base, secrets: [TV_ID, TV_SECRET] }, async (request) => {
  const uid = signedIn(request);
  if (!configured(tv())) throw new HttpsError('failed-precondition', 'Not configured.', { reason: 'not-configured' });
  await enforce('yt_poll', uid, YT_LIMITS.poll.max, YT_LIMITS.poll.windowMs);
  return { status: await pollDeviceLink(uid, tv()) };
});

/** The phone hands over its one-time server code: `{ code }` → `{ linked: true }`. */
export const ytLinkCode = onCall({ ...base, secrets: [WEB_ID, WEB_SECRET] }, async (request) => {
  const uid = signedIn(request);
  if (!configured(web())) throw new HttpsError('failed-precondition', 'Not configured.', { reason: 'not-configured' });
  const code = request.data?.code;
  if (typeof code !== 'string' || code.length < 10 || code.length > 512) throw new HttpsError('invalid-argument', 'No code.');
  await enforce('yt_link', uid, YT_LIMITS.link.max, YT_LIMITS.link.windowMs);
  await exchangeServerCode(uid, code, web());
  return { linked: true };
});

/** Disconnects the account's YouTube: `{}` → `{ linked: false }`. */
export const ytUnlink = onCall(base, async (request) => {
  await unlink(signedIn(request));
  return { linked: false };
});

/** How long the list of an account's subscriptions is kept before it is asked for again. */
const SUBS_TTL_MS = 6 * 60 * 60 * 1000;
/** Subscriptions read per account: the first page, by relevance. */
const SUBS_MAX = 50;

/**
 * The account's subscriptions and their newest videos: `{}` → `{ linked, channels, feed, stale }`.
 * The list of channels is one `subscriptions.list` call (1 unit) every six hours; their videos come
 * from the shared channel cache, an hour old at most.
 */
export const ytSubscriptions = onCall({ ...base, secrets: [API_KEY, TV_ID, TV_SECRET, WEB_ID, WEB_SECRET] }, async (request) => {
  const uid = signedIn(request);
  await enforce('yt_subs', uid, YT_LIMITS.subscriptions.max, YT_LIMITS.subscriptions.windowMs);
  const ref = getFirestore().collection('ytSubs').doc(uid);
  try {
    let list = (await ref.get()).data() as { channels: { id: string; title: string; thumb: string | null }[]; at: number } | undefined;
    if (!list || Date.now() - list.at > SUBS_TTL_MS) {
      const token = await accessTokenFor(uid, { tv: tv(), web: web() });
      if (!token) return { linked: false, channels: [], feed: [], stale: false };
      const res = await ytGetAsUser('subscriptions', { part: 'snippet', mine: 'true', maxResults: String(SUBS_MAX), order: 'relevance' }, token);
      const channels = (res.items ?? [])
        .map((i: { snippet?: { title?: string; resourceId?: { channelId?: string }; thumbnails?: { medium?: { url?: string }; default?: { url?: string } } } }) => ({
          id: i.snippet?.resourceId?.channelId ?? '',
          title: String(i.snippet?.title ?? ''),
          thumb: i.snippet?.thumbnails?.medium?.url ?? i.snippet?.thumbnails?.default?.url ?? null,
        }))
        .filter((c: { id: string }) => c.id);
      list = { channels, at: Date.now() };
      await ref.set({ ...list, expiresAt: Timestamp.fromMillis(Date.now() + 7 * 86_400_000) });
    }
    const { states, stale } = await channelStates(list.channels.map((c) => c.id), API_KEY.value(), SUBSCRIBED_TTL_MS);
    return { linked: true, channels: list.channels, feed: mergeFeed(states), stale };
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    translate(e);
  }
});
