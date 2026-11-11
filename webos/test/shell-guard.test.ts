import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SHELL } from '../scripts/shell-plugin';

/**
 * A bundle runs only on the shell it was built for (ADR-057, 0.4.0): the shell's number, SHELL in
 * scripts/shell-plugin.ts. This test holds a hash of every file that is the shell, not the bundle,
 * beside that number. Changing any of them fails here until SHELL is raised and the hash below
 * replaced: a changed shell has to reach the television by a reinstall from the phone, and bundles
 * built for it must not run on the old one.
 */
const SHELL_HASHES: Record<number, string> = {
  1: '65d972753720d186ba1698c16c16f5b50e686b0a86ba8941682c8991dd05800c',
};

const root = new URL('..', import.meta.url).pathname;

function shellFiles(): string[] {
  const fonts = readdirSync(join(root, 'public/fonts')).sort().map((f) => `public/fonts/${f}`);
  return [
    'index.html',
    'shell/boot.js',
    'shell/public-key.txt',
    'scripts/shell-plugin.ts',
    'appinfo/icon-80.png',
    'appinfo/icon-130.png',
    'appinfo/splash.png',
    'service/index.js',
    'service/net.js',
    'service/package.json',
    'service/services.json',
    'node_modules/tweetnacl/nacl-fast.min.js',
    ...fonts,
  ];
}

function shellHash(): string {
  const hash = createHash('sha256');
  for (const file of shellFiles()) {
    hash.update(`${file}\n`);
    hash.update(readFileSync(join(root, file)));
  }
  // appinfo.json is the shell's too, except its version: a bundle-only release raises that.
  const appinfo = JSON.parse(readFileSync(join(root, 'appinfo/appinfo.json'), 'utf8')) as Record<string, unknown>;
  delete appinfo.version;
  hash.update(`appinfo/appinfo.json\n${JSON.stringify(appinfo)}`);
  return hash.digest('hex');
}

describe('the shell', () => {
  it('has not changed without its number being raised', () => {
    const now = shellHash();
    expect(
      SHELL_HASHES[SHELL],
      `The shell's files changed (${now}). Raise SHELL in scripts/shell-plugin.ts and put this hash under the new number in test/shell-guard.test.ts.`,
    ).toBe(now);
  });
});
