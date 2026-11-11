import type { Plugin } from 'vite';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** The shell's number (ADR-057, 0.4.0): a bundle runs only on the shell it was built for. */
export const SHELL = 1;
export const APP_ID = 'com.idanck.shidur';
/** Where bundles are published. A store build (SHIDUR_SELF_UPDATE=0) carries none of it. */
export const UPDATE_BASE = 'https://updates.idanck.com/webos/';
/**
 * The bundle's files, in the order the shell loads them. They sit at the package's root, so the
 * stylesheet's `fonts/…` addresses resolve the same from a <link> (the packaged bundle) and from
 * a <style> in the page (a downloaded one).
 */
export const BUNDLE_FILES = ['style.css', 'polyfills.js', 'index.js'];

export interface ShellOptions {
  root: string;
  version: () => string;
  commit: () => string;
  selfUpdate: boolean;
}

/**
 * The shell (ADR-057, 0.4.0). A packaged app is opened from file://, where webOS refuses module
 * scripts, so the page loads one classic script, `boot.js`, and nothing else: it decides which
 * bundle runs and loads that bundle's stylesheet and scripts (shell/boot.js). The build writes:
 *
 * - `index.html` with only `<script src="boot.js">` in it;
 * - `boot.js`: `var SHELL_CONFIG = {…}`, tweetnacl and shell/boot.js; a store build
 *   (SHIDUR_SELF_UPDATE=0) leaves out tweetnacl, the public key and the update address;
 * - `bundle.json`, unless a store build: the app id, version, commit and shell, and for each file
 *   its address on the update host, size and SHA-512. The Pi signs it to publish the bundle
 *   (tools/publish-bundle.sh).
 */
export function shell(options: ShellOptions): Plugin {
  let outDir = '';
  return {
    name: 'shidur-shell',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    transformIndexHtml(html) {
      const out = html
        .replace(/\s*<!--[^>]*-->\s*<script src="polyfills\.js"><\/script>/, '')
        .replace(/\s*<script[^>]*src="[^"]*index\.js"[^>]*><\/script>/g, '')
        .replace(/\s*<link rel="stylesheet"[^>]*>/g, '')
        .replace('</body>', '  <script src="boot.js"></script>\n  </body>');
      if (/<script(?![^>]*src="boot\.js")/.test(out) || /<link rel="stylesheet"/.test(out)) {
        throw new Error('shidur-shell: index.html must load boot.js only');
      }
      return out;
    },
    writeBundle() {
      const version = options.version();
      const commit = options.commit();
      const config: Record<string, unknown> = {
        app: APP_ID,
        shell: SHELL,
        selfUpdate: options.selfUpdate,
        packaged: { version, commit },
      };
      const parts: string[] = [];
      if (options.selfUpdate) {
        const files = BUNDLE_FILES.map((path) => {
          const bytes = readFileSync(resolve(outDir, path));
          return {
            path,
            url: `${UPDATE_BASE}bundles/${version}-${commit}/${path}`,
            size: bytes.length,
            sha512: createHash('sha512').update(bytes).digest('hex'),
          };
        });
        config.updateBase = UPDATE_BASE;
        config.publicKey = readFileSync(resolve(options.root, 'shell/public-key.txt'), 'utf8').trim();
        parts.push(readFileSync(resolve(options.root, 'node_modules/tweetnacl/nacl-fast.min.js'), 'utf8'));
        const bundle = { app: APP_ID, version, commit, shell: SHELL, built: new Date().toISOString(), files };
        writeFileSync(resolve(outDir, 'bundle.json'), `${JSON.stringify(bundle, null, 2)}\n`);
      }
      parts.push(`var SHELL_CONFIG = ${JSON.stringify(config)};`);
      parts.push(readFileSync(resolve(options.root, 'shell/boot.js'), 'utf8'));
      writeFileSync(resolve(outDir, 'boot.js'), parts.join('\n'));
    },
  };
}
