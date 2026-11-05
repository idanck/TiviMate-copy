// @ts-nocheck -- exercises the plain-JS service (service/net.js) against a local server.
import { parse } from 'acorn';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { gzipSync } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const require = createRequire(import.meta.url);
const net = require('../service/net.js');

/*
 * The service runs in the television's Node, v8.12 on webOS 6 (service/net.js). These tests run
 * it in today's Node against a local server, and check that its source is still what Node 8 reads.
 */

describe('the service source runs on webOS 6 (Node 8.12)', () => {
  for (const file of ['service/net.js', 'service/index.js']) {
    const source = readFileSync(new URL(`../${file}`, import.meta.url), 'utf8');
    it(`${file} is ES2017`, () => {
      expect(() => parse(source, { ecmaVersion: 2017, sourceType: 'script' })).not.toThrow();
    });
    it(`${file} uses nothing Node 8 lacks`, () => {
      expect(source).not.toMatch(/\.(flatMap|flat|matchAll|trimStart|trimEnd|at|replaceAll)\(|Object\.fromEntries|globalThis|AbortController|\bfetch\(/);
    });
  }
});

let server;
let base;
const seen = [];

beforeAll(async () => {
  server = createServer((req, res) => {
    seen.push({ url: req.url, ua: req.headers['user-agent'], referer: req.headers.referer });
    switch (req.url) {
      case '/list.m3u':
        res.writeHead(200, { 'content-type': 'audio/x-mpegurl' });
        return res.end('#EXTM3U\n#EXTINF:-1,ערוץ אחד\nhttp://example.com/1.ts\n');
      case '/moved':
        res.writeHead(302, { location: '/list.m3u' });
        return res.end();
      case '/loop':
        res.writeHead(302, { location: '/loop' });
        return res.end();
      case '/guide.xml.gz':
        // A gzip body served as plain XML, as providers do.
        res.writeHead(200, { 'content-type': 'application/xml' });
        return res.end(gzipSync(Buffer.from('<tv>שלום</tv>')));
      case '/encoded':
        res.writeHead(200, { 'content-type': 'text/plain', 'content-encoding': 'gzip' });
        return res.end(gzipSync(Buffer.from('plain text')));
      case '/big':
        res.writeHead(200, { 'content-type': 'text/plain' });
        return res.end('א'.repeat(net.PART_CHARS + 10));
      case '/missing':
        res.writeHead(404);
        return res.end('no');
      case '/live/1':
        res.writeHead(200, { 'content-type': 'application/octet-stream' });
        return res.end(Buffer.from([0x47, 0x40, 0x00, 0x10, 0, 0, 0]));
      case '/live/2':
        res.writeHead(302, { location: '/live/2.m3u8' });
        return res.end();
      case '/live/2.m3u8':
        res.writeHead(200, { 'content-type': 'text/plain' });
        return res.end('#EXTM3U\n#EXT-X-VERSION:3\n');
      default:
        res.writeHead(500);
        return res.end();
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

afterAll(() => new Promise((r) => server.close(r)));

async function read(path, headers) {
  const parts = [];
  const meta = await net.readText(base + path, headers, (p) => parts.push(p));
  return { meta, parts, text: parts.join('') };
}

describe('service: reading text', () => {
  it('reads a playlist and sends the default user agent', async () => {
    const { meta, text } = await read('/list.m3u');
    expect(meta.status).toBe(200);
    expect(text).toContain('ערוץ אחד');
    expect(seen.at(-1).ua).toBe(net.DEFAULT_USER_AGENT);
  });

  it('sends a source’s own user agent and referer', async () => {
    await read('/list.m3u', { 'User-Agent': 'VLC/3.0.20', Referer: 'https://example.com/', Host: 'evil' });
    expect(seen.at(-1)).toMatchObject({ ua: 'VLC/3.0.20', referer: 'https://example.com/' });
  });

  it('follows redirects and reports where it ended', async () => {
    const { meta, text } = await read('/moved');
    expect(text).toContain('#EXTM3U');
    expect(meta.finalUrl).toBe(`${base}/list.m3u`);
  });

  it('gives up on a redirect loop', async () => {
    await expect(read('/loop')).rejects.toMatchObject({ code: 'redirects' });
  });

  it('unzips a gzip body whatever its content type says', async () => {
    expect((await read('/guide.xml.gz')).text).toBe('<tv>שלום</tv>');
    expect((await read('/encoded')).text).toBe('plain text');
  });

  it('hands long text on in parts, without splitting a character', async () => {
    const { parts, text } = await read('/big');
    expect(parts.length).toBe(2);
    expect(parts[0].length).toBe(net.PART_CHARS);
    expect(text).toBe('א'.repeat(net.PART_CHARS + 10));
  });

  it('resolves an error status with no text, for the app to word', async () => {
    const { meta, parts } = await read('/missing');
    expect(meta.status).toBe(404);
    expect(parts).toEqual([]);
  });

  it('refuses what is not http or https', async () => {
    await expect(net.readText('file:///etc/passwd', {}, () => {})).rejects.toMatchObject({ code: 'bad-url' });
  });

  it('says when the host cannot be reached', async () => {
    await expect(net.readText('http://127.0.0.1:1/', {}, () => {})).rejects.toMatchObject({ code: 'unreachable' });
  });
});

describe('service: probing a stream', () => {
  it('knows MPEG-TS by its sync byte', async () => {
    expect(await net.probe(`${base}/live/1`, {})).toMatchObject({ status: 200, kind: 'ts' });
  });

  it('knows HLS by its first line and returns the address after redirects', async () => {
    expect(await net.probe(`${base}/live/2`, {})).toMatchObject({ kind: 'hls', finalUrl: `${base}/live/2.m3u8` });
  });

  it('reports an error status', async () => {
    expect(await net.probe(`${base}/missing`, {})).toMatchObject({ status: 404 });
  });

  it('reads the content type first', () => {
    expect(net.kindOf('application/vnd.apple.mpegurl', Buffer.alloc(0))).toBe('hls');
    expect(net.kindOf('video/mp2t', Buffer.from('x'))).toBe('ts');
    expect(net.kindOf('', Buffer.from('FLV\x01'))).toBe('flv');
    expect(net.kindOf('audio/mpeg', Buffer.from('ID3'))).toBe('native');
  });
});
