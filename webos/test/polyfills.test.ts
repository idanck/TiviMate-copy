import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';

/**
 * public/polyfills.js, run as Chromium 68 would meet it: in a fresh global with every API it
 * provides taken away first. Each is then put back by the file and checked against what the real
 * one does.
 */
const source = readFileSync(new URL('../public/polyfills.js', import.meta.url), 'utf8');

function as68() {
  const g: Record<string, unknown> = { setTimeout };
  runInNewContext(
    `
    delete Array.prototype.flat; delete Array.prototype.flatMap; delete String.prototype.matchAll;
    delete Object.fromEntries; delete Promise.allSettled;
    var self = this; self.self = self;
    `,
    g,
  );
  // queueMicrotask and globalThis are not given to a new context unless asked for; check they are absent.
  runInNewContext('this.before = { qm: typeof queueMicrotask, flat: typeof [].flat, matchAll: typeof "".matchAll };', g);
  runInNewContext(source, g);
  return g;
}

describe('public/polyfills.js', () => {
  const g = as68();
  const run = <T>(code: string): T => runInNewContext(code, g) as T;

  it('starts from a global that lacks them', () => {
    expect(g.before).toEqual({ qm: 'undefined', flat: 'undefined', matchAll: 'undefined' });
  });

  it('flat and flatMap', () => {
    expect(run('JSON.stringify([1, [2, [3, [4]]], , 5].flat())')).toBe('[1,2,[3,[4]],5]');
    expect(run('JSON.stringify([1, [2, [3, [4]]]].flat(Infinity))')).toBe('[1,2,3,4]');
    expect(run('JSON.stringify([1, 2].flatMap(function (x) { return [x, [x * 10]]; }))')).toBe('[1,[10],2,[20]]');
  });

  it('matchAll, with groups and an empty match', () => {
    expect(run('JSON.stringify(Array.from("a1b22c".matchAll(/(\\d+)/g), function (m) { return [m[1], m.index]; }))')).toBe('[["1",1],["22",3]]');
    expect(run('Array.from("abc".matchAll(/x*/g)).length')).toBe(4);
    expect(() => run('"a".matchAll(/a/)')).toThrow(/global/);
  });

  it('Object.fromEntries from a Map and from pairs', () => {
    expect(run('JSON.stringify(Object.fromEntries(new Map([["a", 1], ["b", 2]])))')).toBe('{"a":1,"b":2}');
    expect(run('JSON.stringify(Object.fromEntries([["x", "y"]]))')).toBe('{"x":"y"}');
  });

  it('queueMicrotask runs before a timer', async () => {
    const order = await run<Promise<string[]>>(`
      new Promise(function (done) {
        var seen = [];
        setTimeout(function () { seen.push('timer'); done(seen); }, 0);
        queueMicrotask(function () { seen.push('micro'); });
      })`);
    expect(order).toEqual(['micro', 'timer']);
  });

  it('Promise.allSettled', async () => {
    const out = await run<Promise<unknown>>('Promise.allSettled([1, Promise.reject("no")])');
    expect(JSON.parse(JSON.stringify(out))).toEqual([{ status: 'fulfilled', value: 1 }, { status: 'rejected', reason: 'no' }]);
  });

  it('globalThis', () => {
    expect(run('globalThis === self')).toBe(true);
  });

  it('leaves a native one alone', () => {
    const native = Array.prototype.flat;
    runInNewContext(source, { self: globalThis });
    expect(Array.prototype.flat).toBe(native);
  });
});
