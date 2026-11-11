/**
 * The shell that loaded this bundle (shell/boot.js, ADR-057 0.4.0): it decides which bundle runs
 * (the packaged one or one the app downloaded) and asks for updates. The bundle tells it when the
 * first screen is drawn, and reads what it knows for the corner line and `shidur.state()`.
 *
 * Off the television's package (the dev server, the tests) there is no shell, and none of this
 * does anything.
 */

export interface ShellInfo {
  shell: number;
  selfUpdate: boolean;
  packaged: { version: string; commit: string };
  running: { version: string; commit: string; source: 'packaged' | 'downloaded'; load: 'file' | 'blob' | 'inline'; why: string } | null;
  pending: { version: string; commit: string } | null;
  bad: { version: string; commit: string; reason: string; at: string }[];
  lastCheck: { at: string; result: string; detail: string } | null;
  storage: { ok: boolean; writeMs?: number; readMs?: number; shellChars?: number; error?: string } | null;
  timings: { verifyMs?: number; hashMs?: number; downloadHashMs?: number };
  startedAt?: string;
  events: string[];
}

interface ShellApi {
  started(): void;
  crashed?(reason: string): void;
  info(): ShellInfo;
  check(): void;
}

declare global {
  interface Window {
    shidurShell?: ShellApi;
  }
}

function api(): ShellApi | undefined {
  return typeof window !== 'undefined' ? window.shidurShell : undefined;
}

/**
 * Tells the shell what the first screen is. A working one makes a downloaded bundle good; the
 * crashed screen (ui/Guard.tsx: a render threw) makes it bad, and the shell reloads with the
 * packaged bundle. Called once, after the first render has settled.
 */
export function tellShellFirstScreen() {
  const shell = api();
  if (!shell) return;
  try {
    const crashed = typeof document !== 'undefined' ? document.querySelector('.crashed') : null;
    if (crashed) shell.crashed?.(`the first screen crashed: ${(crashed.querySelector('.ltr')?.textContent ?? '').slice(0, 120)}`);
    else shell.started();
  } catch {
    // an older shell
  }
}

export function shellInfo(): ShellInfo | null {
  try {
    return api()?.info() ?? null;
  } catch {
    return null;
  }
}

/** The corner line: the running bundle's version and commit, and "עדכון" when it was downloaded. */
export function buildLine(): string {
  const downloaded = shellInfo()?.running?.source === 'downloaded';
  return `Shidur ${__APP_VERSION__} · ${__APP_SHA__}${downloaded ? ' · עדכון' : ''}`;
}

/** One line for `shidur.state().report`. */
export function shellLine(): string {
  const s = shellInfo();
  if (!s) return 'no shell (not the packaged app)';
  const r = s.running;
  const check = s.lastCheck ? `${s.lastCheck.result}${s.lastCheck.detail ? ` (${s.lastCheck.detail})` : ''} at ${s.lastCheck.at}` : 'not yet';
  const storage = s.storage
    ? s.storage.ok
      ? `1 MB written in ${s.storage.writeMs} ms, read in ${s.storage.readMs} ms, shell holds ${s.storage.shellChars} chars`
      : `FAILING (${s.storage.error ?? 'read back differs'})`
    : 'not measured';
  return [
    `shell ${s.shell} · packaged ${s.packaged.version} ${s.packaged.commit} · self-update ${s.selfUpdate ? 'on' : 'off'}`,
    `running ${r ? `${r.version} ${r.commit} (${r.source}, loaded as ${r.load})${r.why ? ` — ${r.why}` : ''}` : '-'}`,
    `pending ${s.pending ? `${s.pending.version} ${s.pending.commit}` : '-'} · bad ${s.bad.length ? s.bad.map((b) => `${b.version} ${b.commit} (${b.reason})`).join(', ') : '-'}`,
    `last check: ${check}`,
    `storage: ${storage} · verify ${s.timings.verifyMs ?? '-'} ms · stored hash ${s.timings.hashMs ?? '-'} ms · download hash ${s.timings.downloadHashMs ?? '-'} ms`,
    `shell events: ${s.events.join(' | ') || 'none'}`,
  ].join('\n');
}
