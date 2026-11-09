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
