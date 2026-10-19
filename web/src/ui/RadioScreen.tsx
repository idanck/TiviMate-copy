import { computed } from '@preact/signals';
import { radioCategory, radioRegion, REGION_ORDER, type RadioRegion } from '../data/classify';
import type { Channel } from '../data/model';
import { engine, playback, volume } from '../player/engine';
import { play } from '../state/actions';
import { radioStations, refreshing, sources } from '../state/store';
import { Logo } from './common';
import { Icon } from './icons';

const REGION_LABEL: Record<RadioRegion, string> = {
  NORTH: 'צפון',
  CENTER: 'מרכז',
  JERUSALEM: 'ירושלים',
  SOUTH: 'דרום',
  UNKNOWN: 'אזורים נוספים',
};

/**
 * The radio's shelves, as on the television and the phone (ADR-015): national first, then the
 * regional stations by region from the north down, then a shelf per genre, then the rest.
 */
const shelves = computed(() => {
  const national: Channel[] = [];
  const regional = new Map<RadioRegion, Channel[]>();
  const genres = new Map<string, Channel[]>();
  const other: Channel[] = [];
  for (const s of radioStations.value) {
    const cat = radioCategory(s.group, s.name);
    if (cat.kind === 'NATIONAL') national.push(s);
    else if (cat.kind === 'REGIONAL') {
      const r = radioRegion(s.name);
      regional.set(r, [...(regional.get(r) ?? []), s]);
    } else if (cat.kind === 'GENRE') genres.set(cat.label, [...(genres.get(cat.label) ?? []), s]);
    else other.push(s);
  }
  const out: Array<{ title: string; stations: Channel[] }> = [];
  if (national.length) out.push({ title: 'ארצי', stations: national });
  for (const r of REGION_ORDER) {
    const list = regional.get(r);
    if (list?.length) out.push({ title: `אזורי · ${REGION_LABEL[r]}`, stations: list });
  }
  for (const [label, list] of genres) out.push({ title: label, stations: list });
  if (other.length) out.push({ title: 'אחר', stations: other });
  return out;
});

export function RadioScreen() {
  const pb = playback.value;
  const current = pb.role === 'radio' ? pb.channel : null;
  const loading = sources.value.some((s) => s.role === 'radio' && refreshing.value[s.id] === 'playlist');

  return (
    <div class="radio">
      <div class="topbar">
        <h1>רדיו</h1>
      </div>
      {current && <NowPlaying station={current} />}
      {shelves.value.length === 0 && (
        <div class="empty">
          {loading ? <div class="spinner" /> : <Icon name="radio" size={36} />}
          <p>{loading ? 'טוען תחנות…' : 'אין תחנות רדיו. אפשר להפעיל את "רדיו ישראלי" בהגדרות.'}</p>
        </div>
      )}
      {shelves.value.map((shelf) => (
        <section class="shelf" key={shelf.title}>
          <h2>{shelf.title}</h2>
          <div class="tiles">
            {shelf.stations.map((s) => {
              const on = current?.key === s.key;
              return (
                <button
                  key={s.key}
                  class={`tile${on ? ' on' : ''}`}
                  aria-pressed={on}
                  onClick={() => (on ? engine.togglePause() : play(s, 'radio'))}
                >
                  <Logo url={s.logoUrl} name={s.name} size={84} round />
                  <span class="name" dir="auto">{s.name}</span>
                </button>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}

function NowPlaying({ station }: { station: Channel }) {
  const pb = playback.value;
  const v = volume.value;
  const playing = pb.status === 'playing';
  const paused = pb.status === 'paused' || pb.status === 'blocked';
  const message =
    pb.status === 'loading' ? 'מתחבר…' :
    pb.status === 'error' || pb.status === 'reconnecting' ? pb.message :
    paused ? 'בהשהיה' : 'מתנגן עכשיו';
  return (
    <div class="radio-hero">
      <Logo url={station.logoUrl} name={station.name} size={120} round />
      <div class="info">
        <span class={pb.status === 'error' ? 'error-text' : 'muted'} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span class={`eq${playing ? ' on' : ''}`} aria-hidden="true"><i /><i /><i /><i /></span>
          {message}
        </span>
        <span class="station" dir="auto">{station.name}</span>
        <div class="controls">
          {pb.status === 'error' ? (
            <button class="btn" onClick={() => engine.retry()}>
              <Icon name="refresh" size={18} /> נסה שוב
            </button>
          ) : (
            <button class="btn primary" onClick={() => engine.togglePause()} aria-label={paused ? 'נגן' : 'השהה'}>
              <Icon name={paused ? 'play' : 'pause'} size={18} /> {paused ? 'נגן' : 'השהה'}
            </button>
          )}
          <button class="icon-btn" onClick={() => engine.toggleMute()} aria-label={v.muted ? 'הפעל קול' : 'השתק'}>
            <Icon name={v.muted ? 'mute' : 'audio'} />
          </button>
          <input
            class="volume"
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={v.muted ? 0 : v.level}
            onInput={(e) => engine.setVolume(Number((e.target as HTMLInputElement).value))}
            aria-label="עוצמת קול"
          />
          <button class="btn ghost" onClick={() => engine.stop()}>
            <Icon name="close" size={18} /> עצור
          </button>
        </div>
      </div>
    </div>
  );
}
