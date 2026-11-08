#!/usr/bin/env node
/**
 * Drives a page on the television over its DevTools WebSocket, for when the DevTools page itself
 * cannot be opened (it shows blank in a phone's Chrome). Runs in Termux with Node 22 or newer,
 * which has WebSocket built in; nothing to install.
 *
 *   TV=<address> node tools/cdp.mjs list                 pages the television offers
 *   TV=<address> node tools/cdp.mjs state                what the page says about itself now
 *                                                        (the app's shidur.state(), or the spike's)
 *   TV=<address> node tools/cdp.mjs selftest [steps]     spike only: walks the list in every list mode
 *   TV=<address> node tools/cdp.mjs mode <name> <value>  spike only: e.g. mode scale zoom
 *   TV=<address> node tools/cdp.mjs key <key> [times]    Up Down Left Right Ok Back Red Green Yellow Blue Play Pause Stop 0-9
 *   TV=<address> node tools/cdp.mjs eval '<expression>'
 *   TV=<address> node tools/cdp.mjs shot [file.png]       what Chromium itself draws (the video plane is
 *                                                        the television's and shows black): if the
 *                                                        interface is in the picture but not on the
 *                                                        screen, the television covered it
 *   TV=<address> node tools/cdp.mjs reload
 *
 * The port is 9998 unless PORT says otherwise; MATCH picks the page whose address contains it
 * (default: "shidur"). Only one client can attach at a time: stop ares-inspect first.
 */
import { writeFileSync } from 'node:fs';

const host = process.env.TV;
const port = process.env.PORT || '9998';
const match = process.env.MATCH || 'shidur';
const [command, ...args] = process.argv.slice(2);

const KEYS = {
  Up: 38, Down: 40, Left: 37, Right: 39, Ok: 13, Back: 461, Red: 403, Green: 404, Yellow: 405, Blue: 406,
  Play: 415, Pause: 19, Stop: 413,
};
for (let d = 0; d <= 9; d++) KEYS[String(d)] = 48 + d;

function die(message) {
  console.error(`cdp: ${message}`);
  process.exit(1);
}

if (!host) die('set TV to the television\'s address, e.g. TV=192.0.2.10 node tools/cdp.mjs list');
if (!command) die('no command; see the top of tools/cdp.mjs');
if (typeof WebSocket !== 'function') die(`this Node (${process.version}) has no WebSocket; Node 22 or newer is needed`);

async function pages() {
  const res = await fetch(`http://${host}:${port}/json/list`);
  if (!res.ok) die(`http://${host}:${port}/json/list answered ${res.status}`);
  return res.json();
}

async function attach() {
  const all = await pages();
  const page = all.find((p) => p.webSocketDebuggerUrl && p.url.includes(match));
  if (!page) {
    const seen = all.map((p) => p.url).join(', ') || 'none';
    die(`no page whose address holds "${match}" is free to attach to (pages: ${seen}); is the app open, and is ares-inspect stopped?`);
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((ok, fail) => {
    ws.addEventListener('open', ok, { once: true });
    ws.addEventListener('error', () => fail(new Error(`cannot open ${page.webSocketDebuggerUrl}`)), { once: true });
  });
  let next = 1;
  const waiting = new Map();
  ws.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data);
    const w = waiting.get(msg.id);
    if (!w) return;
    waiting.delete(msg.id);
    if (msg.error) w.fail(new Error(msg.error.message));
    else w.ok(msg.result);
  });
  // A page that reloads or closes never answers what was pending: fail it rather than hang.
  ws.addEventListener('close', () => {
    for (const w of waiting.values()) w.fail(new Error('the page closed the connection'));
    waiting.clear();
  });
  const send = (method, params = {}) => new Promise((ok, fail) => {
    const id = next++;
    const timer = setTimeout(() => {
      waiting.delete(id);
      fail(new Error(`${method}: no answer in 30 s`));
    }, 30000);
    waiting.set(id, {
      ok: (v) => { clearTimeout(timer); ok(v); },
      fail: (e) => { clearTimeout(timer); fail(e); },
    });
    ws.send(JSON.stringify({ id, method, params }));
  });
  return { page, send, close: () => ws.close() };
}

async function evaluate(send, expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) die(`the page threw: ${r.exceptionDetails.exception?.description || r.exceptionDetails.text}`);
  return r.result.value;
}

async function press(send, name) {
  const code = KEYS[name];
  if (!code) die(`unknown key "${name}"; one of ${Object.keys(KEYS).join(' ')}`);
  const base = { windowsVirtualKeyCode: code, nativeVirtualKeyCode: code };
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
}

/** The app (`com.idanck.shidur`, src/ui/debug.ts) or the spike, whichever the page is. */
const STATE = '(window.shidur || window.spike).state().report';

const print = (value) => console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));

if (command === 'list') {
  for (const p of await pages()) console.log(`${p.title}\n  ${p.url}\n  ${p.webSocketDebuggerUrl || '(a client is attached)'}`);
  process.exit(0);
}

const { page, send, close } = await attach();
console.error(`cdp: attached to ${page.url}`);
try {
  if (command === 'eval') print(await evaluate(send, args.join(' ')));
  else if (command === 'state') print(await evaluate(send, STATE));
  else if (command === 'selftest') print(await evaluate(send, `spike.selfTest(${Number(args[0]) || 20})`));
  else if (command === 'mode') {
    const [name, raw] = args;
    const value = name === 'videos' ? Number(raw) : raw;
    await evaluate(send, `spike.set(${JSON.stringify(name)}, ${JSON.stringify(value)})`);
    print(await evaluate(send, STATE));
  } else if (command === 'key') {
    const times = Number(args[1]) || 1;
    for (let i = 0; i < times; i++) {
      await press(send, args[0]);
      await new Promise((r) => setTimeout(r, 120));
    }
    await new Promise((r) => setTimeout(r, 300));
    print(await evaluate(send, STATE));
  } else if (command === 'shot') {
    const file = args[0] || `shot-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.png`;
    const r = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log(file);
  } else if (command === 'reload') await send('Page.reload', { ignoreCache: true });
  else die(`unknown command "${command}"`);
} catch (e) {
  die(e.message);
} finally {
  close();
}
