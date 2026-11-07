import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * The parsers on the television are the computer version's, byte for byte (ADR-057), so a rule
 * changed there (or ported from Android) cannot be forgotten here. A change to one of these files
 * goes into web/src/data/ and is copied to webos/src/data/.
 */
const PARSERS = ['backup.ts', 'builtin.ts', 'classify.ts', 'guide.ts', 'm3u.ts', 'matcher.ts', 'model.ts', 'normalize.ts', 'xmltv.ts', 'xtream.ts'];

describe('src/data/ parsers are the computer version’s', () => {
  for (const file of PARSERS) {
    it(file, () => {
      const here = readFileSync(new URL(`../src/data/${file}`, import.meta.url), 'utf8');
      const web = readFileSync(new URL(`../../web/src/data/${file}`, import.meta.url), 'utf8');
      expect(here).toBe(web);
    });
  }
});
