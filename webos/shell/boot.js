/*
 * Shidur's shell on the television (ADR-057, 0.4.0): the only script index.html loads.
 *
 * It decides which bundle of the interface runs (the one packaged with the app, or a newer one
 * the app downloaded itself), loads it, and on launch asks updates.idanck.com whether there is a
 * newer one. A downloaded bundle runs only if its manifest is signed with the key whose public
 * half is in the shell, and every file's size and SHA-512 are the manifest's: checked when it is
 * downloaded and again before every run, against what is in storage.
 *
 * Built by vite.config.ts (`shell()`): this file, after tweetnacl's nacl-fast.min.js (Ed25519 and
 * SHA-512 in plain JavaScript: Chromium 68 has no Ed25519 in WebCrypto) and after
 * `var SHELL_CONFIG = {…}`, which the build writes: the shell's number, the packaged bundle's
 * version, commit and files, and, unless the build is the store's (SHIDUR_SELF_UPDATE=0), the
 * update address and the public key. Plain ES5 with no build step (check-es --ecma 5).
 *
 * The bundle tells the shell it has drawn its first screen with `shidurShell.started()`, or that
 * the first screen is its crashed one with `shidurShell.crashed(reason)`, and reads what the shell
 * knows with `shidurShell.info()`. test/shell.test.ts runs this file in a fresh
 * global, with a fake localStorage and a fake Luna bus.
 */
(function (g, config) {
  'use strict';

  var doc = g.document;
  var nacl = g.nacl;
  var PREFIX = 'shidur.shell.';
  /** The files a bundle is made of, in the order they are loaded. */
  var FILES = ['style.css', 'polyfills.js', 'index.js'];
  var SERVICE = 'luna://' + config.app + '.service/';
  /** How long after the first screen the update is asked for, and how long a bundle has to draw one. */
  var CHECK_AFTER_MS = config.checkAfterMs !== undefined ? config.checkAfterMs : 20000;
  var START_WITHIN_MS = config.startWithinMs !== undefined ? config.startWithinMs : 60000;
  var FETCH_TIMEOUT_MS = 60000;
  var BAD_KEPT = 20;

  var events = [];
  var info = {
    shell: config.shell,
    selfUpdate: !!config.selfUpdate,
    packaged: { version: config.packaged.version, commit: config.packaged.commit },
    running: null,
    pending: null,
    bad: [],
    lastCheck: null,
    storage: null,
    timings: {},
    events: events,
  };
  var started = false;
  var checked = false;
  var failing = false;
  var runningStored = null;

  function now() {
    return new Date().getTime();
  }

  function clock() {
    var d = new Date();
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  function note(text) {
    events.push(clock() + ' ' + text);
    if (events.length > 40) events.shift();
  }

  // ---- storage -------------------------------------------------------------------------------

  function read(name) {
    try {
      var v = g.localStorage.getItem(PREFIX + name);
      return v ? JSON.parse(v) : null;
    } catch (e) {
      note('storage: cannot read ' + name + ': ' + (e && e.name));
      return null;
    }
  }

  function write(name, value) {
    try {
      if (value === null) g.localStorage.removeItem(PREFIX + name);
      else g.localStorage.setItem(PREFIX + name, JSON.stringify(value));
      return true;
    } catch (e) {
      note('storage: cannot write ' + name + ': ' + (e && e.name));
      return false;
    }
  }

  function readMeta() {
    var meta = read('meta') || {};
    if (!(meta.bad instanceof Array)) meta.bad = [];
    if (meta.trial === undefined) meta.trial = null;
    if (meta.good === undefined) meta.good = null;
    return meta;
  }

  // ---- bytes ---------------------------------------------------------------------------------

  /** A string's UTF-8 bytes: what the provider sent, since the service decoded it as UTF-8. */
  function utf8(text) {
    var bin = unescape(encodeURIComponent(text));
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function binaryOf(bytes) {
    var parts = [];
    for (var i = 0; i < bytes.length; i += 8192) {
      parts.push(String.fromCharCode.apply(null, Array.prototype.slice.call(bytes.subarray(i, i + 8192))));
    }
    return parts.join('');
  }

  function fromUtf8(bytes) {
    return decodeURIComponent(escape(binaryOf(bytes)));
  }

  function fromBase64(text) {
    var bin = g.atob(text);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function hex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    return out;
  }

  function sha512(text) {
    return hex(nacl.hash(utf8(text)));
  }

  // ---- versions ------------------------------------------------------------------------------

  var VERSION = /^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/;

  /** -1, 0 or 1: a against b, as major.minor.revision. */
  function compareVersions(a, b) {
    var x = String(a).split('.');
    var y = String(b).split('.');
    for (var i = 0; i < 3; i++) {
      var d = Number(x[i]) - Number(y[i]);
      if (d !== 0) return d < 0 ? -1 : 1;
    }
    return 0;
  }

  function idOf(payload) {
    return payload.version + ' ' + payload.commit;
  }

  function isBad(meta, payload) {
    for (var i = 0; i < meta.bad.length; i++) {
      if (meta.bad[i].version === payload.version && meta.bad[i].commit === payload.commit) return true;
    }
    return false;
  }

  // ---- checking a manifest -------------------------------------------------------------------

  /**
   * A signed manifest, `{payload: <bundle.json, base64>, signature: <base64>}`: its payload if the
   * signature verifies with the shell's public key and the payload is a bundle for this shell,
   * else the reason it is refused.
   */
  function openManifest(manifest) {
    if (!config.publicKey) return { ok: false, reason: 'this build does not update itself' };
    if (!manifest || typeof manifest.payload !== 'string' || typeof manifest.signature !== 'string') {
      return { ok: false, reason: 'not a manifest' };
    }
    var payloadBytes;
    var signature;
    try {
      payloadBytes = fromBase64(manifest.payload);
      signature = fromBase64(manifest.signature);
    } catch (e) {
      return { ok: false, reason: 'not base64' };
    }
    if (signature.length !== 64) return { ok: false, reason: 'signature is ' + signature.length + ' bytes' };
    var t0 = now();
    var verified = nacl.sign.detached.verify(payloadBytes, signature, fromBase64(config.publicKey));
    info.timings.verifyMs = now() - t0;
    if (!verified) return { ok: false, reason: 'signature does not verify' };
    var payload;
    try {
      payload = JSON.parse(fromUtf8(payloadBytes));
    } catch (e) {
      return { ok: false, reason: 'payload is not JSON' };
    }
    var shape = checkShape(payload);
    if (shape) return { ok: false, reason: shape };
    return { ok: true, payload: payload };
  }

  /** Why a payload is not a bundle for this shell, or '' if it is one. */
  function checkShape(p) {
    if (!p || typeof p !== 'object') return 'payload is not an object';
    if (p.app !== config.app) return 'another app: ' + p.app;
    if (p.shell !== config.shell) return 'built for shell ' + p.shell + ', this is shell ' + config.shell;
    if (typeof p.version !== 'string' || !VERSION.test(p.version)) return 'bad version: ' + p.version;
    if (typeof p.commit !== 'string' || !/^[0-9a-f]{7,40}$/.test(p.commit)) return 'bad commit: ' + p.commit;
    if (!(p.files instanceof Array) || p.files.length !== FILES.length) return 'files are not ' + FILES.join(', ');
    var base = config.updateBase + 'bundles/' + p.version + '-' + p.commit + '/';
    for (var i = 0; i < FILES.length; i++) {
      var f = fileOf(p, FILES[i]);
      if (!f) return 'missing ' + FILES[i];
      if (f.url !== base + f.path) return 'file address is not ' + base + f.path + ': ' + f.url;
      if (typeof f.size !== 'number' || f.size < 0 || f.size > 4000000) return 'bad size for ' + f.path;
      if (typeof f.sha512 !== 'string' || !/^[0-9a-f]{128}$/.test(f.sha512)) return 'bad hash for ' + f.path;
    }
    return '';
  }

  function fileOf(payload, path) {
    for (var i = 0; i < payload.files.length; i++) if (payload.files[i] && payload.files[i].path === path) return payload.files[i];
    return null;
  }

  /** Why a file's text is not the manifest's, or '' if it is. */
  function checkFile(entry, text) {
    if (typeof text !== 'string') return entry.path + ' is missing';
    var bytes = utf8(text);
    if (bytes.length !== entry.size) return entry.path + ' is ' + bytes.length + ' bytes, not ' + entry.size;
    if (hex(nacl.hash(bytes)) !== entry.sha512) return entry.path + ' has another SHA-512';
    return '';
  }

  /** A stored bundle `{manifest, files}`, checked again from the start: its payload, or why not. */
  function checkStored(slot) {
    if (!slot || !slot.files) return { ok: false, reason: 'nothing stored' };
    var opened = openManifest(slot.manifest);
    if (!opened.ok) return opened;
    var t0 = now();
    for (var i = 0; i < FILES.length; i++) {
      var why = checkFile(fileOf(opened.payload, FILES[i]), slot.files[FILES[i]]);
      if (why) return { ok: false, reason: why };
    }
    info.timings.hashMs = now() - t0;
    return opened;
  }

  // ---- which bundle runs ---------------------------------------------------------------------

  function markBad(meta, payload, reason) {
    if (!isBad(meta, payload)) meta.bad.push({ version: payload.version, commit: payload.commit, reason: reason, at: new Date().toISOString() });
    while (meta.bad.length > BAD_KEPT) meta.bad.shift();
    note('bad: ' + idOf(payload) + ': ' + reason);
  }

  /**
   * Which bundle runs on this launch. Takes what is in storage, `{meta, current, pending}`, and
   * returns what runs (`stored` is the slot, or null for the packaged one) and what storage holds
   * afterwards. Reads and writes nothing itself.
   *
   * - A downloaded bundle that was on trial at the last launch and never drew a screen is bad.
   * - A pending bundle that still checks out becomes the current one.
   * - The current one runs if it still checks out, is newer than the packaged one and is not bad;
   *   otherwise it is dropped and the packaged one runs.
   */
  function choose(stored) {
    var meta = stored.meta;
    var current = stored.current;
    var pending = stored.pending;
    var notes = [];
    if (meta.trial) {
      markBad(meta, meta.trial, 'did not draw a screen at the last launch');
      meta.trial = null;
    }
    if (pending) {
      var p = checkStored(pending);
      if (!p.ok) notes.push('pending refused: ' + p.reason);
      else if (isBad(meta, p.payload)) notes.push('pending refused: ' + idOf(p.payload) + ' is bad');
      else if (compareVersions(p.payload.version, config.packaged.version) <= 0) notes.push('pending refused: ' + p.payload.version + ' is not newer than the packaged ' + config.packaged.version);
      else {
        current = pending;
        notes.push('pending ' + idOf(p.payload) + ' becomes current');
      }
      pending = null;
    }
    var run = null;
    var payload = null;
    if (current) {
      var c = checkStored(current);
      if (!c.ok) notes.push('stored refused: ' + c.reason);
      else if (isBad(meta, c.payload)) notes.push('stored ' + idOf(c.payload) + ' is bad');
      else if (compareVersions(c.payload.version, config.packaged.version) <= 0) notes.push('stored ' + c.payload.version + ' is not newer than the packaged ' + config.packaged.version);
      else {
        run = current;
        payload = c.payload;
      }
      if (!run) current = null;
    }
    if (payload && !(meta.good && meta.good.version === payload.version && meta.good.commit === payload.commit)) {
      meta.trial = { version: payload.version, commit: payload.commit };
    }
    return { run: run, payload: payload, meta: meta, current: current, pending: pending, notes: notes };
  }

  // ---- loading a bundle ----------------------------------------------------------------------

  function addStyleLink(href) {
    var link = doc.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    doc.head.appendChild(link);
  }

  function addStyleText(text) {
    var style = doc.createElement('style');
    style.textContent = text;
    doc.head.appendChild(style);
  }

  /** Loads scripts one after another: each starts once the one before it has run. */
  function addScripts(list, done) {
    var i = 0;
    var next = function () {
      if (i >= list.length) {
        if (done) done(null);
        return;
      }
      var item = list[i++];
      var script = doc.createElement('script');
      script.async = false;
      if (item.text !== undefined) {
        script.text = item.text;
        doc.body.appendChild(script);
        next();
        return;
      }
      script.onload = next;
      script.onerror = function () {
        if (done) done(item.src);
      };
      script.src = item.src;
      doc.body.appendChild(script);
    };
    next();
  }

  function runPackaged(why) {
    info.running = { version: config.packaged.version, commit: config.packaged.commit, source: 'packaged', load: 'file', why: why || '' };
    // A packaged bundle that never says it started still asks for an update: a newer bundle is
    // how a broken one is fixed without the phone.
    if (config.selfUpdate) {
      g.setTimeout(function () {
        if (!started && !checked) {
          note('the packaged bundle drew no screen in ' + START_WITHIN_MS / 1000 + ' s: asking for an update anyway');
          check();
        }
      }, START_WITHIN_MS);
    }
    addStyleLink('style.css');
    addScripts([{ src: 'polyfills.js' }, { src: 'index.js' }]);
  }

  /**
   * A downloaded bundle: the stylesheet as a <style>, the scripts as Blob scripts made from the
   * stored text, or, if webOS refuses a Blob script, as <script>s whose text is the bundle's.
   */
  function runStored(slot, payload) {
    runningStored = payload;
    info.running = { version: payload.version, commit: payload.commit, source: 'downloaded', load: 'blob', why: '' };
    addStyleText(slot.files['style.css']);
    var blobs = [];
    var asBlob = function (path) {
      var url = g.URL.createObjectURL(new g.Blob([slot.files[path]], { type: 'text/javascript' }));
      blobs.push(url);
      return { src: url };
    };
    var inline = function () {
      info.running.load = 'inline';
      note('a Blob script was refused: the bundle runs as inline scripts');
      addScripts([{ text: slot.files['polyfills.js'] }, { text: slot.files['index.js'] }]);
    };
    var first;
    try {
      first = asBlob('polyfills.js');
    } catch (e) {
      inline();
      return;
    }
    addScripts([first], function (failedSrc) {
      if (failedSrc) {
        inline();
        return;
      }
      addScripts([asBlob('index.js')]);
    });
  }

  /** A downloaded bundle that threw before its first screen, or drew none in time: never again. */
  function fail(reason) {
    if (started || failing || !runningStored) return;
    failing = true;
    var meta = readMeta();
    markBad(meta, runningStored, reason);
    meta.trial = null;
    write('meta', meta);
    write('current', null);
    g.location.reload();
  }

  // ---- asking for an update ------------------------------------------------------------------

  /**
   * Reads a text file: through the app's own JS service on the television (service/index.js
   * `fetch`, which decodes it as UTF-8), else with XMLHttpRequest (a desktop browser).
   * `done(error, text)`, error being `{code, status, detail}`.
   */
  function fetchText(url, done) {
    var finished = false;
    var timer = null;
    var bridge = null;
    var finish = function (err, text) {
      if (finished) return;
      finished = true;
      if (timer) g.clearTimeout(timer);
      if (bridge) {
        try { bridge.cancel(); } catch (e) { /* already ended */ }
      }
      done(err, text);
    };
    timer = g.setTimeout(function () { finish({ code: 'timeout', status: 0, detail: 'no answer in ' + FETCH_TIMEOUT_MS / 1000 + ' s' }); }, FETCH_TIMEOUT_MS);
    if (typeof g.PalmServiceBridge === 'function') {
      var parts = [];
      bridge = new g.PalmServiceBridge();
      bridge.onservicecallback = function (message) {
        var reply;
        try {
          reply = JSON.parse(message);
        } catch (e) {
          return;
        }
        if (reply.returnValue === false) {
          finish({ code: reply.errorText || 'failed', status: reply.status || 0, detail: reply.detail || '' });
          return;
        }
        if (typeof reply.part === 'string') parts.push(reply.part);
        if (reply.done) {
          if (reply.status && reply.status !== 200) finish({ code: 'http', status: reply.status, detail: '' });
          else finish(null, parts.join(''));
        }
      };
      bridge.call(SERVICE + 'fetch', JSON.stringify({ url: url, headers: {}, subscribe: true }));
      return;
    }
    var xhr = new g.XMLHttpRequest();
    xhr.onload = function () {
      if (xhr.status === 200) finish(null, xhr.responseText);
      else finish({ code: 'http', status: xhr.status, detail: '' });
    };
    xhr.onerror = function () { finish({ code: 'failed', status: 0, detail: 'XMLHttpRequest' }); };
    xhr.open('GET', url);
    xhr.send();
  }

  function finishCheck(result, detail) {
    info.lastCheck = { at: new Date().toISOString(), result: result, detail: detail || '' };
    note('update: ' + result + (detail ? ': ' + detail : ''));
    var meta = readMeta();
    meta.lastCheck = info.lastCheck;
    write('meta', meta);
  }

  /** Asks for the manifest; a bundle that passes every check is stored as pending, silently. */
  function check() {
    if (!config.selfUpdate) return;
    checked = true;
    measureStorage();
    info.lastCheck = { at: new Date().toISOString(), result: 'asking', detail: '' };
    fetchText(config.updateBase + 'manifest.json', function (err, text) {
      if (err) {
        finishCheck('failed', err.code + (err.status ? ' ' + err.status : '') + (err.detail ? ' (' + err.detail + ')' : ''));
        return;
      }
      var manifest;
      try {
        manifest = JSON.parse(text);
      } catch (e) {
        finishCheck('refused', 'manifest is not JSON');
        return;
      }
      var opened = openManifest(manifest);
      if (!opened.ok) {
        finishCheck('refused', opened.reason);
        return;
      }
      var p = opened.payload;
      var meta = readMeta();
      var running = info.running;
      if (running && running.version === p.version && running.commit === p.commit) {
        finishCheck('up to date', idOf(p));
        return;
      }
      var floor = config.packaged.version;
      if (running && compareVersions(running.version, floor) > 0) floor = running.version;
      var pending = read('pending');
      if (pending) {
        var held = openManifest(pending.manifest);
        if (held.ok && held.payload.version === p.version && held.payload.commit === p.commit) {
          finishCheck('already pending', idOf(p));
          return;
        }
        if (held.ok && compareVersions(held.payload.version, floor) > 0) floor = held.payload.version;
      }
      if (isBad(meta, p)) {
        finishCheck('refused', idOf(p) + ' is marked bad');
        return;
      }
      if (compareVersions(p.version, floor) <= 0) {
        finishCheck('refused', 'a downgrade: ' + p.version + ' is not newer than ' + floor);
        return;
      }
      download(p, 0, {}, function (why, files) {
        if (why) {
          finishCheck('refused', why);
          return;
        }
        if (!write('pending', { manifest: manifest, files: files })) {
          finishCheck('failed', 'could not store the bundle');
          return;
        }
        info.pending = { version: p.version, commit: p.commit };
        finishCheck('pending', idOf(p) + ': runs at the next launch');
      });
    });
  }

  function download(payload, i, files, done) {
    if (i >= FILES.length) {
      done('', files);
      return;
    }
    var entry = fileOf(payload, FILES[i]);
    fetchText(entry.url, function (err, text) {
      if (err) {
        done(entry.path + ': ' + err.code + (err.status ? ' ' + err.status : ''));
        return;
      }
      var t0 = now();
      var why = checkFile(entry, text);
      info.timings.downloadHashMs = (info.timings.downloadHashMs || 0) + (now() - t0);
      if (why) {
        done(why);
        return;
      }
      files[entry.path] = text;
      download(payload, i + 1, files, done);
    });
  }

  /** localStorage's room and speed on this television, once for each installed shell: 1 MB written and read back. */
  function measureStorage() {
    if (info.storage) return;
    var kept = readMeta().storage;
    if (kept && kept.packaged === config.packaged.version + ' ' + config.packaged.commit) {
      info.storage = kept;
      return;
    }
    var block = new Array(1024 * 1024 + 1).join('x');
    var key = PREFIX + 'probe';
    try {
      var t0 = now();
      g.localStorage.setItem(key, block);
      var t1 = now();
      var back = g.localStorage.getItem(key);
      var t2 = now();
      g.localStorage.removeItem(key);
      var used = 0;
      for (var i = 0; i < g.localStorage.length; i++) {
        var k = g.localStorage.key(i);
        if (k && k.indexOf(PREFIX) === 0) used += (g.localStorage.getItem(k) || '').length;
      }
      info.storage = { ok: back === block, writeMs: t1 - t0, readMs: t2 - t1, shellChars: used };
    } catch (e) {
      info.storage = { ok: false, error: (e && e.name) || String(e) };
    }
    // Measured once for each installed shell, not at every launch.
    info.storage.packaged = config.packaged.version + ' ' + config.packaged.commit;
    var meta = readMeta();
    meta.storage = info.storage;
    write('meta', meta);
  }

  // ---- what the bundle calls -----------------------------------------------------------------

  /** The bundle has drawn its first screen. */
  function onStarted() {
    if (started || failing) return;
    started = true;
    info.startedAt = new Date().toISOString();
    if (runningStored) {
      var meta = readMeta();
      meta.trial = null;
      meta.good = { version: runningStored.version, commit: runningStored.commit };
      write('meta', meta);
    }
    if (config.selfUpdate) g.setTimeout(check, CHECK_AFTER_MS);
  }

  function copy(value) {
    return JSON.parse(JSON.stringify(value));
  }

  /** The bundle's first screen is its crashed one: a downloaded bundle is bad at once. */
  function onCrashed(reason) {
    if (started || failing) return;
    note('the bundle reports: ' + reason);
    if (runningStored) {
      fail(String(reason || 'the first screen crashed'));
      return;
    }
    if (config.selfUpdate && !checked) g.setTimeout(check, CHECK_AFTER_MS);
  }

  g.shidurShell = {
    started: onStarted,
    crashed: onCrashed,
    info: function () {
      return copy(info);
    },
    /** Asks for an update now, for tools/cdp.mjs: the same check as on launch. */
    check: check,
    _: {
      choose: choose,
      openManifest: openManifest,
      checkStored: checkStored,
      checkFile: checkFile,
      compareVersions: compareVersions,
      sha512: sha512,
      utf8: utf8,
    },
  };

  // ---- launch --------------------------------------------------------------------------------

  function boot() {
    if (!config.selfUpdate) {
      runPackaged('this build does not update itself');
      return;
    }
    var meta = readMeta();
    var chosen;
    try {
      chosen = choose({ meta: meta, current: read('current'), pending: read('pending') });
    } catch (e) {
      note('choosing failed: ' + (e && e.message));
      runPackaged('choosing failed');
      return;
    }
    for (var i = 0; i < chosen.notes.length; i++) note(chosen.notes[i]);
    info.bad = copy(chosen.meta.bad);
    info.lastCheck = chosen.meta.lastCheck || null;
    write('meta', chosen.meta);
    write('pending', chosen.pending);
    write('current', chosen.current);
    if (!chosen.run) {
      runPackaged(chosen.notes.join('; '));
      return;
    }
    g.addEventListener('error', function (event) {
      if (!started) fail('threw while starting: ' + ((event && event.message) || 'an error'));
    });
    g.setTimeout(function () {
      if (!started) fail('drew no screen in ' + START_WITHIN_MS / 1000 + ' s');
    }, START_WITHIN_MS);
    runStored(chosen.run, chosen.payload);
  }

  if (!config.noBoot) boot();
})(window, SHELL_CONFIG);
