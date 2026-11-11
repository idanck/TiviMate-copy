/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import preact from '@preact/preset-vite';
import { build as esbuild } from 'esbuild';
import { execSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { shell } from './scripts/shell-plugin';

/**
 * Shidur for LG televisions from webOS 5, which runs Chromium 68 (ADR-057). Everything shipped is
 * ES2019 with no API newer than 68 (scripts/check-es.mjs) and no CSS newer than it
 * (scripts/check-css.mjs); public/polyfills.js loads first in the page and in the guide worker.
 */
const TARGET = 'chrome68';

/** The webOS version is its own, in appinfo/appinfo.json: LG reviews every release by hand. */
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
 * The guide worker, as one script of its own: public/polyfills.js, then the worker bundled for
 * Chromium 68. `import source from 'virtual:epg-worker'` gives its text, which the page starts as
 * a Blob worker (a packaged app cannot start a worker from a file:// address). The same text is
 * written to .build/epg.worker.js so check-es can read it: inside the page's bundle it is only a
 * string.
 */
function epgWorker(): Plugin {
  const id = 'virtual:epg-worker';
  const entry = resolve(__dirname, 'src/workers/epg.worker.ts');
  const polyfills = resolve(__dirname, 'public/polyfills.js');
  let minify = false;
  return {
    name: 'shidur-epg-worker',
    configResolved(config) {
      minify = config.command === 'build';
    },
    resolveId(source) {
      return source === id ? `\0${id}` : null;
    },
    async load(loaded) {
      if (loaded !== `\0${id}`) return null;
      const out = await esbuild({
        entryPoints: [entry],
        bundle: true,
        format: 'iife',
        target: TARGET,
        platform: 'browser',
        minify,
        legalComments: 'none',
        write: false,
        metafile: true,
      });
      for (const input of Object.keys(out.metafile.inputs)) this.addWatchFile(resolve(__dirname, input));
      this.addWatchFile(polyfills);
      const code = `${readFileSync(polyfills, 'utf8')}\n${out.outputFiles[0].text}`;
      mkdirSync(resolve(__dirname, '.build'), { recursive: true });
      writeFileSync(resolve(__dirname, '.build/epg.worker.js'), code);
      return `export default ${JSON.stringify(code)};`;
    },
  };
}

export default defineConfig({
  // Relative addresses: the page is opened from wherever the television installed the package.
  base: './',
  plugins: [
    preact(),
    shell({ root: __dirname, version: versionName, commit: gitSha, selfUpdate: process.env.SHIDUR_SELF_UPDATE !== '0' }),
    epgWorker(),
  ],
  define: {
    __APP_VERSION__: JSON.stringify(versionName()),
    __APP_SHA__: JSON.stringify(gitSha()),
    __APP_BUILT__: JSON.stringify(new Date().toISOString()),
  },
  server: {
    port: 5175,
  },
  esbuild: {
    target: TARGET,
  },
  build: {
    target: TARGET,
    cssTarget: TARGET,
    modulePreload: false,
    // One stylesheet, linked in the page: with an iife bundle Vite would otherwise inject it from script.
    cssCodeSplit: false,
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        // Fixed names at the root (the shell loads them by name, and the update host keeps each
        // release under its own version and commit).
        entryFileNames: 'index.js',
        assetFileNames: (asset) => (asset.names?.[0]?.endsWith('.css') ? 'style.css' : 'assets/[name]-[hash][extname]'),
      },
    },
  },
  test: {
    include: ['test/**/*.test.{ts,mjs}'],
    environment: 'node',
  },
});
