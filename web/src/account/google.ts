import { initializeApp, type FirebaseOptions } from 'firebase/app';
import {
  getAuth,
  getRedirectResult,
  GoogleAuthProvider,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  type Auth,
  type User,
} from 'firebase/auth';
import { getFunctions, httpsCallable, type Functions } from 'firebase/functions';

/**
 * Signing in with a Google account, in the server's Firebase mode (ADR-059, stage 5).
 *
 * Loaded only in that mode (`App.tsx` imports it on demand), so a server with a password or no
 * guard at all never sends the browser Firebase.
 *
 * The server hands the project's web configuration out in `/api/session` — nothing about the
 * project is in this repository — and relays Firebase Auth's own sign-in pages under `/__/auth/`,
 * so `authDomain` is this page's own host. Signing in then never needs third-party storage, which
 * Safari, iOS and the current Chrome and Firefox no longer give a page.
 *
 * The session the server keeps is made from the account's ID token and lasts as long as that
 * token. It is renewed every `renewMinutes` (`keepSession`), and every renewal checks the
 * subscription again.
 */

/** What `/api/session` carries for this mode. None of it is secret. */
export interface WebConfig extends FirebaseOptions {
  apiKey: string;
  appId: string;
  projectId: string;
  /** Where the project's Functions run, `ensureProfile` among them. */
  region: string;
  renewMinutes: number;
}

/** How a hand-over of a token to the server ended. */
export type Outcome = 'ok' | 'not-entitled' | 'invalid' | 'locked' | 'unavailable' | 'offline';

interface Session {
  auth: Auth;
  functions: Functions;
  renewMs: number;
}

let session: Session | undefined;

/** Starts Firebase once, with this page's host as the sign-in domain. */
export function start(config: WebConfig): Session {
  if (session) return session;
  const { region, renewMinutes, ...options } = config;
  const app = initializeApp({ ...options, authDomain: location.host });
  const auth = getAuth(app);
  auth.languageCode = 'he';
  session = { auth, functions: getFunctions(app, region), renewMs: Math.max(1, renewMinutes) * 60_000 };
  return session;
}

/** Set while a sign-in has left the page for Google, so only then is its result waited for. */
const REDIRECTING = 'shidur.signInRedirect';

/**
 * The account Firebase already holds in this browser, if any, once it has said so. A sign-in that
 * came back by redirect is finished here too; its error, if it failed, is thrown. The redirect's
 * result is asked for only after this page sent one: asking loads Google's frame, which an
 * ordinary start has no need to wait for.
 */
export async function signedInUser(config: WebConfig): Promise<User | null> {
  const { auth } = start(config);
  if (readFlag()) {
    clearFlag();
    await getRedirectResult(auth);
  }
  await auth.authStateReady();
  return auth.currentUser;
}

async function redirect(auth: Auth, provider: GoogleAuthProvider): Promise<null> {
  try {
    sessionStorage.setItem(REDIRECTING, '1');
  } catch {
    // No session storage: the result is still picked up by `authStateReady`, only an error is lost.
  }
  await signInWithRedirect(auth, provider);
  return null;
}

function readFlag(): boolean {
  try {
    return sessionStorage.getItem(REDIRECTING) === '1';
  } catch {
    return false;
  }
}

function clearFlag(): void {
  try {
    sessionStorage.removeItem(REDIRECTING);
  } catch {
    // Nothing to clear.
  }
}

/**
 * Google's account chooser: a popup where the browser allows one, otherwise — the popup blocked,
 * or an app installed on an iPhone's home screen, where a popup does not come back to the app —
 * the whole page goes to Google and returns (`signedInUser` picks the result up).
 */
export async function signInWithGoogle(config: WebConfig): Promise<User | null> {
  const { auth } = start(config);
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  if (installedOnIos()) return redirect(auth, provider);
  try {
    return (await signInWithPopup(auth, provider)).user;
  } catch (e) {
    if (codeOf(e).endsWith('popup-blocked')) return redirect(auth, provider);
    throw e;
  }
}

/**
 * Makes sure the account has its profile (`ensureProfile`, as the app does after every sign-in),
 * then hands the server a fresh ID token for a session. A failed `ensureProfile` does not stop the
 * hand-over: an account that has a profile already is let in all the same, and the next sign-in
 * asks again.
 */
export async function establish(config: WebConfig, user: User, { profile }: { profile: boolean }): Promise<Outcome> {
  const { functions } = start(config);
  if (profile) {
    try {
      await httpsCallable(functions, 'ensureProfile')({});
    } catch (e) {
      console.warn('ensureProfile failed', codeOf(e));
    }
  }
  let idToken: string;
  try {
    idToken = await user.getIdToken(true);
  } catch {
    return 'offline';
  }
  return handOver(idToken);
}

/** `POST /api/login` with an ID token; what the server made of it. */
async function handOver(idToken: string): Promise<Outcome> {
  let res: Response;
  try {
    res = await fetch('/api/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ idToken }),
    });
  } catch {
    return 'offline';
  }
  if (res.ok) return 'ok';
  if (res.status === 403) return 'not-entitled';
  if (res.status === 401 || res.status === 400) return 'invalid';
  if (res.status === 429) return 'locked';
  return 'unavailable';
}

/**
 * Renews the server's session every `renewMinutes` while the page is open, and as soon as it is
 * looked at again after a while away (a laptop asleep past the session's hour). [onLost] is told
 * once when the account is gone or no longer subscribed; a renewal that fails for want of a
 * network is tried again at the next turn.
 */
export function keepSession(config: WebConfig, onLost: (outcome: 'not-entitled' | 'invalid') => void): () => void {
  const { auth, renewMs } = start(config);
  let last = Date.now();
  let stopped = false;
  const renew = async () => {
    if (stopped) return;
    last = Date.now();
    await auth.authStateReady();
    const user = auth.currentUser;
    const outcome = user ? await establish(config, user, { profile: false }) : 'invalid';
    if (!stopped && (outcome === 'not-entitled' || outcome === 'invalid')) {
      stopped = true;
      onLost(outcome);
    }
  };
  const timer = setInterval(() => void renew(), renewMs);
  const onVisible = () => {
    if (document.visibilityState === 'visible' && Date.now() - last > 5 * 60_000) void renew();
  };
  document.addEventListener('visibilitychange', onVisible);
  return () => {
    stopped = true;
    clearInterval(timer);
    document.removeEventListener('visibilitychange', onVisible);
  };
}

/** Out of the Google account in this browser, and out of the server's session. */
export async function signOutEverywhere(config: WebConfig): Promise<void> {
  try {
    await signOut(start(config).auth);
  } finally {
    await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
  }
}

/** A sentence for a failed sign-in, in Hebrew, from Firebase's error code. */
export function describeSignInError(e: unknown): string {
  const code = codeOf(e);
  if (code.endsWith('popup-closed-by-user') || code.endsWith('cancelled-popup-request') || code.endsWith('user-cancelled')) {
    return 'ההתחברות בוטלה.';
  }
  if (code.endsWith('popup-blocked')) return 'הדפדפן חסם את חלון ההתחברות. אפשר חלונות קופצים לאתר הזה ונסה שוב.';
  if (code.endsWith('network-request-failed')) return 'אין חיבור לרשת. נסה שוב.';
  if (code.endsWith('unauthorized-domain')) return 'הכתובת של האתר הזה לא רשומה בפרויקט Firebase.';
  if (code.endsWith('web-storage-unsupported')) return 'הדפדפן חוסם אחסון לאתר הזה, וההתחברות צריכה אותו.';
  return 'ההתחברות לא הצליחה. נסה שוב.';
}

function codeOf(e: unknown): string {
  return String((e as { code?: string })?.code ?? '');
}

/** An app on an iPhone's or iPad's home screen, where a popup opens outside it and does not return. */
function installedOnIos(): boolean {
  const standalone = (navigator as Navigator & { standalone?: boolean }).standalone === true ||
    matchMedia('(display-mode: standalone)').matches;
  return standalone && /iPad|iPhone|iPod/.test(navigator.userAgent);
}
