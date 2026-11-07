/**
 * A key-value store over IndexedDB: channel lists and guides are too large for localStorage and
 * must survive a restart, so the app opens with the last list while it refreshes.
 *
 * A failed open is not kept (ADR-057): the first version cached the rejected promise, so one bad
 * moment at start-up (storage still mounting, a quota prompt) left every later read failing and
 * the app never started. Each call now tries to open again, and the caller decides what a failure
 * means (state/store.ts starts with nothing stored rather than not at all).
 */
const DB = 'shidur';
const STORE = 'kv';
/** An open that neither succeeds nor fails (a `blocked` that never clears) counts as failed. */
const OPEN_TIMEOUT_MS = 10_000;

let opening: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (opening) return opening;
  const attempt = new Promise<IDBDatabase>((resolve, reject) => {
    let req: IDBOpenDBRequest;
    try {
      req = indexedDB.open(DB, 1);
    } catch (err) {
      reject(err);
      return;
    }
    const timer = setTimeout(() => reject(new Error('indexeddb-timeout')), OPEN_TIMEOUT_MS);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => {
      clearTimeout(timer);
      const db = req.result;
      // Another tab (or a later version) asking to upgrade: let it, and open afresh next time.
      db.onversionchange = () => {
        db.close();
        if (opening === attempt) opening = null;
      };
      resolve(db);
    };
    req.onerror = () => {
      clearTimeout(timer);
      reject(req.error ?? new Error('indexeddb-open'));
    };
  });
  opening = attempt;
  attempt.catch(() => {
    if (opening === attempt) opening = null;
  });
  return attempt;
}

async function run<T>(mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await open();
  return new Promise((resolve, reject) => {
    let tx: IDBTransaction;
    try {
      tx = db.transaction(STORE, mode);
    } catch (err) {
      // The connection was closed under us: open again on the next call.
      opening = null;
      reject(err);
      return;
    }
    const req = op(tx.objectStore(STORE));
    tx.oncomplete = () => resolve(req.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const kv = {
  get: <T>(key: string) => run<T | undefined>('readonly', (s) => s.get(key) as IDBRequest<T | undefined>),
  set: (key: string, value: unknown) => run('readwrite', (s) => s.put(value, key)),
  del: (key: string) => run('readwrite', (s) => s.delete(key)),
};

/** For the tests: forget the connection, as a fresh start would. */
export function resetForTests() {
  opening = null;
}
