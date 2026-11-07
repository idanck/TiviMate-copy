#!/usr/bin/env node
/**
 * Fails if a script is not plain ES2019 or uses an API newer than Chromium 68 (webOS 5, ADR-057).
 *
 *   node scripts/check-es.mjs [--ecma 2017] [--polyfills <file>] <file.js>...
 *
 * acorn parses each file as ES2019 (class fields, optional chaining, `??` and the like are syntax
 * errors there; `--ecma 2017` for the JS service, which runs in Node 8.12), then its syntax tree is
 * searched for APIs Chromium 68 does not have: by name, so a comment or a string never fails it.
 *
 * `--polyfills <file>`: the APIs that file defines through `define(target, '<name>', …)` (as
 * public/polyfills.js does) are allowed everywhere, since it is loaded first in the page and in
 * every worker.
 *
 * Also refused: a style written as an object key that Chromium 68 ignores (`insetInlineStart`,
 * `marginInlineStart`, `aspectRatio`, …). PostCSS lowers the stylesheet, but an inline style is
 * set from script and nothing lowers it.
 */
import { readFileSync } from 'node:fs';
import { parse } from 'acorn';

/** Methods called on any object: `x.flat()`. */
const METHODS = Object.assign(Object.create(null), {
  flat: 'Array.prototype.flat (Chromium 69)',
  flatMap: 'Array.prototype.flatMap (Chromium 69)',
  matchAll: 'String.prototype.matchAll (Chromium 73)',
  replaceAll: 'String.prototype.replaceAll (Chromium 85)',
  replaceChildren: 'Element.replaceChildren (Chromium 86)',
  toggleAttribute: 'Element.toggleAttribute (Chromium 69)',
  at: 'Array/String .at() (Chromium 92)',
});
/** Globals read by name: `queueMicrotask(…)`. */
const GLOBALS = Object.assign(Object.create(null), {
  globalThis: 'globalThis (Chromium 71)',
  queueMicrotask: 'queueMicrotask (Chromium 71)',
  structuredClone: 'structuredClone (Chromium 98)',
});
/** Members of a named global: `Object.fromEntries`. */
const STATICS = Object.assign(Object.create(null), {
  'Object.fromEntries': 'Object.fromEntries (Chromium 73)',
  'Object.hasOwn': 'Object.hasOwn (Chromium 93)',
  'Promise.allSettled': 'Promise.allSettled (Chromium 76)',
  'Promise.any': 'Promise.any (Chromium 85)',
  'Intl.RelativeTimeFormat': 'Intl.RelativeTimeFormat (Chromium 71)',
  'Intl.ListFormat': 'Intl.ListFormat (Chromium 72)',
});

/** Inline style keys (camelCase, as Preact sets them) that Chromium 68 does not know. */
const STYLE_KEY = /^(inset([A-Z]|$)|(margin|padding|border|scrollMargin|scrollPadding|overscrollBehavior)(Inline|Block)|(min|max)?(Inline|Block)Size$|aspectRatio$|backdropFilter$)/;

const nameOf = (n) => (n.type === 'Identifier' ? n.name : n.type === 'Literal' ? String(n.value) : null);

/** Every use of an API newer than Chromium 68 in a syntax tree, as [offset, what]. */
function findNewer(ast, provided) {
  const found = [];
  const walk = (node, parent) => {
    if (!node || typeof node.type !== 'string') return;
    if (node.type === 'CallExpression' && node.callee.type === 'MemberExpression') {
      const m = node.callee.computed ? nameOf(node.callee.property) : node.callee.property.name;
      if (m && METHODS[m] && !provided.has(m)) found.push([node.start, METHODS[m]]);
    }
    if (node.type === 'MemberExpression' && node.object.type === 'Identifier' && !node.computed) {
      const key = `${node.object.name}.${node.property.name}`;
      if (STATICS[key] && !provided.has(node.property.name)) found.push([node.start, STATICS[key]]);
    }
    if (node.type === 'Property' && !node.computed && node.value && node.value.type !== 'FunctionExpression') {
      const key = nameOf(node.key);
      if (key && STYLE_KEY.test(key)) found.push([node.start, `the style "${key}" (not in Chromium 68; inline styles are not lowered)`]);
    }
    if (node.type === 'Identifier' && GLOBALS[node.name] && !provided.has(node.name)) {
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

/** The names a polyfill file defines: the string second argument of every `define(…)` call. */
function providedBy(file) {
  const names = new Set();
  const walk = (node) => {
    if (!node || typeof node.type !== 'string') return;
    if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.callee.name === 'define') {
      const arg = node.arguments[1];
      if (arg && arg.type === 'Literal' && typeof arg.value === 'string') names.add(arg.value);
    }
    for (const key of Object.keys(node)) {
      const v = node[key];
      if (Array.isArray(v)) v.forEach(walk);
      else if (v && typeof v.type === 'string') walk(v);
    }
  };
  walk(parse(readFileSync(file, 'utf8'), { ecmaVersion: 5, sourceType: 'script' }));
  if (names.size === 0) {
    console.error(`${file}: defines no polyfill through define(target, 'name', value)`);
    process.exit(2);
  }
  return names;
}

const args = process.argv.slice(2);
const provided = new Set();
const files = [];
let ecmaVersion = 2019;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--polyfills') for (const n of providedBy(args[++i])) provided.add(n);
  else if (args[i] === '--ecma') ecmaVersion = Number(args[++i]);
  else files.push(args[i]);
}
if (files.length === 0) {
  console.error('usage: check-es.mjs [--ecma 2017] [--polyfills <file>] <file.js>...');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  let ast;
  try {
    ast = parse(src, { ecmaVersion, sourceType: 'script' });
  } catch (e) {
    console.error(`${file}: not ES${ecmaVersion}: ${e.message}`);
    failed = true;
    continue;
  }
  for (const [at, what] of findNewer(ast, provided)) {
    const line = src.slice(0, at).split('\n').length;
    console.error(`${file}:${line}: ${what} is newer than Chromium 68`);
    failed = true;
  }
}
if (failed) process.exit(1);
console.log(`check-es: ${files.length} file(s) are ES${ecmaVersion} with nothing newer than Chromium 68${provided.size ? ` (polyfilled: ${[...provided].join(', ')})` : ''}`);
