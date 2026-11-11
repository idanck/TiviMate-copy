#!/usr/bin/env node
/**
 * Checks what the build wrote for the shell (ADR-057, 0.4.0, scripts/shell-plugin.ts):
 *
 *   node scripts/check-shell.mjs          a build that updates itself
 *   node scripts/check-shell.mjs --store  a store build (SHIDUR_SELF_UPDATE=0)
 *
 * - dist/index.html loads boot.js and nothing else;
 * - every url() in dist/style.css is relative to the page (`./fonts/…`), not to the stylesheet's
 *   own folder, since a downloaded bundle's stylesheet is put in the page as a <style>;
 * - a self-updating build: dist/bundle.json names the three files with their real size and
 *   SHA-512, under the update host's address for this version and commit;
 * - a store build: no bundle.json, and nothing in dist/ names the update host's webos/ path or
 *   carries the public key.
 */
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const store = process.argv.includes('--store');
const problems = [];
const fail = (text) => problems.push(text);

const html = readFileSync(join(dist, 'index.html'), 'utf8');
const scripts = [...html.matchAll(/<script\b[^>]*>/g)].map((m) => m[0]);
if (scripts.length !== 1 || !/src="boot\.js"/.test(scripts[0])) fail(`index.html must load boot.js only, found: ${scripts.join(' ') || 'no script'}`);
if (/<link[^>]+stylesheet/.test(html)) fail('index.html links a stylesheet: the shell loads it');

const css = readFileSync(join(dist, 'style.css'), 'utf8');
for (const m of css.matchAll(/url\(\s*['"]?([^'")]+)/g)) {
  if (!/^(\.\/fonts\/|fonts\/|data:)/.test(m[1])) fail(`style.css: url(${m[1]}) is not relative to the page (./fonts/…)`);
}

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const publicKey = readFileSync(join(root, 'shell/public-key.txt'), 'utf8').trim();
if (store) {
  if (existsSync(join(dist, 'bundle.json'))) fail('a store build has a bundle.json');
  for (const file of walk(dist)) {
    if (/\.(woff2|png|jpg)$/.test(file)) continue;
    const text = readFileSync(file, 'utf8');
    if (text.includes('updates.idanck.com/webos')) fail(`${file.slice(root.length + 1)} names updates.idanck.com/webos`);
    if (text.includes(publicKey)) fail(`${file.slice(root.length + 1)} carries the public key`);
  }
} else {
  const bundle = JSON.parse(readFileSync(join(dist, 'bundle.json'), 'utf8'));
  const appinfo = JSON.parse(readFileSync(join(root, 'appinfo/appinfo.json'), 'utf8'));
  if (bundle.app !== appinfo.id) fail(`bundle.json app ${bundle.app} is not ${appinfo.id}`);
  if (bundle.version !== appinfo.version) fail(`bundle.json version ${bundle.version} is not appinfo's ${appinfo.version}`);
  const base = `https://updates.idanck.com/webos/bundles/${bundle.version}-${bundle.commit}/`;
  const want = ['style.css', 'polyfills.js', 'index.js'];
  if (JSON.stringify(bundle.files.map((f) => f.path)) !== JSON.stringify(want)) fail(`bundle.json files are not ${want.join(', ')}`);
  for (const f of bundle.files) {
    const bytes = readFileSync(join(dist, f.path));
    if (f.url !== base + f.path) fail(`bundle.json: ${f.path} is at ${f.url}, not ${base + f.path}`);
    if (f.size !== bytes.length) fail(`bundle.json: ${f.path} is ${bytes.length} bytes, not ${f.size}`);
    if (f.sha512 !== createHash('sha512').update(bytes).digest('hex')) fail(`bundle.json: ${f.path} has another SHA-512`);
  }
  const boot = readFileSync(join(dist, 'boot.js'), 'utf8');
  if (!boot.includes(publicKey)) fail('boot.js does not carry the public key');
}

if (problems.length) {
  for (const p of problems) console.error(`check-shell: ${p}`);
  process.exit(1);
}
console.log(`check-shell: the ${store ? 'store' : 'self-updating'} build is what the shell expects`);
