/**
 * Studio's own icon set (ui/common/StudioIcons.kt, ADR-033): a 2-unit line on a 24-unit grid with
 * round ends and joins; only play, pause, record and a set favourite are solid. The paths are the
 * Android set's, verbatim; the few this shell adds (volume off, picture in picture, refresh, edit,
 * sign out, leave full screen) are drawn to the same rule.
 */
const PATHS = {
  play: 'M7 4.5v15l12-7.5z',
  pause: 'M7 5h3.5v14H7zM13.5 5H17v14h-3.5z',
  guide: 'M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM3 10h18M9 10v10',
  star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z',
  starOn: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z',
  search: 'M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13zM20 20l-4.2-4.2',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM10 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z',
  chevronEnd: 'M9 6l6 6-6 6',
  chevronDown: 'M6 9l6 6 6-6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  trash: 'M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13',
  audio: 'M5 10v4h3l4 4V6L8 10zM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11',
  mute: 'M5 10v4h3l4 4V6L8 10zM16 9.5l5 5M21 9.5l-5 5',
  plus: 'M12 5v14M5 12h14',
  tv: 'M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9 21h6',
  radio: 'M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14',
  sport: 'M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18zM3 12h18M12 3v18M5.6 5.6a9 9 0 0 1 0 12.8M18.4 5.6a9 9 0 0 0 0 12.8',
  news: 'M4 5h13v13a2 2 0 0 0 2 2H6a2 2 0 0 1-2-2zM17 9h3v9a2 2 0 0 1-2 2M7 9h3v3H7zM12.5 9H14M12.5 12H14M7 15.5h7',
  info: 'M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18zM12 11v6M12 7.5v.5',
  alert: 'M12 4l9 16H3zM12 10v4M12 17v.5',
  update: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  backup: 'M12 15V4M8 8l4-4 4 4M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4',
  close: 'M6 6l12 12M18 6L6 18',
  history: 'M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4M12 8v4l3 2',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  fullscreen: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  exitFullscreen: 'M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5',
  pip: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM12 12h7v5h-7z',
  refresh: 'M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4',
  edit: 'M4 20h4l10-10-4-4L4 16zM13 7l4 4',
  network: 'M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0M12 19.5v.5',
  storage: 'M4 4h16v7H4zM4 13h16v7H4zM8 7.5h3M8 16.5h3',
  logout: 'M10 4H5v16h5M15 8l4 4-4 4M19 12H9',
  pulse: 'M3 12h4l2-5 4 10 2-5h6',
  music: 'M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z',
} as const;

export type IconName = keyof typeof PATHS;

const FILLED = new Set<IconName>(['play', 'pause', 'starOn']);
/** Drawn left to right and turned for Hebrew, so they point to the end of the line either way. */
const MIRRORED = new Set<IconName>(['chevronEnd']);

export function Icon({ name, size = 20, label }: { name: IconName; size?: number; label?: string }) {
  const filled = FILLED.has(name);
  return (
    <svg
      class={`icon${MIRRORED.has(name) ? ' icon-mirror' : ''}`}
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill={filled ? 'currentColor' : 'none'}
      stroke={filled ? 'none' : 'currentColor'}
      stroke-width={2}
      stroke-linecap="round"
      stroke-linejoin="round"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
