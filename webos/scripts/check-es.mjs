#!/usr/bin/env node
/**
 * Fails if a script is not plain ES2019 or uses an API newer than Chromium 68 (webOS 5, ADR-056).
 *
 *   node scripts/check-es.mjs <file.js>...
 *
 * acorn parses each file as ES2019 (class fields, optional chaining, `??` and the like are syntax
 * errors there), then the source is searched for calls Chromium 68 does not have. The search is
 * by name, so a polyfilled API is allowed only in the file that defines it (`--polyfills <file>`).
 */
import { readFileSync } from 'node:fs';
import { parse } from 'acorn';

const NEWER_THAN_68 = [
  ['.flat(', 'Array.prototype.flat (Chromium 69)'],
  ['.flatMap(', 'Array.prototype.flatMap (Chromium 69)'],
  ['.matchAll(', 'String.prototype.matchAll (Chromium 73)'],
  ['Object.fromEntries', 'Object.fromEntries (Chromium 73)'],
  ['queueMicrotask', 'queueMicrotask (Chromium 71)'],
  ['globalThis', 'globalThis (Chromium 71)'],
  ['.replaceAll(', 'String.prototype.replaceAll (Chromium 85)'],
  ['Promise.allSettled', 'Promise.allSettled (Chromium 76)'],
  ['Promise.any', 'Promise.any (Chromium 85)'],
  ['structuredClone', 'structuredClone (Chromium 98)'],
  ['Object.hasOwn', 'Object.hasOwn (Chromium 93)'],
  ['.replaceChildren(', 'replaceChildren (Chromium 86)'],
  ['.toggleAttribute(', 'Element.toggleAttribute (Chromium 69)'],
  ['Intl.RelativeTimeFormat', 'Intl.RelativeTimeFormat (Chromium 71)'],
  ['Intl.ListFormat', 'Intl.ListFormat (Chromium 72)'],
  ['.at(-', 'Array/String .at() (Chromium 92)'],
];

const args = process.argv.slice(2);
const allowed = new Set();
const files = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--polyfills') allowed.add(args[++i]);
  else files.push(args[i]);
}
if (files.length === 0) {
  console.error('usage: check-es.mjs [--polyfills <file>] <file.js>...');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  try {
    parse(src, { ecmaVersion: 2019, sourceType: 'script' });
  } catch (e) {
    console.error(`${file}: not ES2019: ${e.message}`);
    failed = true;
    continue;
  }
  if (allowed.has(file)) continue;
  for (const [needle, what] of NEWER_THAN_68) {
    const at = src.indexOf(needle);
    if (at >= 0) {
      const line = src.slice(0, at).split('\n').length;
      console.error(`${file}:${line}: ${what} is newer than Chromium 68`);
      failed = true;
    }
  }
}
if (failed) process.exit(1);
console.log(`check-es: ${files.length} file(s) are ES2019 with nothing newer than Chromium 68`);
