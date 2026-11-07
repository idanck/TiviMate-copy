// @ts-nocheck -- drives service/index.js with a stand-in for the television's `webos-service`.
import Module, { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const require = createRequire(import.meta.url);

/** What service/index.js registered, by method name. */
const methods = {};
class FakeService {
  constructor(id) { this.id = id; }
  register(name, handler, cancel) { methods[name] = { handler, cancel }; }
}

let server;
let base;
const PARTS = 12;

beforeAll(async () => {
  // `webos-service` is the television's own; stand one in for require().
  const load = Module._load;
  Module._load = function (request, ...rest) {
    if (request === 'webos-service') return FakeService;
    return load.call(this, request, ...rest);
  };
  try {
    require('../service/index.js');
  } finally {
    Module._load = load;
  }
  const net = require('../service/net.js');
  server = createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('x'.repeat(net.PART_CHARS * PARTS));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

afterAll(() => new Promise((r) => server.close(r)));

function call(name, payload, token) {
  const replies = [];
  const message = { payload, uniqueToken: token, respond: (r) => replies.push(r) };
  methods[name].handler(message);
  return { replies, message };
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

describe('the service on the bus (service/index.js)', () => {
  it('sends no more than four parts ahead of the last ack, then everything once acked', async () => {
    const { replies } = call('fetch', { id: 'g1', url: `${base}/guide` }, 't1');
    await wait(500);
    const parts = () => replies.filter((r) => typeof r.part === 'string');
    // Paused after the fourth (a few more may already be on their way through one chunk).
    expect(parts().length).toBeGreaterThanOrEqual(4);
    expect(parts().length).toBeLessThan(PARTS);
    expect(replies.some((r) => r.done)).toBe(false);
    // Acknowledge each part as it comes, until the end.
    for (let acked = 0; !replies.some((r) => r.done); ) {
      while (acked < parts().length) {
        call('ack', { id: 'g1', seq: acked }, `a${acked}`);
        acked++;
      }
      await wait(50);
    }
    expect(parts().length).toBe(PARTS);
    expect(replies.find((r) => r.done)).toMatchObject({ done: true, parts: PARTS, status: 200 });
    expect(parts().map((r) => r.seq)).toEqual([...Array(PARTS).keys()]);
  });

  it('an app that sends no id (and so no acks) is never held back', async () => {
    const { replies } = call('fetch', { url: `${base}/guide` }, 't2');
    for (let i = 0; i < 100 && !replies.some((r) => r.done); i++) await wait(20);
    expect(replies.filter((r) => typeof r.part === 'string').length).toBe(PARTS);
  });

  it('cancelling the subscription stops the read', async () => {
    const { replies, message } = call('fetch', { id: 'g3', url: `${base}/guide` }, 't3');
    await wait(300);
    methods.fetch.cancel(message);
    await wait(300);
    expect(replies.some((r) => r.done)).toBe(false);
    expect(call('ack', { id: 'g3', seq: 0 }, 'a').replies[0]).toMatchObject({ returnValue: true, known: false });
  });

  it('refuses an address that is not http or https', () => {
    expect(call('fetch', { url: 'file:///etc/passwd' }, 't4').replies[0]).toMatchObject({ returnValue: false, errorText: 'bad-url' });
  });
});
