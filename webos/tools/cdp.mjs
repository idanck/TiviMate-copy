#!/usr/bin/env node
/**
 * Drives a page on the television over its DevTools WebSocket, for when the DevTools page itself
 * cannot be opened (it shows blank in a phone's Chrome). Runs in Termux with Node 22 or newer,
 * which has WebSocket built in; nothing to install.
 *
 *   TV=<address> node tools/cdp.mjs list                 pages the television offers
 *   TV=<address> node tools/cdp.mjs state                what the spike's diagnostics say now
 *   TV=<address> node tools/cdp.mjs selftest [steps]     walks the list in every list mode
 *   TV=<address> node tools/cdp.mjs mode <name> <value>  e.g. mode scale zoom, mode list translate
 *   TV=<address> node tools/cdp.mjs key <key> [times]    Up Down Left Right Ok Back Red Green Yellow Blue 0-9
 *   TV=<address> node tools/cdp.mjs eval '<expression>'
 *   TV=<address> node tools/cdp.mjs reload
 *
 * The port is 9998 unless PORT says otherwise; MATCH picks the page whose address contains it
 * (default: "shidur"). Only one client can attach at a time: stop ares-inspect first.
 */
const host = process.env.TV;
const port = process.env.PORT || '9998';
const match = process.env.MATCH || 'shidur';
const [command, ...args] = process.argv.slice(2);

const KEYS = {
  Up: 38, Down: 40, Left: 37, Right: 39, Ok: 13, Back: 461, Red: 403, Green: 404, Yellow: 405, Blue: 406,
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
  const page = all.find((p) => p.webSocketDebuggerUrl && p.url.includes(match)) ||
    all.find((p) => p.webSocketDebuggerUrl);
  if (!page) die('no page to attach to (is another client attached? stop ares-inspect)');
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
  const send = (method, params = {}) => new Promise((ok, fail) => {
    const id = next++;
    waiting.set(id, { ok, fail });
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

const print = (value) => console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));

if (command === 'list') {
  for (const p of await pages()) console.log(`${p.title}\n  ${p.url}\n  ${p.webSocketDebuggerUrl || '(a client is attached)'}`);
  process.exit(0);
}

const { page, send, close } = await attach();
console.error(`cdp: attached to ${page.url}`);
try {
  if (command === 'eval') print(await evaluate(send, args.join(' ')));
  else if (command === 'state') print((await evaluate(send, 'spike.state()')).report);
  else if (command === 'selftest') print(await evaluate(send, `spike.selfTest(${Number(args[0]) || 20})`));
  else if (command === 'mode') {
    const [name, raw] = args;
    const value = name === 'videos' ? Number(raw) : raw;
    await evaluate(send, `spike.set(${JSON.stringify(name)}, ${JSON.stringify(value)})`);
    print((await evaluate(send, 'spike.state()')).report);
  } else if (command === 'key') {
    const times = Number(args[1]) || 1;
    for (let i = 0; i < times; i++) {
      await press(send, args[0]);
      await new Promise((r) => setTimeout(r, 120));
    }
    await new Promise((r) => setTimeout(r, 300));
    print((await evaluate(send, 'spike.state()')).report);
  } else if (command === 'reload') await send('Page.reload', { ignoreCache: true });
  else die(`unknown command "${command}"`);
} finally {
  close();
}
