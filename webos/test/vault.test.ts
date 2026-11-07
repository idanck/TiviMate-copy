import { webcrypto } from 'node:crypto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Source } from '../src/data/model';
import * as vault from '../src/data/vault';

/**
 * Passwords sealed in storage (data/vault.ts), with IndexedDB stood in for by a map that can be
 * made to fail: the first version wrote an empty password over the sealed one when the key failed
 * to load once (ADR-057).
 */
const { store, flags } = vi.hoisted(() => ({ store: new Map<string, unknown>(), flags: { failing: false } }));
vi.mock('../src/data/db', () => ({
  kv: {
    get: async (k: string) => {
      if (flags.failing) throw new Error('idb busy');
      return store.get(k);
    },
    set: async (k: string, v: unknown) => {
      if (flags.failing) throw new Error('idb busy');
      store.set(k, v);
    },
    del: async (k: string) => void store.delete(k),
  },
}));

const xtream = (password: string): Source => ({
  id: 'x1', name: 'panel', type: 'XTREAM', role: 'tv', url: 'http://panel.example', username: 'me', password,
  outputFormat: 'TS', headers: {}, enabled: true, sortIndex: 0,
});

beforeEach(() => {
  vi.stubGlobal('crypto', webcrypto);
  store.clear();
  flags.failing = false;
  vault.resetVaultForTests();
});

describe('the vault', () => {
  it('seals a password and opens it again', async () => {
    const sealed = await vault.sealSource(xtream('secret'));
    expect(sealed.password).toMatch(/^v1:/);
    expect(sealed.password).not.toContain('secret');
    vault.resetVaultForTests();
    expect((await vault.unsealSource(sealed)).password).toBe('secret');
  });

  it('a key that fails to load once does not lose the password (ADR-057)', async () => {
    const sealed = await vault.sealSource(xtream('secret'));
    vault.resetVaultForTests();
    flags.failing = true;
    // Start-up while IndexedDB is busy: the password cannot be opened...
    const opened = await vault.unsealSource(sealed);
    expect(opened.password).toBe('');
    expect(vault.passwordUnopened('x1')).toBe(true);
    // ...and the sources are saved again: the sealed one goes back as it was, not ''.
    expect((await vault.sealSource(opened)).password).toBe(sealed.password);
    // The key is tried again next time, not remembered as missing.
    flags.failing = false;
    expect((await vault.unsealSource(sealed)).password).toBe('secret');
  });

  it('a password typed again replaces one that could not be opened', async () => {
    const sealed = await vault.sealSource(xtream('old'));
    vault.resetVaultForTests();
    flags.failing = true;
    await vault.unsealSource(sealed);
    flags.failing = false;
    const resealed = await vault.sealSource(xtream('new'));
    vault.resetVaultForTests();
    expect((await vault.unsealSource(resealed)).password).toBe('new');
  });

  it('a password that cannot be sealed now is never written in the clear', async () => {
    flags.failing = true;
    const out = await vault.sealSource(xtream('secret'));
    expect(JSON.stringify(out)).not.toContain('secret');
  });

  it('an old password kept in the clear is sealed on the next save', async () => {
    const opened = await vault.unsealSource(xtream('plain'));
    expect(opened.password).toBe('plain');
    expect((await vault.sealSource(opened)).password).toMatch(/^v1:/);
  });
});
