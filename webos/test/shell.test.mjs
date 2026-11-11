// @ts-nocheck -- runs the plain ES5 shell (shell/boot.js) in a fresh global, as the page would.
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runInNewContext } from 'node:vm';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

/*
 * shell/boot.js decides which bundle runs and fetches updates (ADR-057, 0.4.0). Here it runs in a
 * new global with a fake page (document, localStorage, timers, Blob URLs) and a fake Luna bus that
 * reads through the real service/net.js from a local server: so a bundle's bytes go the same way
 * as on the television, from the server through the service's UTF-8 decoding and its 256 K parts,
 * into localStorage and back. Manifests are signed with openssl, as tools/publish-bundle.sh signs
 * them on the Pi, and verified with the shell's own tweetnacl.
 */

const require = createRequire(import.meta.url);
const net = require('../service/net.js');
const root = new URL('..', import.meta.url).pathname;
const NACL = readFileSync(join(root, 'node_modules/tweetnacl/nacl-fast.min.js'), 'utf8');
const BOOT = readFileSync(join(root, 'shell/boot.js'), 'utf8');
const BASE = 'https://updates.idanck.com/webos/';
const APP = 'com.idanck.shidur';

const dir = mkdtempSync(join(tmpdir(), 'shidur-shell-'));

function openssl(args, input) {
  return execFileSync('openssl', args, { input, stdio: ['pipe', 'pipe', 'pipe'] });
}

/** A key pair made as publish-bundle.sh keygen makes it: the public half as raw base64. */
function keyPair(name) {
  const key = join(dir, `${name}.pem`);
  openssl(['genpkey', '-algorithm', 'ed25519', '-out', key]);
  const der = openssl(['pkey', '-in', key, '-pubout', '-outform', 'DER']);
  return { key, publicKey: der.subarray(der.length - 32).toString('base64') };
}

const signer = keyPair('signer');
const stranger = keyPair('stranger');

function sign(keyFile, bytes) {
  const file = join(dir, `payload-${Math.random().toString(36).slice(2)}`);
  writeFileSync(file, bytes);
  return openssl(['pkeyutl', '-sign', '-inkey', keyFile, '-rawin', '-in', file]);
}

const sha512 = (text) => createHash('sha512').update(Buffer.from(text, 'utf8')).digest('hex');

/** A bundle's three files: Hebrew in each, and an index.js longer than one service part (256 K). */
function bundleFiles(tag) {
  return {
    'style.css': `/* ${tag} */ .live::before { content: 'שידור חי'; }\n`,
    'polyfills.js': `/* ${tag} polyfills: אין */\nwindow.__polyfills = '${tag}';\n`,
    'index.js': `window.__bundle = '${tag}'; window.__text = 'שלום עולם 👋';\n// ${'א'.repeat(300 * 1024)}\n`,
  };
}

/** bundle.json for a version and commit, as the build writes it, and its manifest signed with `keyFile`. */
function release(version, commit, files, { keyFile = signer.key, shell = 1, app = APP, base = BASE } = {}) {
  const payload = {
    app,
    version,
    commit,
    shell,
    built: '2026-11-11T00:00:00.000Z',
    files: ['style.css', 'polyfills.js', 'index.js'].map((path) => ({
      path,
      url: `${base}bundles/${version}-${commit}/${path}`,
      size: Buffer.byteLength(files[path], 'utf8'),
      sha512: sha512(files[path]),
    })),
  };
  const bytes = Buffer.from(`${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  return {
    payload,
    manifest: { payload: bytes.toString('base64'), signature: sign(keyFile, bytes).toString('base64') },
  };
}

/** What the local server serves, by the update host's path. */
const hosted = new Map();
let server;
let origin;

beforeAll(async () => {
  server = createServer((req, res) => {
    const body = hosted.get(req.url);
    if (body === undefined) {
      res.writeHead(404);
      return res.end('not found');
    }
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(Buffer.from(body, 'utf8'));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});

afterAll(() => server.close());

function publish(version, commit, files, options) {
  const r = release(version, commit, files, options);
  for (const path of Object.keys(files)) hosted.set(`/webos/bundles/${version}-${commit}/${path}`, files[path]);
  hosted.set('/webos/manifest.json', JSON.stringify(r.manifest));
  return r;
}

/** localStorage as a page has it: strings only. Shared between launches like the television's. */
function makeStorage() {
  const map = new Map();
  return {
    map,
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    key: (i) => [...map.keys()][i] ?? null,
    get length() {
      return map.size;
    },
  };
}

/**
 * One launch of the app: a fresh global with the shell in it. `refuseBlob` makes Blob scripts fail
 * to load, as webOS 5 might. Returns the page's record of what was loaded, its timers and the shell.
 */
function launch(storage, { selfUpdate = true, publicKey = signer.publicKey, packaged = { version: '0.4.0', commit: 'aaaaaaa1' }, refuseBlob = false, updateBase = BASE } = {}) {
  const loaded = [];
  const timers = [];
  const listeners = {};
  const blobs = new Map();
  let reloads = 0;
  const node = (tag) => {
    const el = { tag, text: '', textContent: '' };
    return el;
  };
  const append = (el) => {
    if (el.tag === 'link') loaded.push(`link ${el.href}`);
    else if (el.tag === 'style') loaded.push(`style ${el.textContent.slice(0, 30)}`);
    else if (el.tag === 'script') {
      if (el.src) {
        const blob = el.src.startsWith('blob:');
        loaded.push(`script ${blob ? `blob ${blobs.get(el.src).slice(0, 30)}` : el.src}`);
        queueMicrotask(() => (blob && refuseBlob ? el.onerror && el.onerror() : el.onload && el.onload()));
      } else loaded.push(`inline ${el.text.slice(0, 30)}`);
    }
  };
  const bridges = [];
  class PalmServiceBridge {
    constructor() {
      bridges.push(this);
      this.onservicecallback = null;
      this.handle = null;
    }
    call(uri, params) {
      expect(uri).toBe(`luna://${APP}.service/fetch`);
      const p = JSON.parse(params);
      const url = p.url.replace('https://updates.idanck.com', origin);
      this.handle = net.newHandle();
      let seq = 0;
      net.readText(url, p.headers, (part) => {
        this.onservicecallback?.(JSON.stringify({ returnValue: true, seq: seq++, part }));
      }, this.handle).then(
        (meta) => this.onservicecallback?.(JSON.stringify({ returnValue: true, done: true, parts: seq, status: meta.status })),
        (err) => this.onservicecallback?.(JSON.stringify({ returnValue: false, errorText: err.code, status: err.status || 0, detail: err.detail || '' })),
      );
    }
    cancel() {
      this.handle?.abort();
    }
  }
  const g = {
    document: {
      createElement: node,
      head: { appendChild: append },
      body: { appendChild: append },
    },
    localStorage: storage,
    atob: (s) => Buffer.from(s, 'base64').toString('latin1'),
    setTimeout: (fn, ms) => {
      timers.push({ fn, ms });
      return timers.length;
    },
    clearTimeout: (id) => {
      if (timers[id - 1]) timers[id - 1].fn = null;
    },
    addEventListener: (type, fn) => {
      (listeners[type] ||= []).push(fn);
    },
    location: { reload: () => reloads++ },
    URL: {
      createObjectURL: (blob) => {
        const url = `blob:file:///${blobs.size}`;
        blobs.set(url, blob.text);
        return url;
      },
    },
    Blob: class {
      constructor(parts) {
        this.text = parts.join('');
      }
    },
    PalmServiceBridge,
  };
  g.window = g;
  g.self = g;
  const config = {
    app: APP,
    shell: 1,
    selfUpdate,
    packaged,
    checkAfterMs: 20000,
    startWithinMs: 60000,
    ...(selfUpdate ? { updateBase, publicKey } : {}),
  };
  runInNewContext(`${selfUpdate ? NACL : ''}\nvar SHELL_CONFIG = ${JSON.stringify(config)};\n${BOOT}`, g);
  const shell = g.shidurShell;
  return {
    g,
    shell,
    loaded,
    timers,
    get reloads() {
      return reloads;
    },
    fire: (type, event) => (listeners[type] || []).forEach((fn) => fn(event)),
    /** Runs the timers that are due at `ms` after launch (each once; not those they set). */
    advance(ms) {
      for (const t of [...timers]) {
        if (t.fn && t.ms <= ms) {
          const fn = t.fn;
          t.fn = null;
          fn();
        }
      }
    },
    /** Waits for the shell's update check to settle (the fetches are real requests). */
    async settled() {
      for (let i = 0; i < 200; i++) {
        const c = shell.info().lastCheck;
        if (c && c.result !== 'asking') return c;
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error('the check never settled');
    },
  };
}

/** A launch that draws its screen and then asks for the update, as on the television. */
async function launchAndCheck(storage, options) {
  const page = launch(storage, options);
  await Promise.resolve();
  page.shell.started();
  page.advance(20000);
  const check = await page.settled();
  return { page, check };
}

describe('the shell (shell/boot.js)', () => {
  it('compares versions as numbers', () => {
    const { compareVersions } = launch(makeStorage()).shell._;
    expect(compareVersions('0.4.10', '0.4.9')).toBe(1);
    expect(compareVersions('0.4.0', '0.4.0')).toBe(0);
    expect(compareVersions('0.4.0', '1.0.0')).toBe(-1);
  });

  it('hashes text as its UTF-8 bytes, Hebrew and emoji included', () => {
    const { sha512: shellHash, utf8 } = launch(makeStorage()).shell._;
    const text = 'שלום עולם 👋 — ü';
    expect(shellHash(text)).toBe(sha512(text));
    expect(Buffer.from(utf8(text)).equals(Buffer.from(text, 'utf8'))).toBe(true);
  });

  it('verifies a manifest openssl signed, and refuses one signed with another key or changed after signing', () => {
    const { openManifest } = launch(makeStorage()).shell._;
    const files = bundleFiles('a');
    const good = release('0.4.1', 'bbbbbbb1', files);
    expect(openManifest(good.manifest)).toMatchObject({ ok: true, payload: { version: '0.4.1' } });
    expect(openManifest(release('0.4.1', 'bbbbbbb1', files, { keyFile: stranger.key }).manifest)).toEqual({ ok: false, reason: 'signature does not verify' });
    const changed = Buffer.from(good.manifest.payload, 'base64').toString('utf8').replace('0.4.1', '0.4.9');
    expect(openManifest({ ...good.manifest, payload: Buffer.from(changed).toString('base64') }).reason).toBe('signature does not verify');
    expect(openManifest({ payload: good.manifest.payload }).reason).toBe('not a manifest');
  });

  it('refuses a signed bundle for another app, another shell, or with files off the update host', () => {
    const { openManifest } = launch(makeStorage()).shell._;
    const files = bundleFiles('a');
    expect(openManifest(release('0.4.1', 'bbbbbbb1', files, { app: 'com.other' }).manifest).reason).toContain('another app');
    expect(openManifest(release('0.4.1', 'bbbbbbb1', files, { shell: 2 }).manifest).reason).toContain('built for shell 2');
    expect(openManifest(release('0.4.1', 'bbbbbbb1', files, { base: 'https://evil.example/webos/' }).manifest).reason).toContain('file address');
  });

  it('runs the packaged bundle when nothing is stored: stylesheet, then polyfills, then the interface', async () => {
    const page = launch(makeStorage());
    await Promise.resolve();
    await Promise.resolve();
    expect(page.loaded).toEqual(['link style.css', 'script polyfills.js', 'script index.js']);
    expect(page.shell.info().running).toMatchObject({ version: '0.4.0', source: 'packaged', load: 'file' });
  });

  it('downloads a newer bundle through the service, stores it as pending, and runs it at the next launch', async () => {
    const storage = makeStorage();
    const files = bundleFiles('b');
    publish('0.4.1', 'bbbbbbb1', files);
    const { page, check } = await launchAndCheck(storage);
    expect(check).toMatchObject({ result: 'pending' });
    expect(page.shell.info().pending).toEqual({ version: '0.4.1', commit: 'bbbbbbb1' });
    // The text went through the service in more than one part and came back byte for byte.
    const stored = JSON.parse(storage.getItem('shidur.shell.pending'));
    expect(sha512(stored.files['index.js'])).toBe(sha512(files['index.js']));
    expect(files['index.js'].length).toBeGreaterThan(net.PART_CHARS);

    const next = launch(storage);
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(next.shell.info().running).toMatchObject({ version: '0.4.1', commit: 'bbbbbbb1', source: 'downloaded', load: 'blob' });
    expect(next.loaded[0]).toMatch(/^style \/\* b \*\//);
    expect(next.loaded.slice(1)).toEqual([expect.stringMatching(/^script blob \/\* b polyfills/), expect.stringMatching(/^script blob window.__bundle = 'b'/)]);
    // On trial until it draws a screen; then good, and not on trial at the launch after.
    expect(JSON.parse(storage.getItem('shidur.shell.meta')).trial).toEqual({ version: '0.4.1', commit: 'bbbbbbb1' });
    next.shell.started();
    const meta = JSON.parse(storage.getItem('shidur.shell.meta'));
    expect(meta.trial).toBeNull();
    expect(meta.good).toEqual({ version: '0.4.1', commit: 'bbbbbbb1' });
    const third = launch(storage);
    expect(third.shell.info().running.source).toBe('downloaded');
    expect(JSON.parse(storage.getItem('shidur.shell.meta')).trial).toBeNull();
  });

  it('says up to date when the manifest names what runs, and refuses a downgrade', async () => {
    const storage = makeStorage();
    publish('0.4.2', 'ccccccc1', bundleFiles('c'));
    await launchAndCheck(storage);
    const second = launch(storage);
    second.shell.started();
    second.advance(20000);
    expect(await second.settled()).toMatchObject({ result: 'up to date' });

    publish('0.4.1', 'bbbbbbb1', bundleFiles('b'));
    const third = launch(storage);
    third.shell.started();
    third.advance(20000);
    expect(await third.settled()).toMatchObject({ result: 'refused', detail: expect.stringContaining('a downgrade') });

    // Not newer than the packaged bundle either.
    publish('0.3.9', 'ddddddd1', bundleFiles('d'));
    expect((await launchAndCheck(makeStorage())).check).toMatchObject({ result: 'refused', detail: expect.stringContaining('a downgrade') });
  });

  it('refuses a file whose bytes are not the manifest\'s', async () => {
    const files = bundleFiles('e');
    publish('0.4.3', 'eeeeeee1', files);
    hosted.set('/webos/bundles/0.4.3-eeeeeee1/index.js', files['index.js'].replace("'e'", "'x'"));
    const { check } = await launchAndCheck(makeStorage());
    expect(check).toMatchObject({ result: 'refused', detail: 'index.js has another SHA-512' });
  });

  it('refuses a manifest signed with another key, and a missing one fails without harm', async () => {
    publish('0.4.4', 'fffffff1', bundleFiles('f'), { keyFile: stranger.key });
    expect((await launchAndCheck(makeStorage())).check).toMatchObject({ result: 'refused', detail: 'signature does not verify' });
    hosted.delete('/webos/manifest.json');
    const { page, check } = await launchAndCheck(makeStorage());
    expect(check).toMatchObject({ result: 'failed', detail: expect.stringContaining('404') });
    expect(page.shell.info().running.source).toBe('packaged');
  });

  it('checks a stored bundle again before running it: one changed in storage runs the packaged one', async () => {
    const storage = makeStorage();
    publish('0.4.5', 'abcdef01', bundleFiles('g'));
    await launchAndCheck(storage);
    const pending = JSON.parse(storage.getItem('shidur.shell.pending'));
    pending.files['index.js'] = pending.files['index.js'].slice(0, 1000);
    storage.setItem('shidur.shell.pending', JSON.stringify(pending));
    const next = launch(storage);
    expect(next.shell.info().running).toMatchObject({ source: 'packaged', why: expect.stringContaining('index.js is') });
    expect(storage.getItem('shidur.shell.pending')).toBeNull();
  });

  it('marks a bundle bad that throws before its first screen, reloads, and never runs it again', async () => {
    const storage = makeStorage();
    publish('0.4.6', 'abcdef02', bundleFiles('h'));
    await launchAndCheck(storage);
    const broken = launch(storage);
    expect(broken.shell.info().running.source).toBe('downloaded');
    broken.fire('error', { message: 'TypeError: x is undefined' });
    expect(broken.reloads).toBe(1);
    const meta = JSON.parse(storage.getItem('shidur.shell.meta'));
    expect(meta.bad).toEqual([expect.objectContaining({ version: '0.4.6', commit: 'abcdef02', reason: expect.stringContaining('threw while starting') })]);
    const after = launch(storage);
    expect(after.shell.info().running.source).toBe('packaged');
    // The same bundle is refused when the check finds it again.
    after.shell.started();
    after.advance(20000);
    expect(await after.settled()).toMatchObject({ result: 'refused', detail: expect.stringContaining('is marked bad') });
  });

  it('marks a bundle bad whose first screen is the crashed one', async () => {
    const storage = makeStorage();
    publish('0.4.12', 'abcdef08', bundleFiles('n'));
    await launchAndCheck(storage);
    const crashed = launch(storage);
    crashed.shell.crashed('the first screen crashed: TypeError');
    expect(crashed.reloads).toBe(1);
    expect(JSON.parse(storage.getItem('shidur.shell.meta')).bad[0]).toMatchObject({ version: '0.4.12', reason: expect.stringContaining('crashed') });
    expect(launch(storage).shell.info().running.source).toBe('packaged');
  });

  it('a packaged bundle that never says it started still asks for an update', async () => {
    publish('0.4.13', 'abcdef09', bundleFiles('o'));
    const page = launch(makeStorage());
    page.advance(60000);
    expect(await page.settled()).toMatchObject({ result: 'pending' });
    expect(page.shell.info().events.join(' ')).toContain('asking for an update anyway');
  });

  it('marks a bundle bad that draws no screen in time', async () => {
    const storage = makeStorage();
    publish('0.4.7', 'abcdef03', bundleFiles('i'));
    await launchAndCheck(storage);
    const slow = launch(storage);
    slow.advance(60000);
    expect(slow.reloads).toBe(1);
    expect(JSON.parse(storage.getItem('shidur.shell.meta')).bad[0].reason).toContain('drew no screen');
  });

  it('marks a bundle bad that was still on trial when the app was last closed', async () => {
    const storage = makeStorage();
    publish('0.4.8', 'abcdef04', bundleFiles('j'));
    await launchAndCheck(storage);
    launch(storage); // runs on trial, closed before its first screen
    const next = launch(storage);
    expect(next.shell.info().running.source).toBe('packaged');
    expect(next.shell.info().bad[0]).toMatchObject({ version: '0.4.8', reason: expect.stringContaining('did not draw a screen') });
  });

  it('drops a stored bundle that is not newer than a newly installed packaged one', async () => {
    const storage = makeStorage();
    publish('0.4.9', 'abcdef05', bundleFiles('k'));
    await launchAndCheck(storage);
    const reinstalled = launch(storage, { packaged: { version: '0.5.0', commit: 'aaaaaaa2' } });
    expect(reinstalled.shell.info().running).toMatchObject({ version: '0.5.0', source: 'packaged' });
    expect(storage.getItem('shidur.shell.current')).toBeNull();
  });

  it('falls back to inline scripts when webOS refuses a Blob script', async () => {
    const storage = makeStorage();
    publish('0.4.10', 'abcdef06', bundleFiles('l'));
    await launchAndCheck(storage);
    const page = launch(storage, { refuseBlob: true });
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(page.shell.info().running.load).toBe('inline');
    expect(page.loaded.slice(-2)).toEqual([expect.stringMatching(/^inline \/\* l polyfills/), expect.stringMatching(/^inline window.__bundle = 'l'/)]);
  });

  it('measures localStorage at the first check of an installed shell, and keeps the result', async () => {
    publish('0.4.11', 'abcdef07', bundleFiles('m'));
    const storage = makeStorage();
    const { page } = await launchAndCheck(storage);
    const measured = page.shell.info().storage;
    expect(measured).toMatchObject({ ok: true, writeMs: expect.any(Number), readMs: expect.any(Number), packaged: '0.4.0 aaaaaaa1' });
    const set = storage.setItem;
    let probes = 0;
    storage.setItem = (k, v) => {
      if (k === 'shidur.shell.probe') probes++;
      return set(k, v);
    };
    const { page: next } = await launchAndCheck(storage);
    expect(probes).toBe(0);
    expect(next.shell.info().storage).toEqual(measured);
  });

  it('a store build only loads the packaged bundle and never asks for an update', async () => {
    const page = launch(makeStorage(), { selfUpdate: false });
    await Promise.resolve();
    page.shell.started();
    expect(page.timers.filter((t) => t.fn)).toHaveLength(0);
    expect(page.shell.info()).toMatchObject({ selfUpdate: false, running: { source: 'packaged' } });
  });
});

/*
 * tools/publish-bundle.sh, run against a local stand-in for GitHub's API and the update host: it
 * makes the key, takes a run's artifact, checks it, signs it and uploads it; the shell then
 * verifies what it published with the public key keygen printed, and downloads the bundle.
 */
describe('tools/publish-bundle.sh', () => {
  const SCRIPT = join(root, 'tools/publish-bundle.sh');
  const REPO = 'owner/repo';
  const COMMIT = '0123abcd';
  const HEAD = `${COMMIT}${'e'.repeat(32)}`;
  let api;
  let apiOrigin;
  /** The update host's objects, by key, and the zip the "artifact" serves. */
  const objects = new Map();
  let zipBytes = Buffer.alloc(0);
  let repoKey = '';
  const uploads = [];
  const UPLOAD_TOKEN = 'test-upload-token';

  beforeAll(async () => {
    api = createServer(async (req, res) => {
      const url = new URL(req.url, 'http://x');
      const send = (status, body, type = 'application/json') => {
        res.writeHead(status, { 'content-type': type });
        res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
      };
      if (req.method === 'PUT' && url.pathname.startsWith('/publish/')) {
        if (req.headers.authorization !== `Bearer ${UPLOAD_TOKEN}`) return send(401, 'unauthorized', 'text/plain');
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const key = url.pathname.slice('/publish/'.length);
        objects.set(key, Buffer.concat(chunks));
        uploads.push(key);
        return send(200, { ok: true, key });
      }
      if (url.pathname.startsWith('/repos/') && req.headers.authorization !== 'Bearer test-gh-token') return send(401, { message: 'Bad credentials' });
      switch (url.pathname) {
        case `/repos/${REPO}/actions/workflows/webos.yml/runs`:
          expect(url.searchParams.get('branch')).toBe('main');
          return send(200, { workflow_runs: [{ id: 7 }] });
        case `/repos/${REPO}/actions/runs/7`:
          return send(200, { id: 7, head_branch: 'main', conclusion: 'success', head_sha: HEAD });
        case `/repos/${REPO}/actions/runs/7/artifacts`:
          return send(200, { artifacts: [{ name: 'shidur-webos-ipk', expired: false, archive_download_url: `${apiOrigin}/nope` }, { name: 'shidur-webos-bundle', expired: false, archive_download_url: `${apiOrigin}/artifact.zip` }] });
        case '/artifact.zip':
          return send(200, zipBytes, 'application/zip');
        case `/repos/${REPO}/contents/webos/shell/public-key.txt`:
          expect(url.searchParams.get('ref')).toBe(HEAD);
          return send(200, `${repoKey}\n`, 'text/plain');
      }
      const key = url.pathname.slice(1);
      if (req.method === 'GET' && objects.has(key)) return send(200, objects.get(key), 'text/plain; charset=utf-8');
      return send(404, 'not found', 'text/plain');
    });
    await new Promise((resolve) => api.listen(0, '127.0.0.1', resolve));
    apiOrigin = `http://127.0.0.1:${api.address().port}`;
  });

  afterAll(() => api.close());

  function keygen() {
    const out = join(dir, `key-${Math.random().toString(36).slice(2)}.env`);
    const printed = execFileSync('bash', [SCRIPT, 'keygen', out], { encoding: 'utf8' });
    const line = readFileSync(out, 'utf8').trim();
    expect(line).toMatch(/^SHIDUR_WEBOS_SIGNING_KEY_B64=[A-Za-z0-9+/=]+$/);
    // Nothing of the private key is printed.
    expect(printed).not.toContain(line.split('=')[1].slice(0, 40));
    return { privateB64: line.slice('SHIDUR_WEBOS_SIGNING_KEY_B64='.length), publicKey: printed.trim().split('\n').pop() };
  }

  /** An artifact as CI keeps it: the three files and bundle.json, at the zip's root. */
  function artifact(version, files, tamper) {
    const work = mkdtempSync(join(dir, 'artifact-'));
    const base = `${apiOrigin}/webos/bundles/${version}-${COMMIT}`;
    const bundle = {
      app: APP,
      version,
      commit: COMMIT,
      shell: 1,
      built: '2026-11-11T00:00:00.000Z',
      files: ['style.css', 'polyfills.js', 'index.js'].map((path) => ({ path, url: `${base}/${path}`, size: Buffer.byteLength(files[path]), sha512: sha512(files[path]) })),
    };
    for (const path of Object.keys(files)) writeFileSync(join(work, path), tamper && path === 'index.js' ? `${files[path]}//` : files[path]);
    writeFileSync(join(work, 'bundle.json'), `${JSON.stringify(bundle, null, 2)}\n`);
    execFileSync('zip', ['-q', '-j', join(work, 'a.zip'), ...['style.css', 'polyfills.js', 'index.js', 'bundle.json'].map((f) => join(work, f))]);
    zipBytes = readFileSync(join(work, 'a.zip'));
  }

  /** Runs the script without blocking: the stand-in servers answer from this same process. */
  async function run(key, args = [], token = UPLOAD_TOKEN) {
    try {
      const { stdout: out } = await promisify(execFile)('bash', [SCRIPT, ...args], {
        encoding: 'utf8',
        env: {
          PATH: process.env.PATH,
          SHIDUR_REPO: REPO,
          SHIDUR_UPDATE_HOST: apiOrigin,
          SHIDUR_GITHUB_API: apiOrigin,
          SHIDUR_WEBOS_GITHUB_TOKEN: 'test-gh-token',
          SHIDUR_WEBOS_SIGNING_KEY_B64: key.privateB64,
          TIVICOPY_UPDATES_UPLOAD_TOKEN: token,
        },
      });
      return { ok: true, out };
    } catch (e) {
      return { ok: false, out: `${e.stdout}${e.stderr}` };
    }
  }

  it('publishes a run\'s bundle that the shell then verifies and downloads', async () => {
    const key = keygen();
    repoKey = key.publicKey;
    const files = bundleFiles('p');
    artifact('0.4.1', files);
    const dry = await run(key, ['--dry-run']);
    expect(dry.out).toContain('dry run: would publish 0.4.1');
    expect(uploads).toEqual([]);

    const result = await run(key);
    expect(result.out).toContain('published 0.4.1 0123abcd');
    expect(uploads).toEqual(['webos/bundles/0.4.1-0123abcd/style.css', 'webos/bundles/0.4.1-0123abcd/polyfills.js', 'webos/bundles/0.4.1-0123abcd/index.js', 'webos/manifest.json']);

    const manifest = JSON.parse(objects.get('webos/manifest.json').toString('utf8'));
    const page = launch(makeStorage(), { publicKey: key.publicKey, updateBase: `${apiOrigin}/webos/` });
    expect(page.shell._.openManifest(manifest)).toMatchObject({ ok: true, payload: { version: '0.4.1', commit: COMMIT } });
    page.shell.started();
    page.advance(20000);
    expect(await page.settled()).toMatchObject({ result: 'pending', detail: expect.stringContaining('0.4.1 0123abcd') });
  });

  let lastKey;

  it('refuses an artifact whose files are not its bundle.json\'s, and a shell with another public key', async () => {
    lastKey = keygen();
    repoKey = lastKey.publicKey;
    artifact('0.4.2', bundleFiles('q'), true);
    expect((await run(lastKey)).out).toContain('index.js: size is not bundle.json');
    artifact('0.4.2', bundleFiles('q'));
    repoKey = signer.publicKey;
    expect((await run(lastKey)).out).toContain('carries another public key');
    repoKey = lastKey.publicKey;
    const before = uploads.length;
    expect((await run(lastKey, [], 'wrong-token')).out).toContain('upload of webos/bundles/0.4.2-0123abcd/style.css failed');
    expect(uploads.length).toBe(before);
    expect((await run(lastKey)).out).toContain('published 0.4.2');
    // Not newer than what is now published.
    expect((await run(lastKey)).out).toContain('0.4.2 is not newer than the published 0.4.2');
  });
});
