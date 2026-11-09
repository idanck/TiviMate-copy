import { initializeApp, type FirebaseOptions } from 'firebase/app';
import {
  connectAuthEmulator,
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signOut,
  type Auth,
  type User,
} from 'firebase/auth';
import { connectFunctionsEmulator, getFunctions, httpsCallable, type Functions } from 'firebase/functions';

/** Same region as `functions/src/config.ts`; a callable function is addressed by it. */
export const REGION = 'me-west1';

export interface Session {
  auth: Auth;
  functions: Functions;
}

/**
 * Firebase Hosting serves the project's web configuration at a reserved address, so nothing about
 * the project is written into this repository (ADR-059). Sign-in runs on this site's own domain:
 * its popup then never depends on third-party storage, which browsers increasingly block.
 */
export async function start(): Promise<Session> {
  const local = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  const options: FirebaseOptions = local
    ? { apiKey: 'demo', projectId: 'demo-shidur', appId: 'demo' }
    : await (await fetch('/__/firebase/init.json')).json();
  if (!local) options.authDomain = location.host;
  const app = initializeApp(options);
  const auth = getAuth(app);
  auth.languageCode = 'he';
  const functions = getFunctions(app, REGION);
  if (local) {
    connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  }
  return { auth, functions };
}

export function watchUser(session: Session, onChange: (user: User | null) => void): void {
  onAuthStateChanged(session.auth, onChange);
}

export async function signInWithGoogle(session: Session): Promise<void> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await signInWithPopup(session.auth, provider);
}

export function leave(session: Session): Promise<void> {
  return signOut(session.auth);
}

export async function call<T>(session: Session, name: string, data: unknown): Promise<T> {
  const result = await httpsCallable(session.functions, name)(data);
  return result.data as T;
}

/** A sentence for a failed call, in Hebrew, from the callable error's code. */
export function describeError(e: unknown): string {
  const code = String((e as { code?: string })?.code ?? '');
  if (code.endsWith('not-found')) return 'הקוד לא נמצא או שפג תוקפו. בקש קוד חדש בטלוויזיה.';
  if (code.endsWith('failed-precondition')) return 'הקוד הזה כבר שומש. בקש קוד חדש בטלוויזיה.';
  if (code.endsWith('resource-exhausted')) return 'יותר מדי ניסיונות. נסה שוב בעוד כמה דקות.';
  if (code.endsWith('permission-denied')) return 'צריך להתחבר עם חשבון Google.';
  if (code.endsWith('invalid-argument')) return 'זה לא נראה כמו קוד. הקוד בטלוויזיה בנוי משמונה תווים.';
  if (code.endsWith('popup-closed-by-user') || code.endsWith('cancelled-popup-request')) return 'ההתחברות בוטלה.';
  if (code.endsWith('popup-blocked')) return 'הדפדפן חסם את חלון ההתחברות. אפשר חלונות קופצים לאתר הזה ונסה שוב.';
  if (code.endsWith('network-request-failed') || code.endsWith('unavailable')) return 'אין חיבור לרשת. נסה שוב.';
  return 'משהו השתבש. נסה שוב.';
}

export function $(id: string): HTMLElement {
  const el = document.getElementById(id);
  if (!el) throw new Error(`#${id} is missing`);
  return el;
}

export function show(id: string, visible: boolean): void {
  $(id).hidden = !visible;
}
