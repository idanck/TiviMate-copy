/*
 * What Chromium 68 (webOS 5) lacks and the app, or a library it bundles, calls (ADR-057).
 *
 * Loaded first: as a classic script before the bundle in index.html, and at the head of the guide
 * worker's own code (vite.config.ts). Plain ES5 with no build step. Each API is defined only where
 * it is missing, through define(), whose second argument is how scripts/check-es.mjs learns what
 * this file provides: an API defined here may be used anywhere else in the bundle.
 *
 * test/polyfills.test.ts runs this file with the natives taken away and checks each one.
 */
(function (g) {
  'use strict';

  function define(target, name, value) {
    if (target[name] !== undefined) return;
    Object.defineProperty(target, name, { value: value, configurable: true, writable: true, enumerable: false });
  }

  define(g, 'globalThis', g);

  define(g, 'queueMicrotask', function (callback) {
    if (typeof callback !== 'function') throw new TypeError('queueMicrotask needs a function');
    Promise.resolve().then(callback).catch(function (err) {
      setTimeout(function () { throw err; }, 0);
    });
  });

  function flatInto(out, list, depth) {
    for (var i = 0; i < list.length; i++) {
      if (!(i in list)) continue;
      var item = list[i];
      if (depth > 0 && Array.isArray(item)) flatInto(out, item, depth - 1);
      else out.push(item);
    }
    return out;
  }

  define(Array.prototype, 'flat', function (depth) {
    var d = depth === undefined ? 1 : Number(depth);
    return flatInto([], Object(this), d > 0 ? d : 0);
  });

  define(Array.prototype, 'flatMap', function (callback, thisArg) {
    var list = Object(this);
    var out = [];
    for (var i = 0; i < list.length; i++) {
      if (!(i in list)) continue;
      var mapped = callback.call(thisArg, list[i], i, list);
      if (Array.isArray(mapped)) flatInto(out, mapped, 0);
      else out.push(mapped);
    }
    return out;
  });

  define(String.prototype, 'matchAll', function (regexp) {
    if (!(regexp instanceof RegExp)) regexp = new RegExp(regexp, 'g');
    else if (regexp.flags.indexOf('g') < 0) throw new TypeError('matchAll needs a global regular expression');
    var re = new RegExp(regexp.source, regexp.flags);
    re.lastIndex = regexp.lastIndex;
    var text = String(this);
    var done = false;
    var iterator = {
      next: function () {
        if (done) return { value: undefined, done: true };
        var m = re.exec(text);
        if (m === null) {
          done = true;
          return { value: undefined, done: true };
        }
        if (m[0] === '') re.lastIndex++;
        return { value: m, done: false };
      }
    };
    iterator[Symbol.iterator] = function () { return iterator; };
    return iterator;
  });

  define(Object, 'fromEntries', function (entries) {
    var out = {};
    var it = entries[Symbol.iterator]();
    for (var step = it.next(); !step.done; step = it.next()) out[step.value[0]] = step.value[1];
    return out;
  });

  define(Promise, 'allSettled', function (promises) {
    return Promise.all(Array.prototype.map.call(Array.from(promises), function (p) {
      return Promise.resolve(p).then(
        function (value) { return { status: 'fulfilled', value: value }; },
        function (reason) { return { status: 'rejected', reason: reason }; }
      );
    }));
  });
})(typeof self !== 'undefined' ? self : typeof window !== 'undefined' ? window : this);
