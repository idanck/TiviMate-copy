import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, setDoc, updateDoc } from 'firebase/firestore';

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-shidur-rules',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8080 },
  });
});

afterAll(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users/alice'), { displayName: 'Alice', entitlements: { premium: false, web: false } });
    await setDoc(doc(db, 'users/bob'), { displayName: 'Bob', entitlements: { premium: false, web: false } });
    await setDoc(doc(db, 'config/app'), { requireSignIn: false });
    await setDoc(doc(db, 'pairings/ABCDEFGH'), { status: 'pending' });
    await setDoc(doc(db, 'rateLimits/x'), { count: 1 });
  });
});

describe('users/{uid}', () => {
  it('is readable by its owner', async () => {
    await assertSucceeds(getDoc(doc(env.authenticatedContext('alice').firestore(), 'users/alice')));
  });

  it('is not readable by anybody else', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('bob').firestore(), 'users/alice')));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/alice')));
  });

  it('cannot be written by its owner, entitlements least of all', async () => {
    const db = env.authenticatedContext('alice').firestore();
    await assertFails(updateDoc(doc(db, 'users/alice'), { 'entitlements.web': true }));
    await assertFails(updateDoc(doc(db, 'users/alice'), { displayName: 'A' }));
    await assertFails(setDoc(doc(db, 'users/alice'), { entitlements: { premium: true, web: true } }));
    await assertFails(deleteDoc(doc(db, 'users/alice')));
  });

  it('cannot be created by a client', async () => {
    const db = env.authenticatedContext('carol').firestore();
    await assertFails(setDoc(doc(db, 'users/carol'), { entitlements: { premium: false, web: false } }));
  });
});

describe('config', () => {
  it('is readable before signing in', async () => {
    await assertSucceeds(getDoc(doc(env.unauthenticatedContext().firestore(), 'config/app')));
  });

  it('is not writable by anybody', async () => {
    await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), 'config/app'), { requireSignIn: true }));
    await assertFails(setDoc(doc(env.authenticatedContext('alice').firestore(), 'config/app'), { requireSignIn: true }));
  });
});

describe('server-only collections', () => {
  it('pairings are closed to clients', async () => {
    for (const ctx of [env.unauthenticatedContext(), env.authenticatedContext('alice')]) {
      await assertFails(getDoc(doc(ctx.firestore(), 'pairings/ABCDEFGH')));
      await assertFails(setDoc(doc(ctx.firestore(), 'pairings/ZZZZZZZZ'), { status: 'approved', uid: 'alice' }));
    }
  });

  it('rate limits are closed to clients', async () => {
    const db = env.authenticatedContext('alice').firestore();
    await assertFails(getDoc(doc(db, 'rateLimits/x')));
    await assertFails(deleteDoc(doc(db, 'rateLimits/x')));
  });

  it('anything else is closed', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('alice').firestore(), 'other/doc')));
  });
});

describe('YouTube (ADR-061)', () => {
  it('keeps an account its own YouTube token from no one more than from itself', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'ytTokens/alice'), { refreshToken: 'secret', client: 'tv' });
    });
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(getDoc(doc(alice, 'ytTokens/alice')));
    await assertFails(setDoc(doc(alice, 'ytTokens/alice'), { refreshToken: 'mine' }));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'ytTokens/alice')));
  });

  it('closes the caches, the counters and the waiting connections to every client', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    for (const path of ['ytChannels/UCabcdefghijklmnopqrstuv', 'ytSearch/x', 'ytQuota/2026-11-09', 'ytLinks/alice', 'ytSubs/alice']) {
      await assertFails(getDoc(doc(alice, path)));
      await assertFails(setDoc(doc(alice, path), { x: 1 }));
    }
  });
});

describe('podcasts (ADR-067)', () => {
  const show = { feedUrl: 'https://example.com/feed.xml', title: 'Show', inLibrary: true, speed: 1.25, skipIntroSeconds: 30, skipOutroSeconds: 0, notifyNew: false, oldestFirst: false, updatedUtc: 1 };
  const mark = { feedUrl: 'https://example.com/feed.xml', guid: 'g1', positionMs: 1000, durationMs: 60000, played: false, favourite: true, favouriteUtc: 1, historyUtc: 1, lastPlayedUtc: 1, device: 'tv', updatedUtc: 2 };

  it('an account reads and writes its own library, marks and queue', async () => {
    const db = env.authenticatedContext('alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'users/alice/podcasts/p1'), show));
    await assertSucceeds(getDoc(doc(db, 'users/alice/podcasts/p1')));
    await assertSucceeds(setDoc(doc(db, 'users/alice/podcastMarks/m1'), mark));
    await assertSucceeds(setDoc(doc(db, 'users/alice/podcastState/queue'), { keys: [{ feedUrl: mark.feedUrl, guid: 'g1' }], updatedUtc: 3 }));
    await assertSucceeds(deleteDoc(doc(db, 'users/alice/podcastMarks/m1')));
  });

  it("nobody else reads or writes them", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/alice/podcasts/p1'), show);
    });
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(getDoc(doc(bob, 'users/alice/podcasts/p1')));
    await assertFails(setDoc(doc(bob, 'users/alice/podcastMarks/m1'), mark));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/alice/podcasts/p1')));
  });

  it('only the fields the app writes, of a bounded size', async () => {
    const db = env.authenticatedContext('alice').firestore();
    await assertFails(setDoc(doc(db, 'users/alice/podcasts/p1'), { ...show, extra: 'x' }));
    await assertFails(setDoc(doc(db, 'users/alice/podcasts/p1'), { ...show, feedUrl: 'x'.repeat(3000) }));
    await assertFails(setDoc(doc(db, 'users/alice/podcastMarks/m1'), { ...mark, played: 'yes' }));
    await assertFails(setDoc(doc(db, 'users/alice/podcastState/other'), { keys: [], updatedUtc: 1 }));
    await assertFails(setDoc(doc(db, 'users/alice/podcastState/queue'), { keys: Array.from({ length: 201 }, () => ({ feedUrl: 'a', guid: 'b' })), updatedUtc: 1 }));
  });
});
