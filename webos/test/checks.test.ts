import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/** scripts/check-es.mjs and scripts/check-css.mjs fail on what they should, and only on that. */
const root = new URL('..', import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), 'shidur-checks-'));

function run(script: string, args: string[], content: string, ext: string) {
  const file = join(dir, `f${Math.random().toString(36).slice(2)}.${ext}`);
  writeFileSync(file, content);
  try {
    execFileSync('node', [join(root, 'scripts', script), ...args, file], { stdio: 'pipe' });
    return { ok: true, out: '' };
  } catch (e) {
    return { ok: false, out: String((e as { stderr: Buffer }).stderr) };
  }
}

const es = (code: string, ...args: string[]) => run('check-es.mjs', args, code, 'js');
const css = (code: string) => run('check-css.mjs', [], code, 'css');
const POLY = ['--polyfills', join(root, 'public/polyfills.js')];

describe('check-es', () => {
  it('passes plain ES2019', () => expect(es('var a = [1].map(function (x) { return x; }); a.toString(); ({ a }).hasOwnProperty("a");').ok).toBe(true));
  it('fails syntax Chromium 68 cannot parse', () => {
    expect(es('class A { x = 1 }').ok).toBe(false);
    expect(es('a?.b').ok).toBe(false);
    expect(es('a ?? b').ok).toBe(false);
  });
  it('fails an API Chromium 68 lacks, and allows it once polyfilled', () => {
    expect(es('[].flat()').ok).toBe(false);
    expect(es('[].flat(); "".matchAll(/a/g); queueMicrotask(f); Object.fromEntries([])', ...POLY).ok).toBe(true);
    expect(es('"".replaceAll("a", "b")', ...POLY).ok).toBe(false);
  });
  it('fails an inline style Chromium 68 ignores', () => {
    expect(es('h("div", { style: { insetInlineStart: 0 } })').out).toContain('insetInlineStart');
    expect(es('h("div", { style: { marginInlineStart: 4 } })').ok).toBe(false);
    expect(es('h("div", { style: { aspectRatio: "16/9" } })').ok).toBe(false);
    expect(es('h("div", { style: { marginRight: 4, top: 0 } })').ok).toBe(true);
  });
  it('holds the service to ES2017 (Node 8.12)', () => {
    expect(es('const { a, ...rest } = b;', '--ecma', '2017').ok).toBe(false);
    expect(es('async function f() { await g(); }', '--ecma', '2017').ok).toBe(true);
  });
});

describe('check-css', () => {
  it('passes what Chromium 68 has', () => expect(css('.a{display:flex;margin-right:1rem;grid-gap:1rem;top:0}.b:focus{color:red}').ok).toBe(true));
  for (const [what, rule] of [
    ['flex gap', '.a{display:flex;gap:1rem}'],
    ['min()', '.a{width:min(10rem,50%)}'],
    ['clamp()', '.a{font-size:clamp(1rem,2vw,2rem)}'],
    ['inset', '.a{inset:0}'],
    ['a logical property', '.a{margin-inline-start:1rem}'],
    ['backdrop-filter', '.a{backdrop-filter:blur(4px)}'],
    [':focus-visible', '.a:focus-visible{color:red}'],
    ['aspect-ratio', '.a{aspect-ratio:16/9}'],
    [':is()', ':is(.a,.b){color:red}'],
  ]) {
    it(`fails ${what}`, () => expect(css(rule).ok).toBe(false));
  }
  it('is not fooled by a comment or a string', () => expect(css('/* gap: 1rem; min( */ .a{content:"inset: 0"}').ok).toBe(true));
});
