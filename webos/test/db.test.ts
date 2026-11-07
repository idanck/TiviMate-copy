import { afterEach, describe, expect, it, vi } from 'vitest';
import { kv, resetForTests } from '../src/data/db';

/**
 * A failed IndexedDB open is not kept (ADR-057): the first version cached the rejected open, and
 * the app never started. A stand-in for indexedDB whose first open fails.
 */
function fakeIndexedDb(failFirst: number) {
  const data = new Map<string, unknown>();
  let opens = 0;
  const request = <T>(result: () => T) => {
    const r: { result?: T; error?: unknown; onsuccess?: () => void; onerror?: () => void } = {};
    return { r, run: () => ((r.result = result()), r) };
  };
  const db = {
    onversionchange: null,
    close() {},
    transaction() {
      const tx: Record<string, unknown> = {};
      tx.objectStore = () => ({
        get: (k: string) => {
          const req = { result: data.get(k) };
          setTimeout(() => (tx.oncomplete as () => void)?.(), 0);
          return req;
        },
        put: (v: unknown, k: string) => {
          data.set(k, v);
          setTimeout(() => (tx.oncomplete as () => void)?.(), 0);
          return { result: k };
        },
        delete: (k: string) => {
          data.delete(k);
          setTimeout(() => (tx.oncomplete as () => void)?.(), 0);
          return { result: undefined };
        },
      });
      return tx;
    },
    createObjectStore() {},
  };
  return {
    get opens() {
      return opens;
    },
    open() {
      opens++;
      const failing = opens <= failFirst;
      const { r } = request(() => db);
      const req = r as { result?: unknown; error?: unknown; onsuccess?: () => void; onerror?: () => void; onupgradeneeded?: () => void };
      setTimeout(() => {
        if (failing) {
          req.error = new Error('QuotaExceededError');
          req.onerror?.();
        } else {
          req.result = db;
          req.onsuccess?.();
        }
      }, 0);
      return req;
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  resetForTests();
});

describe('the key-value store (data/db.ts)', () => {
  it('opens again after a failed open, instead of failing for ever', async () => {
    const idb = fakeIndexedDb(1);
    vi.stubGlobal('indexedDB', idb);
    await expect(kv.get('sources')).rejects.toThrow();
    await kv.set('sources', ['a']);
    expect(await kv.get('sources')).toEqual(['a']);
    expect(idb.opens).toBe(2);
  });

  it('opens once while it works', async () => {
    const idb = fakeIndexedDb(0);
    vi.stubGlobal('indexedDB', idb);
    await kv.set('a', 1);
    await kv.get('a');
    await kv.del('a');
    expect(idb.opens).toBe(1);
  });
});
