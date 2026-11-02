/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import preact from '@preact/preset-vite';
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The webOS version has its own version, in appinfo/appinfo.json (ADR-052): LG reviews every
 * release by hand, so it ships on its own schedule, not with each APK.
 */
function versionName(): string {
  const appinfo = JSON.parse(readFileSync(resolve(__dirname, 'appinfo/appinfo.json'), 'utf8')) as { version: string };
  return appinfo.version;
}

function gitSha(): string {
  if (process.env.SHIDUR_SHA) return process.env.SHIDUR_SHA;
  try {
    return execSync('git rev-parse --short=8 HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return 'local';
  }
}

/**
 * A packaged app is opened from file://, where webOS 6 refuses module scripts ("cross origin
 * requests are only supported for protocol schemes: http, data, …"). The bundle is one classic
 * script instead, deferred so it still runs after the page is parsed, and nothing asks for CORS.
 */
function classicScript(): Plugin {
  return {
    name: 'shidur-classic-script',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml(html) {
      return html
        .replace(/<script type="module" crossorigin /g, '<script defer ')
        .replace(/<script type="module" /g, '<script defer ')
        .replace(/ crossorigin(="[^"]*")?/g, '');
    },
  };
}

export default defineConfig({
  // Relative addresses: the page is opened from wherever the television installed the package.
  base: './',
  plugins: [preact(), classicScript()],
  define: {
    __APP_VERSION__: JSON.stringify(versionName()),
    __APP_SHA__: JSON.stringify(gitSha()),
    __APP_BUILT__: JSON.stringify(new Date().toISOString()),
  },
  server: {
    port: 5174,
  },
  worker: {
    format: 'iife',
  },
  build: {
    // webOS 6 (2021) runs Chromium 79: the oldest television this version supports.
    target: 'chrome79',
    cssTarget: 'chrome79',
    modulePreload: false,
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
      },
    },
  },
  test: {
    include: ['test/**/*.test.{ts,mjs}'],
    environment: 'node',
  },
});
