#!/usr/bin/env node
/**
 * Builds the two spike packages (ADR-056, step 1):
 *
 *   npm run spike:package   →   ipk/com.idanck.shidurspike.hd_<version>_all.ipk     (1280x720)
 *                                ipk/com.idanck.shidurspike.fullhd_<version>_all.ipk (1920x1080)
 *
 * The same page in both; only appinfo.json's id, title and resolution differ, because webOS reads
 * the resolution from the manifest when it opens the app and the page cannot change it.
 */
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const VERSION = '0.1.0';
const VARIANTS = [
  { id: 'com.idanck.shidurspike.hd', title: 'Spike 720', resolution: '1280x720' },
  { id: 'com.idanck.shidurspike.fullhd', title: 'Spike 1080', resolution: '1920x1080' },
];

const outDir = join(root, 'ipk');
mkdirSync(outDir, { recursive: true });
const ares = join(root, 'node_modules/.bin/ares-package');

for (const v of VARIANTS) {
  const stage = join(root, '.package', v.id);
  rmSync(stage, { recursive: true, force: true });
  cpSync(join(root, 'spike/app'), stage, { recursive: true });
  const appinfo = {
    id: v.id,
    version: VERSION,
    vendor: 'Shidur',
    type: 'web',
    main: 'index.html',
    title: v.title,
    icon: 'icon-80.png',
    largeIcon: 'icon-130.png',
    resolution: v.resolution,
    disableBackHistoryAPI: true,
    requiredACG: [],
  };
  writeFileSync(join(stage, 'appinfo.json'), `${JSON.stringify(appinfo, null, 2)}\n`);
  execFileSync(ares, ['--outdir', outDir, stage], { stdio: 'inherit' });
  console.log(`spike: ${join('ipk', `${v.id}_${VERSION}_all.ipk`)} (${v.resolution})`);
}
rmSync(join(root, '.package'), { recursive: true, force: true });
