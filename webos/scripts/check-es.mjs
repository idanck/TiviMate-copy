#!/usr/bin/env node
/**
 * Fails if a script is not plain ES2019 or uses an API newer than Chromium 68 (webOS 5, ADR-056).
 *
 *   node scripts/check-es.mjs <file.js>...
 *
 * acorn parses each file as ES2019 (class fields, optional chaining, `??` and the like are syntax
 * errors there), then its syntax tree is searched for APIs Chromium 68 does not have: by name, so a
 * comment or a string never fails it, and a polyfilled API is allowed only in the file that
 * defines it (`--polyfills <file>`).
 */
import { readFileSync } from 'node:fs';
import { parse } from 'acorn';

/** Methods called on any object: `x.flat()`. */
const METHODS = {
  flat: 'Array.prototype.flat (Chromium 69)',
  flatMap: 'Array.prototype.flatMap (Chromium 69)',
  matchAll: 'String.prototype.matchAll (Chromium 73)',
  replaceAll: 'String.prototype.replaceAll (Chromium 85)',
  replaceChildren: 'Element.replaceChildren (Chromium 86)',
  toggleAttribute: 'Element.toggleAttribute (Chromium 69)',
  at: 'Array/String .at() (Chromium 92)',
};
/** Globals read by name: `queueMicrotask(…)`. */
const GLOBALS = {
  globalThis: 'globalThis (Chromium 71)',
  queueMicrotask: 'queueMicrotask (Chromium 71)',
  structuredClone: 'structuredClone (Chromium 98)',
};
/** Members of a named global: `Object.fromEntries`. */
const STATICS = {
  'Object.fromEntries': 'Object.fromEntries (Chromium 73)',
  'Object.hasOwn': 'Object.hasOwn (Chromium 93)',
  'Promise.allSettled': 'Promise.allSettled (Chromium 76)',
  'Promise.any': 'Promise.any (Chromium 85)',
  'Intl.RelativeTimeFormat': 'Intl.RelativeTimeFormat (Chromium 71)',
  'Intl.ListFormat': 'Intl.ListFormat (Chromium 72)',
};

const nameOf = (n) => (n.type === 'Identifier' ? n.name : n.type === 'Literal' ? String(n.value) : null);

/** Every use of an API newer than Chromium 68 in a syntax tree, as [offset, what]. */
function findNewer(ast) {
  const found = [];
  const walk = (node, parent) => {
    if (!node || typeof node.type !== 'string') return;
    if (node.type === 'CallExpression' && node.callee.type === 'MemberExpression') {
      const m = node.callee.computed ? nameOf(node.callee.property) : node.callee.property.name;
      if (m && METHODS[m]) found.push([node.start, METHODS[m]]);
    }
    if (node.type === 'MemberExpression' && node.object.type === 'Identifier' && !node.computed) {
      const key = `${node.object.name}.${node.property.name}`;
      if (STATICS[key]) found.push([node.start, STATICS[key]]);
    }
    if (node.type === 'Identifier' && GLOBALS[node.name]) {
      // Not a property name (`x.globalThis`) nor an object key (`{ queueMicrotask: … }`).
      const isProperty = parent && parent.type === 'MemberExpression' && parent.property === node && !parent.computed;
      const isKey = parent && parent.type === 'Property' && parent.key === node && !parent.computed;
      if (!isProperty && !isKey) found.push([node.start, GLOBALS[node.name]]);
    }
    for (const key of Object.keys(node)) {
      const v = node[key];
      if (Array.isArray(v)) v.forEach((c) => walk(c, node));
      else if (v && typeof v.type === 'string') walk(v, node);
    }
  };
  walk(ast, null);
  return found;
}

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
  let ast;
  try {
    ast = parse(src, { ecmaVersion: 2019, sourceType: 'script' });
  } catch (e) {
    console.error(`${file}: not ES2019: ${e.message}`);
    failed = true;
    continue;
  }
  if (allowed.has(file)) continue;
  for (const [at, what] of findNewer(ast)) {
    const line = src.slice(0, at).split('\n').length;
    console.error(`${file}:${line}: ${what} is newer than Chromium 68`);
    failed = true;
  }
}
if (failed) process.exit(1);
console.log(`check-es: ${files.length} file(s) are ES2019 with nothing newer than Chromium 68`);
