// @ts-nocheck -- exercises the plain-JS service (service/net.js) against a local server.
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { gzipSync } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const require = createRequire(import.meta.url);
const net = require('../service/net.js');

/*
 * The service runs in the television's Node, v8.12 on webOS 5 and 6 (service/net.js). These tests
 * run it in today's Node against a local server; scripts/check-es.mjs --ecma 2017 checks its source
 * is what Node 8 reads.
 */

let server;
let base;
const seen = [];
/** How many bytes the /endless route has written, to see the service hold it back. */
let endlessWritten = 0;

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
      case '/endless': {
        // 64 MB offered as fast as the socket takes it.
        res.writeHead(200, { 'content-type': 'text/plain' });
        const chunk = Buffer.alloc(64 * 1024, 'x');
        let left = 1024;
        const pump = () => {
          while (left > 0) {
            left--;
            endlessWritten += chunk.length;
            if (!res.write(chunk)) return res.once('drain', pump);
          }
          res.end();
        };
        res.on('close', () => { left = 0; });
        return pump();
      }
      case '/hang':
        // Headers, then silence.
        res.writeHead(200, { 'content-type': 'text/plain' });
        return res.write('a');
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

afterAll(() => new Promise((r) => {
  server.closeAllConnections?.();
  server.close(r);
}));

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
    expect(seen[seen.length - 1].ua).toBe(net.DEFAULT_USER_AGENT);
  });

  it('sends a source’s own user agent and referer', async () => {
    await read('/list.m3u', { 'User-Agent': 'VLC/3.0.20', Referer: 'https://example.com/', Host: 'evil' });
    expect(seen[seen.length - 1]).toMatchObject({ ua: 'VLC/3.0.20', referer: 'https://example.com/' });
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
});

describe('service: says what went wrong (ADR-057: not everything is "unreachable")', () => {
  it('a closed port is a refused connection', async () => {
    await expect(net.readText('http://127.0.0.1:1/', {}, () => {})).rejects.toMatchObject({ code: 'refused' });
  });

  it('names each kind of failure Node reports', () => {
    const e = (code, message = '') => Object.assign(new Error(message), { code });
    expect(net.codeOf(e('ENOTFOUND'))).toBe('dns');
    expect(net.codeOf(e('EAI_AGAIN'))).toBe('dns');
    expect(net.codeOf(e('ECONNREFUSED'))).toBe('refused');
    expect(net.codeOf(e('ECONNRESET'))).toBe('reset');
    expect(net.codeOf(e('ETIMEDOUT'))).toBe('timeout');
    expect(net.codeOf(e('CERT_HAS_EXPIRED'))).toBe('tls-cert');
    expect(net.codeOf(e('UNABLE_TO_GET_ISSUER_CERT_LOCALLY'))).toBe('tls-cert');
    expect(net.codeOf(e('DEPTH_ZERO_SELF_SIGNED_CERT'))).toBe('tls-cert');
    // Node 8 words a wrong host name without a code.
    expect(net.codeOf(e(undefined, "Hostname/IP doesn't match certificate's altnames: \"Host: x\""))).toBe('tls-cert');
    expect(net.codeOf(e('EPROTO', 'write EPROTO ssl3_get_record:wrong version number'))).toBe('tls');
    expect(net.codeOf(e('EHOSTUNREACH'))).toBe('unreachable');
  });

  it('a connection that goes silent times out', async () => {
    const handle = net.newHandle();
    const started = Date.now();
    const t = setTimeout(() => handle.abort(), net.TIMEOUT_MS + 5000);
    try {
      await expect(net.readText(`${base}/hang`, {}, () => {}, handle)).rejects.toMatchObject({ code: 'timeout' });
    } finally {
      clearTimeout(t);
    }
    expect(Date.now() - started).toBeGreaterThanOrEqual(net.TIMEOUT_MS - 100);
  }, net.TIMEOUT_MS + 10000);
});

describe('service: holds the provider back while the app catches up (ADR-057)', () => {
  it('stops reading while paused, and reads on after resume', async () => {
    endlessWritten = 0;
    const handle = net.newHandle();
    let received = 0;
    let parts = 0;
    const reading = net.readText(`${base}/endless`, {}, (part) => {
      received += part.length;
      parts++;
      if (parts === 2) handle.pause();
    }, handle);
    await new Promise((r) => setTimeout(r, 600));
    const whilePaused = received;
    const writtenWhilePaused = endlessWritten;
    await new Promise((r) => setTimeout(r, 600));
    // Nothing more reaches the app, and the server is held back by the socket's buffers.
    expect(received).toBe(whilePaused);
    expect(endlessWritten - writtenWhilePaused).toBeLessThan(8 * 1024 * 1024);
    expect(endlessWritten).toBeLessThan(32 * 1024 * 1024);
    handle.resume();
    const meta = await reading;
    expect(meta.status).toBe(200);
    expect(received).toBe(64 * 1024 * 1024);
  }, 30000);
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
