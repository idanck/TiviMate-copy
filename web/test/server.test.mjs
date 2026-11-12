// @ts-check
import { describe, expect, it } from 'vitest';
import { Auth } from '../server/auth.mjs';
import { assertReachable, isPrivateAddress } from '../server/guard.mjs';
import { decodeHeaders, mediaType, rewritePlaylist, sniffKind } from '../server/proxy.mjs';
import { redactUrl } from '../server/redact.mjs';

const b64 = (/** @type {object} */ o) => Buffer.from(JSON.stringify(o)).toString('base64url');

describe('rewritePlaylist', () => {
  it('sends every segment, key and variant back through the proxy, resolved against the final URL', () => {
    const text = [
      '#EXTM3U',
      '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"',
      '#EXT-X-MAP:URI="/init.mp4"',
      '#EXTINF:2.0,',
      'seg1.ts',
      '#EXTINF:2.0,',
      'https://cdn.example.com/abs/seg2.ts?token=1',
      '',
    ].join('\r\n');
    const out = rewritePlaylist(text, 'http://lb.example.com/live/a/b/1.m3u8', 'HDR').split('\n');
    expect(out[1]).toBe(`#EXT-X-KEY:METHOD=AES-128,URI="/api/stream?u=${encodeURIComponent('http://lb.example.com/live/a/b/key.bin')}&h=HDR"`);
    expect(out[2]).toBe(`#EXT-X-MAP:URI="/api/stream?u=${encodeURIComponent('http://lb.example.com/init.mp4')}&h=HDR"`);
    expect(out[4]).toBe(`/api/stream?u=${encodeURIComponent('http://lb.example.com/live/a/b/seg1.ts')}&h=HDR`);
    expect(out[6]).toBe(`/api/stream?u=${encodeURIComponent('https://cdn.example.com/abs/seg2.ts?token=1')}&h=HDR`);
  });

  it('names each address after its file for ffmpeg', () => {
    const out = rewritePlaylist('#EXTM3U\nseg1.ts?t=1\nchunk', 'http://a.example/live/x.m3u8', null, true).split('\n');
    expect(out[1]).toBe(`/api/stream/x.ts?u=${encodeURIComponent('http://a.example/live/seg1.ts?t=1')}`);
    expect(out[2]).toBe(`/api/stream/x?u=${encodeURIComponent('http://a.example/live/chunk')}`);
  });
  it('leaves data: and skd: key URIs alone', () => {
    const out = rewritePlaylist('#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://abc"', 'http://x/a.m3u8', null);
    expect(out).toBe('#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://abc"');
  });
});

describe('sniffKind', () => {
  const bytes = (/** @type {number[]} */ ...b) => Uint8Array.from(b);
  it('knows HLS by type or by its first line', () => {
    expect(sniffKind('application/vnd.apple.mpegurl', new Uint8Array())).toBe('hls');
    expect(sniffKind('text/plain', new TextEncoder().encode('#EXTM3U\n'))).toBe('hls');
  });
  it('knows MPEG-TS by its sync byte', () => {
    const ts = new Uint8Array(400);
    ts[0] = 0x47;
    ts[188] = 0x47;
    expect(sniffKind('application/octet-stream', ts)).toBe('ts');
  });
  it('knows progressive audio', () => {
    expect(sniffKind('audio/mpeg', new Uint8Array())).toBe('audio');
    expect(sniffKind('', bytes(0xff, 0xfb, 0x90, 0x00))).toBe('audio');
    expect(sniffKind('application/octet-stream', new TextEncoder().encode('OggS\0\x02'))).toBe('audio');
  });
});

describe('decodeHeaders', () => {
  it('keeps request headers and drops the connection-level ones', () => {
    const h = decodeHeaders(b64({ 'User-Agent': 'VLC', Referer: 'https://a', Host: 'evil', 'Content-Length': '1' }));
    expect(h).toEqual({ 'user-agent': 'VLC', referer: 'https://a' });
  });
  it('drops a value with a line break in it', () => {
    const h = Buffer.from(JSON.stringify({ Referer: 'http://a/\r\nX-Evil: 1', Origin: 'http://a' })).toString('base64url');
    expect(decodeHeaders(h)).toEqual({ origin: 'http://a' });
  });
  it('treats garbage as no headers', () => {
    expect(decodeHeaders('!!!')).toEqual({});
    expect(decodeHeaders(null)).toEqual({});
  });
});

describe('mediaType', () => {
  it('keeps media and images, and turns a page or a script into bytes', () => {
    expect(mediaType('video/mp2t')).toBe('video/mp2t');
    expect(mediaType('Audio/MPEG; charset=x')).toBe('audio/mpeg');
    expect(mediaType('image/svg+xml')).toBe('image/svg+xml');
    expect(mediaType('application/vnd.apple.mpegurl')).toBe('application/vnd.apple.mpegurl');
    for (const type of ['text/html', 'text/javascript', 'application/xhtml+xml', 'application/xml', 'application/dash+xml', '', null]) {
      expect(mediaType(type)).toBe('application/octet-stream');
    }
  });
});

describe('assertReachable', () => {
  it('refuses a private address written as IPv6 in a URL', async () => {
    for (const url of ['http://[::ffff:127.0.0.1]/', 'http://[::ffff:169.254.169.254]/', 'http://[::127.0.0.1]/', 'http://127.0.0.1/']) {
      await expect(assertReachable(new URL(url), { allowPrivate: false })).rejects.toThrow('private address');
    }
  });
});

describe('isPrivateAddress', () => {
  it.each([
    ['10.0.0.1', true], ['192.168.1.20', true], ['172.20.0.1', true], ['127.0.0.1', true],
    ['100.100.1.1', true], ['169.254.1.1', true], ['::1', true], ['fd00::1', true],
    ['::ffff:192.168.1.1', true], ['8.8.8.8', false], ['172.32.0.1', false], ['2606:4700::1', false],
    // An IPv4 address inside an IPv6 one, as a URL carries it (`[::ffff:127.0.0.1]` → `::ffff:7f00:1`).
    ['::ffff:7f00:1', true], ['::ffff:c0a8:101', true], ['::ffff:a9fe:a9fe', true], ['::7f00:1', true],
    ['64:ff9b::7f00:1', true], ['2002:c0a8:101::1', true], ['2001:0:1::1', true], ['::', true],
    ['fe80::1%eth0', true], ['::ffff:808:808', false], ['2002:808:808::1', false], ['64:ff9b::808:808', false],
  ])('%s → %s', (ip, expected) => {
    expect(isPrivateAddress(ip)).toBe(expected);
  });
});

describe('Auth', () => {
  it('needs nothing when no password is set', () => {
    expect(new Auth({}).valid(undefined)).toBe(true);
  });

  it('issues a cookie that it accepts, and refuses a forged or expired one', () => {
    const auth = new Auth({ password: 'pw' });
    const { value } = auth.issue(1_000);
    expect(auth.valid(`shidur_session=${value}`, 2_000)).toBe(true);
    expect(auth.valid(`shidur_session=${value}x`, 2_000)).toBe(false);
    expect(auth.valid(`shidur_session=${value}`, 1_000 + 181 * 24 * 3600_000)).toBe(false);
    expect(new Auth({ password: 'other' }).valid(`shidur_session=${value}`, 2_000)).toBe(false);
  });

  it('locks a client out after ten wrong passwords', () => {
    const auth = new Auth({ password: 'pw' });
    for (let i = 0; i < 10; i++) expect(auth.check('nope', 'ip', 0)).toBe('wrong');
    expect(auth.check('pw', 'ip', 0)).toBe('locked');
    expect(auth.check('pw', 'ip', 11 * 60_000)).toBe('ok');
  });
});

describe('redactUrl', () => {
  it('hides Xtream accounts in queries and paths', () => {
    expect(redactUrl('http://p.example/player_api.php?username=u&password=p&action=x'))
      .toBe('http://p.example/player_api.php?username=***&password=***&action=x');
    expect(redactUrl('http://p.example/live/user/pass/12.ts')).toBe('http://p.example/live/***/***/12.ts');
  });
});
