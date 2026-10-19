// @ts-check

/**
 * Strips credentials from a URL before it reaches a log line — the server's half of the Android
 * app's `TextNormalizer.redactUrl` (NFR-SEC-02). Xtream puts the account in query parameters and
 * in stream paths alike.
 *
 * @param {string} url
 */
export function redactUrl(url) {
  return url
    .replace(/([?&](?:username|user|password|pass|token|auth|key)=)[^&\s]*/gi, '$1***')
    .replace(/\/\/([^/@\s:]+):([^/@\s]+)@/g, '//***:***@')
    .replace(/\/(live|movie|series|timeshift)\/([^/]+)\/([^/]+)\//gi, '/$1/***/***/');
}
