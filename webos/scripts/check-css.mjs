#!/usr/bin/env node
/**
 * Fails if a built stylesheet uses CSS that Chromium 68 (webOS 5, ADR-057) does not have.
 *
 *   node scripts/check-css.mjs dist/assets/*.css
 *
 * Chromium 68 drops a declaration it does not understand without a word, so a layout that relies
 * on one is simply wrong on the television and right on a desktop. PostCSS lowers some of these
 * (postcss.config.mjs); the rest must not be written at all. Comments and strings are removed
 * before the search, so neither can fail it.
 */
import { readFileSync } from 'node:fs';

/** [pattern, what it is]. Each pattern runs on the stylesheet with comments and strings removed. */
const RULES = [
  [/(?:^|[\s{;(,:])(?:min|max|clamp)\(/, 'min()/max()/clamp() (Chromium 79)'],
  [/(?:^|[;{\s])(?:-webkit-)?backdrop-filter\s*:/, 'backdrop-filter (Chromium 76)'],
  [/(?:^|[;{\s])(?:row-|column-)?gap\s*:/, 'gap (flex gap is Chromium 84; write margins, or grid-gap for a grid)'],
  [/(?:^|[;{\s])inset(?:-inline|-block)?(?:-start|-end)?\s*:/, 'inset (Chromium 87)'],
  [/(?:^|[;{\s])(?:margin|padding|border|scroll-margin|scroll-padding|overscroll-behavior)-(?:inline|block)(?:-start|-end)?(?:-width|-style|-color)?\s*:/, 'a logical property (Chromium 69–89)'],
  [/(?:^|[;{\s])(?:min-|max-)?(?:inline|block)-size\s*:/, 'inline-size/block-size (Chromium 57+ for some, 89 for the rest)'],
  [/(?:^|[;{\s])aspect-ratio\s*:/, 'aspect-ratio (Chromium 88)'],
  [/(?:^|[;{\s])(?:translate|rotate|scale)\s*:/, 'the individual transform properties (Chromium 104)'],
  [/:focus-visible\b/, ':focus-visible (Chromium 86)'],
  [/:(?:is|where|has)\(/, ':is()/:where()/:has() (Chromium 88+)'],
  [/:not\([^)]*,/, ':not() with a list (Chromium 88)'],
  [/@(?:container|layer)\b/, '@container/@layer (Chromium 99+)'],
  [/\b\d*\.?\d+(?:dvh|svh|lvh|dvw|svw|lvw)\b/, 'dynamic viewport units (Chromium 108)'],
  [/(?:^|[\s{;])&/, 'CSS nesting (Chromium 112)'],
  [/\b(?:color-mix|lab|lch|oklab|oklch)\(/, 'a new colour function (Chromium 111)'],
];

function strip(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""');
}

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: check-css.mjs <file.css>...');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const css = strip(readFileSync(file, 'utf8'));
  for (const [pattern, what] of RULES) {
    const g = new RegExp(pattern.source, 'g');
    let m;
    while ((m = g.exec(css)) !== null) {
      const around = css.slice(Math.max(0, m.index - 40), m.index + 40).replace(/\s+/g, ' ');
      console.error(`${file}: ${what}: …${around}…`);
      failed = true;
    }
  }
}
if (failed) process.exit(1);
console.log(`check-css: ${files.length} stylesheet(s) hold nothing newer than Chromium 68`);
