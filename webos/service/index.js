'use strict';
/**
 * Shidur's JS service on the television (ADR-052): the app's way to the providers.
 *
 * Two methods on the Luna bus, both called with `subscribe: true` (data/luna.ts):
 *
 * - `fetch` { url, headers } → any number of { part } replies, then { done: true, status,
 *   contentType, finalUrl }. A playlist, a panel's JSON or a guide, as text.
 * - `probe` { url, headers } → one { done: true, status, contentType, finalUrl, kind }.
 *
 * Failures reply { returnValue: false, errorCode, errorText }. Cancelling the subscription aborts
 * the request, so a guide the user walked away from stops downloading.
 *
 * `webos-service` is not on npm: the television provides it to every JS service.
 */
const Service = require('webos-service');
const net = require('./net');

const ID = require('./package.json').name;
const service = new Service(ID);

/** In-flight requests by the subscription that asked for them, so a cancel can find its request. */
const inFlight = {};

function keyOf(message) {
  return message.uniqueToken || (message.token !== undefined ? String(message.token) : '');
}

function fail(message, err) {
  message.respond({
    returnValue: false,
    errorCode: -1,
    errorText: (err && err.code) || 'failed',
    status: (err && err.status) || 0,
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
  return { url: p.url, headers: headers };
}

function cancel(message) {
  const key = keyOf(message);
  const handle = inFlight[key];
  if (handle) {
    handle.abort();
    delete inFlight[key];
  }
}

service.register('fetch', function (message) {
  const request = validRequest(message);
  if (!request) return;
  const key = keyOf(message);
  const handle = net.newHandle();
  inFlight[key] = handle;
  const done = keepAlive('shidur-fetch-' + key);
  let seq = 0;
  net.readText(request.url, request.headers, function (part) {
    message.respond({ returnValue: true, seq: seq++, part: part });
  }, handle).then(function (meta) {
    message.respond({
      returnValue: true,
      done: true,
      parts: seq,
      status: meta.status,
      contentType: meta.contentType,
      finalUrl: meta.finalUrl,
    });
  }, function (err) {
    fail(message, err);
  }).then(function () {
    delete inFlight[key];
    done();
  });
}, cancel);

service.register('probe', function (message) {
  const request = validRequest(message);
  if (!request) return;
  const key = keyOf(message);
  const handle = net.newHandle();
  inFlight[key] = handle;
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
