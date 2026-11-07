// @ts-nocheck -- plain Node 8 JavaScript, kept free of tooling; test/service.test.mjs covers it.
'use strict';
/**
 * Shidur's JS service on the television (ADR-057): the app's way to the providers.
 *
 * Methods on the Luna bus (the app's side is src/data/api.ts):
 *
 * - `fetch` { id, url, headers }, subscribed → { seq, part } replies, then { done: true, parts,
 *   status, contentType, finalUrl }. A playlist, a panel's JSON or a guide, as text.
 * - `ack` { id, seq } → the app has dealt with every part up to `seq`. At most WINDOW parts are
 *   ever sent ahead of the last ack; past that the provider is held back (net.js `pause`), so a
 *   guide of hundreds of megabytes never piles up on the bus or in the page.
 * - `probe` { url, headers }, subscribed → one { done: true, status, contentType, finalUrl, kind }.
 *
 * Failures reply { returnValue: false, errorCode, errorText, status, detail }: `errorText` is one of
 * net.js's codes (`dns`, `refused`, `tls-cert`, `timeout`, …), `detail` what Node said. Cancelling
 * the subscription aborts the request, so a guide the user walked away from stops downloading.
 *
 * `webos-service` is not on npm: the television provides it to every JS service.
 */
const Service = require('webos-service');
const net = require('./net');

const ID = require('./package.json').name;
const service = new Service(ID);

/** Parts sent and not yet acknowledged, at most: 4 × 256 K characters. */
const WINDOW = 4;

/** In-flight requests by the subscription that asked for them, so a cancel can find its request. */
const inFlight = {};
/** Fetches by the app's own id, so an ack can find its fetch. */
const byId = {};

function keyOf(message) {
  return message.uniqueToken || (message.token !== undefined ? String(message.token) : '');
}

function fail(message, err) {
  message.respond({
    returnValue: false,
    errorCode: -1,
    errorText: (err && err.code) || 'failed',
    status: (err && err.status) || 0,
    detail: (err && err.detail) || '',
  });
}

/**
 * Keeps the service alive while a long request runs: webOS stops an idle service, and a guide
 * can take minutes to download. Where the activity manager is missing this does nothing, and the
 * open subscription is what keeps the service up.
 */
function keepAlive(name) {
  const manager = service.activityManager;
  if (!manager || typeof manager.create !== 'function') return function () {};
  let activity = null;
  try {
    manager.create(name, function (a) { activity = a; });
  } catch (e) {
    return function () {};
  }
  return function () {
    try {
      if (activity) manager.complete(activity, function () {});
    } catch (e) {
      // already gone
    }
  };
}

function validRequest(message) {
  const p = message.payload || {};
  if (typeof p.url !== 'string' || !/^https?:\/\//i.test(p.url)) {
    fail(message, { code: 'bad-url' });
    return null;
  }
  const headers = p.headers && typeof p.headers === 'object' ? p.headers : {};
  return { url: p.url, headers: headers, id: typeof p.id === 'string' ? p.id : '' };
}

function cancel(message) {
  const key = keyOf(message);
  const entry = inFlight[key];
  if (entry) {
    entry.handle.abort();
    delete inFlight[key];
    if (entry.id) delete byId[entry.id];
  }
}

service.register('fetch', function (message) {
  const request = validRequest(message);
  if (!request) return;
  const key = keyOf(message);
  const handle = net.newHandle();
  const entry = { handle: handle, id: request.id, sent: 0, acked: 0 };
  inFlight[key] = entry;
  if (request.id) byId[request.id] = entry;
  const done = keepAlive('shidur-fetch-' + key);
  net.readText(request.url, request.headers, function (part) {
    message.respond({ returnValue: true, seq: entry.sent, part: part });
    entry.sent++;
    // An app that does not ack (an older build) is never held back.
    if (request.id && entry.sent - entry.acked >= WINDOW) handle.pause();
  }, handle).then(function (meta) {
    message.respond({
      returnValue: true,
      done: true,
      parts: entry.sent,
      status: meta.status,
      contentType: meta.contentType,
      finalUrl: meta.finalUrl,
    });
  }, function (err) {
    fail(message, err);
  }).then(function () {
    delete inFlight[key];
    if (request.id) delete byId[request.id];
    done();
  });
}, cancel);

service.register('ack', function (message) {
  const p = message.payload || {};
  const entry = typeof p.id === 'string' ? byId[p.id] : undefined;
  const seq = Number(p.seq);
  if (entry && isFinite(seq) && seq + 1 > entry.acked) {
    entry.acked = seq + 1;
    if (entry.sent - entry.acked < WINDOW) entry.handle.resume();
  }
  message.respond({ returnValue: true, known: !!entry });
});

service.register('probe', function (message) {
  const request = validRequest(message);
  if (!request) return;
  const key = keyOf(message);
  const handle = net.newHandle();
  inFlight[key] = { handle: handle, id: '' };
  net.probe(request.url, request.headers, handle).then(function (info) {
    message.respond({
      returnValue: true,
      done: true,
      status: info.status,
      contentType: info.contentType,
      finalUrl: info.finalUrl,
      kind: info.kind,
    });
  }, function (err) {
    fail(message, err);
  }).then(function () {
    delete inFlight[key];
  });
}, cancel);
