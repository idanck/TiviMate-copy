#!/usr/bin/env node
/**
 * Builds the .ipk that is installed on a television and uploaded to LG Seller Lounge (ADR-057):
 *
 *   npm run build && npm run package   →   ipk/com.idanck.shidur_<version>_all.ipk
 *
 * The package is the built app (dist/ plus appinfo/: the manifest, icons and splash) and the JS
 * service (service/), put together by LG's own `ares-package`.
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const appinfo = JSON.parse(readFileSync(join(root, 'appinfo/appinfo.json'), 'utf8'));
const servicePkg = JSON.parse(readFileSync(join(root, 'service/package.json'), 'utf8'));
const services = JSON.parse(readFileSync(join(root, 'service/services.json'), 'utf8'));

function fail(message) {
  console.error(`package: ${message}`);
  process.exit(1);
}

// The rules LG holds the ids to (appinfo.json reference): with a JS service, no minus signs and
// no part starting with a digit, and the service's name starts with the app's id.
if (!/^[a-z0-9][a-z0-9.]+$/.test(appinfo.id) || /\.\d/.test(appinfo.id)) fail(`app id "${appinfo.id}" may hold only a-z, 0-9 and dots, and no part may start with a digit`);
if (!servicePkg.name.startsWith(`${appinfo.id}.`)) fail(`service "${servicePkg.name}" must start with the app id "${appinfo.id}."`);
if (services.id !== servicePkg.name || services.services[0]?.name !== servicePkg.name) fail('service/services.json and service/package.json name different services');
if (!/^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/.test(appinfo.version)) fail(`version "${appinfo.version}" is not major.minor.revision`);
if (!existsSync(join(root, 'dist/index.html'))) fail('dist/ is missing: run `npm run build` first');

const stage = join(root, '.package');
const appDir = join(stage, 'app');
const serviceDir = join(stage, 'service');
const outDir = join(root, 'ipk');
rmSync(stage, { recursive: true, force: true });
mkdirSync(appDir, { recursive: true });
mkdirSync(serviceDir, { recursive: true });
mkdirSync(outDir, { recursive: true });

cpSync(join(root, 'dist'), appDir, { recursive: true });
for (const f of ['appinfo.json', appinfo.icon, appinfo.largeIcon, appinfo.splashBackground]) cpSync(join(root, 'appinfo', f), join(appDir, f));
for (const f of ['package.json', 'services.json', 'index.js', 'net.js']) cpSync(join(root, 'service', f), join(serviceDir, f));

const ares = join(root, 'node_modules/.bin/ares-package');
execFileSync(ares, ['--outdir', outDir, appDir, serviceDir], { stdio: 'inherit' });
rmSync(stage, { recursive: true, force: true });
console.log(`package: ${join('ipk', `${appinfo.id}_${appinfo.version}_all.ipk`)}`);
