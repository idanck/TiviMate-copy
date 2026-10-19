/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The web app carries the same version as the APK: `version.properties` at the repository root is
 * the single source of truth for both (README → Versioning). A build outside the repository (no
 * file, no git) falls back to SHIDUR_VERSION / SHIDUR_SHA from the environment.
 */
function versionName(): string {
  const file = resolve(__dirname, '../version.properties');
  if (!existsSync(file)) return process.env.SHIDUR_VERSION ?? 'dev';
  const props = Object.fromEntries(
    readFileSync(file, 'utf8')
      .split('\n')
      .filter((l) => /^\s*[A-Za-z]+\s*=/.test(l))
      .map((l) => l.split('=').map((s) => s.trim()) as [string, string]),
  );
  return `${props.versionMajor}.${props.versionMinor}.${props.versionPatch}`;
}

function gitSha(): string {
  if (process.env.SHIDUR_SHA) return process.env.SHIDUR_SHA;
  try {
    return execSync('git rev-parse --short=8 HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return 'local';
  }
}

export default defineConfig({
  plugins: [preact()],
  define: {
    __APP_VERSION__: JSON.stringify(versionName()),
    __APP_SHA__: JSON.stringify(gitSha()),
    __APP_BUILT__: JSON.stringify(new Date().toISOString()),
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8787' },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    include: ['test/**/*.test.{ts,mjs}'],
    environment: 'node',
  },
});
