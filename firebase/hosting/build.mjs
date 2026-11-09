// Builds the approval pages into hosting/dist: the two scripts bundled with the Firebase SDK,
// and the static files copied as they are.
import { build } from 'esbuild';
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, 'dist');
rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
cpSync(join(here, 'public'), dist, { recursive: true });
await build({
  entryPoints: { pair: join(here, 'src/pair.ts'), delete: join(here, 'src/delete.ts') },
  outdir: dist,
  bundle: true,
  format: 'esm',
  minify: true,
  target: ['es2020'],
  legalComments: 'none',
  logLevel: 'info',
});
