import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  getAuth,
  GoogleAuthProvider,
  signInWithCredential,
  signInWithCustomToken,
  type Auth,
} from 'firebase/auth';
import { connectFirestoreEmulator, doc, getDoc, getFirestore } from 'firebase/firestore';
import { connectFunctionsEmulator, getFunctions, httpsCallable, type Functions } from 'firebase/functions';

/**
 * The whole television pairing against the emulators (ADR-059): the television asks for a code,
 * the phone signs in with Google and approves it, the television claims a custom token and signs
 * in as the same account. Then the profile ensureProfile makes, and deleting the account.
 */
const PROJECT = 'demo-shidur';
const REGION = 'me-west1';

interface Client {
  app: FirebaseApp;
  auth: Auth;
  functions: Functions;
}

function client(name: string): Client {
  const app = initializeApp({ apiKey: 'demo', projectId: PROJECT, appId: 'demo' }, name);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  return { app, auth, functions };
}

async function call<T>(c: Client, name: string, data: unknown): Promise<T> {
  return (await httpsCallable(c.functions, name)(data)).data as T;
}

async function code(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'ok';
  } catch (e) {
    return String((e as { code?: string }).code);
  }
}

const tv = client('tv');
const phone = client('phone');

beforeAll(async () => {
  const google = GoogleAuthProvider.credential(
    JSON.stringify({ sub: 'google-sub-1', email: 'viewer@example.com', email_verified: true, name: 'Viewer' }),
  );
  await signInWithCredential(phone.auth, google);
});

afterAll(async () => {
  await deleteApp(tv.app);
  await deleteApp(phone.app);
});

interface Pairing {
  code: string;
  display: string;
  secret: string;
  expiresAt: number;
  url: string;
}

describe('television pairing', () => {
  let pairing: Pairing;

  it('hands the television a code, a secret and the approval address', async () => {
    pairing = await call<Pairing>(tv, 'createPairing', {});
    expect(pairing.code).toMatch(/^[A-HJ-NP-Z2-9]{8}$/);
    expect(pairing.display).toBe(`${pairing.code.slice(0, 4)}-${pairing.code.slice(4)}`);
    expect(pairing.secret.length).toBeGreaterThan(40);
    expect(pairing.expiresAt).toBeGreaterThan(Date.now());
    expect(pairing.url).toContain(`/pair?code=${pairing.display}`);
  });

  it('is pending until approved, and says nothing to a wrong secret', async () => {
    expect(await call(tv, 'claimPairing', { code: pairing.code, secret: pairing.secret })).toEqual({ status: 'pending' });
    expect(await call(tv, 'claimPairing', { code: pairing.code, secret: 'wrong' })).toEqual({ status: 'expired' });
  });

  it('cannot be approved without signing in with Google', async () => {
    expect(await code(call(tv, 'approvePairing', { code: pairing.code }))).toBe('functions/unauthenticated');
  });

  it('refuses something that is not a code', async () => {
    expect(await code(call(phone, 'approvePairing', { code: 'nope' }))).toBe('functions/invalid-argument');
    expect(await code(call(phone, 'approvePairing', { code: 'ZZZZ-ZZZZ' }))).toBe('functions/not-found');
  });

  it('is approved once, from the phone, with the code as the television shows it', async () => {
    expect(await call(phone, 'approvePairing', { code: pairing.display.toLowerCase() })).toEqual({ ok: true });
    expect(await code(call(phone, 'approvePairing', { code: pairing.code }))).toBe('functions/failed-precondition');
  });

  it('signs the television in as the same account, once', async () => {
    const claimed = await call<{ status: string; token?: string }>(tv, 'claimPairing', { code: pairing.code, secret: pairing.secret });
    expect(claimed.status).toBe('approved');
    const signedIn = await signInWithCustomToken(tv.auth, claimed.token!);
    expect(signedIn.user.uid).toBe(phone.auth.currentUser!.uid);
    expect(await call(tv, 'claimPairing', { code: pairing.code, secret: pairing.secret })).toEqual({ status: 'expired' });
  });

  it('does not let a television approve another television', async () => {
    const next = await call<Pairing>(tv, 'createPairing', {});
    expect(await code(call(tv, 'approvePairing', { code: next.code }))).toBe('functions/permission-denied');
  });
});

describe('signing out everywhere', () => {
  // An account and a television of their own, so the tests around these keep their sessions.
  const phone2 = client('phone2');
  const tv2 = client('tv2');

  beforeAll(async () => {
    const google = GoogleAuthProvider.credential(
      JSON.stringify({ sub: 'google-sub-2', email: 'other@example.com', email_verified: true, name: 'Other' }),
    );
    await signInWithCredential(phone2.auth, google);
    const pairing = await call<Pairing>(tv2, 'createPairing', {});
    await call(phone2, 'approvePairing', { code: pairing.code });
    const claimed = await call<{ token?: string }>(tv2, 'claimPairing', { code: pairing.code, secret: pairing.secret });
    await signInWithCustomToken(tv2.auth, claimed.token!);
  });

  afterAll(async () => {
    await deleteApp(tv2.app);
    await deleteApp(phone2.app);
  });

  it('is for a person signed in with Google, not for a paired television', async () => {
    expect(await code(call(tv2, 'signOutEverywhere', {}))).toBe('functions/permission-denied');
    expect(await tv2.auth.currentUser!.getIdToken(true)).toBeTruthy();
  });

  // The Auth emulator records a revocation but goes on refreshing revoked tokens, so what is
  // checked here is the record: the account's sessions are valid only from now on.
  it('revokes every session of the account', async () => {
    const uid = phone2.auth.currentUser!.uid;
    const validSince = async () => {
      const lookup = await fetch(
        `http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts:lookup`,
        { method: 'POST', headers: { Authorization: 'Bearer owner', 'Content-Type': 'application/json' }, body: JSON.stringify({ localId: [uid] }) },
      );
      return Number((await lookup.json()).users?.[0]?.validSince ?? 0);
    };
    const before = await validSince();
    await new Promise((done) => setTimeout(done, 1100));
    const asked = Math.floor(Date.now() / 1000);
    expect(await call(phone2, 'signOutEverywhere', {})).toEqual({ ok: true });
    const after = await validSince();
    expect(after).toBeGreaterThan(before);
    expect(after).toBeGreaterThanOrEqual(asked);
  });
});

describe('the account', () => {
  // Read and written as the emulators' owner, which neither the rules nor a signed-in user limit.
  const owner = { Authorization: 'Bearer owner', 'Content-Type': 'application/json' };
  const profileUrl = (uid: string) =>
    `http://127.0.0.1:8080/v1/projects/${PROJECT}/databases/(default)/documents/users/${uid}`;

  async function profile(c: Client): Promise<Record<string, unknown> | undefined> {
    const db = getFirestore(c.app);
    return (await getDoc(doc(db, 'users', c.auth.currentUser!.uid))).data();
  }

  beforeAll(() => {
    connectFirestoreEmulator(getFirestore(phone.app), '127.0.0.1', 8080);
  });

  it('has no profile until ensureProfile is called', async () => {
    expect(await profile(phone)).toBeUndefined();
  });

  it('gets a profile with every entitlement off', async () => {
    expect(await call(phone, 'ensureProfile', {})).toEqual({ created: true });
    const data = await profile(phone);
    expect(data?.entitlements).toEqual({ premium: false, web: false });
    expect(data?.displayName).toBe('Viewer');
  });

  it('leaves an existing profile alone, entitlements the owner granted included', async () => {
    const uid = phone.auth.currentUser!.uid;
    const granted = await fetch(`${profileUrl(uid)}?updateMask.fieldPaths=entitlements`, {
      method: 'PATCH',
      headers: owner,
      body: JSON.stringify({
        fields: {
          entitlements: { mapValue: { fields: { premium: { booleanValue: true }, web: { booleanValue: true } } } },
        },
      }),
    });
    expect(granted.status).toBe(200);
    expect(await call(phone, 'ensureProfile', {})).toEqual({ created: false });
    // The television, signed in by pairing as the same account, does not reset it either.
    expect(await call(tv, 'ensureProfile', {})).toEqual({ created: false });
    const data = await profile(phone);
    expect(data?.entitlements).toEqual({ premium: true, web: true });
    expect(data?.displayName).toBe('Viewer');
  });

  it('refuses a caller who is not signed in', async () => {
    const stranger = client('stranger');
    try {
      expect(await code(call(stranger, 'ensureProfile', {}))).toBe('functions/unauthenticated');
    } finally {
      await deleteApp(stranger.app);
    }
  });

  it('is deleted, documents and all', async () => {
    const uid = phone.auth.currentUser!.uid;
    expect(await call(phone, 'deleteAccount', {})).toEqual({ ok: true });
    const stored = await fetch(profileUrl(uid), { headers: owner });
    expect(stored.status).toBe(404);
    const lookup = await fetch(
      `http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts:lookup`,
      { method: 'POST', headers: owner, body: JSON.stringify({ localId: [uid] }) },
    );
    expect((await lookup.json()).users ?? []).toHaveLength(0);
  });
});
