import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpError, probeStream, streamText } from '../src/data/api';
import { describe as wordsFor } from '../src/data/sources';

/**
 * The page's side of the service (data/api.ts) against a stand-in for webOS's PalmServiceBridge:
 * every call the page makes is recorded, and the test plays the service's replies.
 */
interface Call {
  uri: string;
  params: Record<string, unknown>;
  reply: (r: Record<string, unknown>) => void;
  cancelled: boolean;
}
let calls: Call[] = [];

class FakeBridge {
  onservicecallback: ((message: string) => void) | null = null;
  private call_: Call | null = null;
  call(uri: string, params: string) {
    const c: Call = {
      uri,
      params: JSON.parse(params),
      reply: (r) => this.onservicecallback?.(JSON.stringify(r)),
      cancelled: false,
    };
    this.call_ = c;
    calls.push(c);
  }
  cancel() {
    if (this.call_) this.call_.cancelled = true;
  }
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal('window', { PalmServiceBridge: FakeBridge });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const tick = () => new Promise((r) => setTimeout(r, 0));
const acks = () => calls.filter((c) => c.uri.endsWith('/ack')).map((c) => c.params.seq);

describe('streamText over the bus', () => {
  it('hands parts on in order, however they arrive, and acks each once it is dealt with', async () => {
    const got: string[] = [];
    let release: () => void = () => {};
    const reading = streamText('http://p/guide.xml', {}, (part) => {
      got.push(part);
      // The first part takes a while (the worker parsing it).
      if (got.length === 1) return new Promise<void>((r) => (release = r));
    });
    const fetch = calls[0];
    expect(fetch.uri).toBe('luna://com.idanck.shidur.service/fetch');
    expect(fetch.params).toMatchObject({ url: 'http://p/guide.xml', subscribe: true });
    expect(typeof fetch.params.id).toBe('string');
    fetch.reply({ returnValue: true, seq: 1, part: 'b' });
    fetch.reply({ returnValue: true, seq: 0, part: 'a' });
    fetch.reply({ returnValue: true, done: true, parts: 2, status: 200, contentType: 'text/xml', finalUrl: 'http://p/g' });
    await tick();
    // Still parsing part 0: nothing acked, part 1 not handed on.
    expect(got).toEqual(['a']);
    expect(acks()).toEqual([]);
    release();
    const info = await reading;
    expect(got).toEqual(['a', 'b']);
    expect(acks()).toEqual([0, 1]);
    expect(calls.filter((c) => c.uri.endsWith('/ack')).every((c) => c.params.id === fetch.params.id)).toBe(true);
    expect(info).toEqual({ status: 200, contentType: 'text/xml', finalUrl: 'http://p/g' });
    expect(fetch.cancelled).toBe(true);
  });

  it('rejects with the service’s own code, which the app puts in words', async () => {
    const reading = streamText('https://p/list.m3u', {}, () => {});
    calls[0].reply({ returnValue: false, errorText: 'tls-cert', status: 0, detail: 'CERT_HAS_EXPIRED' });
    const err = await reading.catch((e: unknown) => e);
    expect(err).toBeInstanceOf(HttpError);
    expect(err).toMatchObject({ code: 'tls-cert', detail: 'CERT_HAS_EXPIRED' });
    expect(wordsFor(err)).toBe('הטלוויזיה לא מקבלת את תעודת האבטחה של הספק');
  });

  it('a provider’s error status rejects once the reply is done', async () => {
    const reading = streamText('http://p/x', {}, () => {});
    calls[0].reply({ returnValue: true, done: true, parts: 0, status: 404 });
    await expect(reading).rejects.toMatchObject({ status: 404, code: 'http' });
  });

  it('stops the service’s read when the caller aborts', async () => {
    const ac = new AbortController();
    const reading = streamText('http://p/x', {}, () => {}, ac.signal);
    ac.abort();
    await expect(reading).rejects.toMatchObject({ name: 'AbortError' });
    expect(calls[0].cancelled).toBe(true);
  });
});

describe('probeStream over the bus', () => {
  it('answers with what the service found', async () => {
    const probing = probeStream('http://p/live/1', {});
    calls[0].reply({ returnValue: true, done: true, status: 200, kind: 'ts', finalUrl: 'http://cdn/1.ts' });
    await expect(probing).resolves.toMatchObject({ kind: 'ts', finalUrl: 'http://cdn/1.ts' });
  });

  it('gives up when the service never answers (ADR-057: the first version waited for ever)', async () => {
    vi.useFakeTimers();
    const probing = probeStream('http://p/live/1', {}, 15_000);
    const settled = probing.catch((e: unknown) => e);
    vi.advanceTimersByTime(15_001);
    expect(await settled).toMatchObject({ code: 'timeout' });
    expect(calls[0].cancelled).toBe(true);
  });
});
