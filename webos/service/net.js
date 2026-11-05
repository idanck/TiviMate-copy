// @ts-nocheck -- plain Node 8 JavaScript, kept free of tooling; test/service.test.mjs covers it.
'use strict';
/**
 * The service's network code: what web/server/proxy.mjs does for the computer, done on the
 * television itself (ADR-052).
 *
 * A packaged webOS app runs from file:// and is held to CORS like any page, and IPTV providers send
 * no CORS headers, so the app cannot read a playlist, a panel's JSON or a guide by itself. A JS
 * service is a Node process the app ships in its own package, outside the page: it may talk to
 * any host, set User-Agent and Referer, and follow redirects. The app asks it over the Luna bus
 * and gets the text back in parts (index.js).
 *
 * This file runs in webOS's own Node — v8.12 on webOS 6 — so it is plain ES2017 and CommonJS:
 * no optional chaining, no `??`, no object spread in calls Node 8 lacks, no dependencies.
 */
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const StringDecoder = require('string_decoder').StringDecoder;
const URL = require('url').URL;

/** The Android app's own default (ADR-018): providers filter on it. */
const DEFAULT_USER_AGENT = 'TiviCopy/1.0 (Android)';
const MAX_HOPS = 5;
const TIMEOUT_MS = 20000;
/** One Luna message's worth of text. The bus carries JSON, and smaller parts keep it moving. */
const PART_CHARS = 256 * 1024;
/** A guide can be hundreds of megabytes; past this, something is wrong with the address. */
const TEXT_LIMIT_BYTES = 768 * 1024 * 1024;
const PROBE_BYTES = 2048;

/** Headers a playlist may not set: they belong to the connection, not to the request. */
const CONNECTION_HEADERS = [
  'host', 'connection', 'content-length', 'transfer-encoding', 'upgrade', 'te', 'trailer',
  'keep-alive', 'proxy-connection', 'accept-encoding',
];

function requestHeaders(headers) {
  const out = { 'User-Agent': DEFAULT_USER_AGENT, Accept: '*/*', 'Accept-Encoding': 'gzip' };
  const given = headers || {};
  Object.keys(given).forEach(function (name) {
    if (CONNECTION_HEADERS.indexOf(name.toLowerCase()) >= 0) return;
    const value = given[name];
    if (typeof value !== 'string' || value === '') return;
    // A user agent given as "user-agent" replaces the default rather than adding a second one.
    if (name.toLowerCase() === 'user-agent') delete out['User-Agent'];
    out[name] = value;
  });
  return out;
}

function failure(code, status) {
  const err = new Error(code);
  err.code = code;
  err.status = status || 0;
  return err;
}

/**
 * Opens `url`, following up to five redirects. Resolves with the response of the last hop and
 * the address it came from; `handle.abort()` stops whatever is in flight.
 */
function open(url, headers, handle) {
  return new Promise(function (resolve, reject) {
    let hops = 0;
    const go = function (address) {
      let target;
      try {
        target = new URL(address);
      } catch (e) {
        return reject(failure('bad-url'));
      }
      if (target.protocol !== 'http:' && target.protocol !== 'https:') return reject(failure('bad-url'));
      const lib = target.protocol === 'https:' ? https : http;
      const req = lib.request({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || undefined,
        path: target.pathname + target.search,
        method: 'GET',
        headers: requestHeaders(headers),
      }, function (res) {
        const status = res.statusCode || 0;
        const location = res.headers.location;
        if ([301, 302, 303, 307, 308].indexOf(status) >= 0 && location) {
          res.resume();
          if (++hops > MAX_HOPS) return reject(failure('redirects', status));
          let next;
          try {
            next = new URL(location, address).href;
          } catch (e) {
            return reject(failure('bad-url', status));
          }
          return go(next);
        }
        resolve({ res: res, finalUrl: address });
      });
      handle.current = req;
      req.setTimeout(TIMEOUT_MS, function () {
        req.abort();
        reject(failure('timeout'));
      });
      req.on('error', function (e) {
        if (handle.aborted) return reject(failure('aborted'));
        reject(failure('unreachable'));
      });
      req.end();
    };
    go(url);
  });
}

function newHandle() {
  const handle = { current: null, aborted: false };
  handle.abort = function () {
    handle.aborted = true;
    if (handle.current) handle.current.abort();
  };
  return handle;
}

function isGzip(buf) {
  return buf.length >= 2 && buf[0] === 0x1f && buf[1] === 0x8b;
}

/**
 * Reads a text resource and hands it on in parts of at most PART_CHARS characters, unzipping a
 * gzip body whether or not the provider said so (guides are often served as .xml.gz with a plain
 * content type). Resolves once the last part has been handed on.
 *
 * A status of 400 or more is not an error here: it resolves with no parts, and the app words it.
 */
function readText(url, headers, onPart, handle) {
  const h = handle || newHandle();
  return open(url, headers, h).then(function (opened) {
    const res = opened.res;
    const meta = {
      status: res.statusCode || 0,
      contentType: String(res.headers['content-type'] || ''),
      finalUrl: opened.finalUrl,
    };
    if (meta.status >= 400) {
      res.resume();
      return meta;
    }
    return new Promise(function (resolve, reject) {
      const decoder = new StringDecoder('utf8');
      let pending = '';
      let bytes = 0;
      let gunzip = null;
      let first = true;
      let settled = false;

      const finish = function (err) {
        if (settled) return;
        settled = true;
        if (err) {
          res.destroy();
          return reject(err);
        }
        pending += decoder.end();
        if (pending) onPart(pending);
        pending = '';
        resolve(meta);
      };
      const text = function (buf) {
        bytes += buf.length;
        if (bytes > TEXT_LIMIT_BYTES) return finish(failure('too-large', meta.status));
        pending += decoder.write(buf);
        while (pending.length >= PART_CHARS) {
          onPart(pending.slice(0, PART_CHARS));
          pending = pending.slice(PART_CHARS);
        }
      };

      res.on('data', function (chunk) {
        if (settled) return;
        if (first) {
          first = false;
          const encoded = /gzip/i.test(String(res.headers['content-encoding'] || ''));
          if (encoded || isGzip(chunk)) {
            gunzip = zlib.createGunzip();
            gunzip.on('data', text);
            gunzip.on('end', function () { finish(); });
            gunzip.on('error', function () { finish(failure('gzip', meta.status)); });
          }
        }
        if (gunzip) gunzip.write(chunk);
        else text(chunk);
      });
      res.on('end', function () {
        if (gunzip) gunzip.end();
        else finish();
      });
      res.on('error', function () {
        finish(failure(h.aborted ? 'aborted' : 'unreachable', meta.status));
      });
      res.on('aborted', function () {
        finish(failure(h.aborted ? 'aborted' : 'unreachable', meta.status));
      });
    });
  });
}

/**
 * What a stream is, from its first bytes: the player needs to know before it picks a decoder,
 * and an Xtream address often has no extension. Also returns the address after redirects, since
 * the television's own player is handed that one.
 */
function probe(url, headers, handle) {
  const h = handle || newHandle();
  return open(url, headers, h).then(function (opened) {
    const res = opened.res;
    const status = res.statusCode || 0;
    const contentType = String(res.headers['content-type'] || '').toLowerCase();
    const base = { status: status, contentType: contentType, finalUrl: opened.finalUrl };
    if (status >= 400) {
      res.resume();
      return Object.assign(base, { kind: 'unknown' });
    }
    return new Promise(function (resolve) {
      const parts = [];
      let length = 0;
      let done = false;
      const settle = function () {
        if (done) return;
        done = true;
        res.destroy();
        resolve(Object.assign(base, { kind: kindOf(contentType, Buffer.concat(parts)) }));
      };
      res.on('data', function (chunk) {
        parts.push(chunk);
        length += chunk.length;
        if (length >= PROBE_BYTES) settle();
      });
      res.on('end', settle);
      res.on('error', settle);
      setTimeout(settle, TIMEOUT_MS);
    });
  });
}

function kindOf(contentType, head) {
  if (/mpegurl/.test(contentType)) return 'hls';
  if (head.slice(0, 7).toString('latin1') === '#EXTM3U') return 'hls';
  if (head.length > 0 && head[0] === 0x47) return 'ts';
  if (/mp2t/.test(contentType)) return 'ts';
  if (head.slice(0, 3).toString('latin1') === 'FLV') return 'flv';
  return 'native';
}

module.exports = {
  DEFAULT_USER_AGENT: DEFAULT_USER_AGENT,
  PART_CHARS: PART_CHARS,
  requestHeaders: requestHeaders,
  readText: readText,
  probe: probe,
  kindOf: kindOf,
  newHandle: newHandle,
};
