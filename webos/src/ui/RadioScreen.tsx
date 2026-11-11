import { computed } from '@preact/signals';
import { useEffect } from 'preact/hooks';
import { radioMenu, type RadioRegion, type RadioShelf } from '../data/classify';
import type { Channel } from '../data/model';
import { engine, playback } from '../player/engine';
import { play, radioFilter, setRadioFilter, showScreen, visibleStations } from '../state/actions';
import { shelfKey } from '../state/radio';
import { favourites, isFavourite, radioStations, recents, refreshing, sources, toggleFavourite } from '../state/store';
import { Logo, VirtualList } from './common';
import { Icon, type IconName } from './icons';
import { remoteUsed } from './remote';
import { focus } from './spatial';
import { watch } from './watch';
import { buildLine } from './shell';

const REGION_LABEL: Record<RadioRegion, string> = {
  NORTH: 'צפון',
  CENTER: 'מרכז',
  JERUSALEM: 'ירושלים',
  SOUTH: 'דרום',
  UNKNOWN: 'אזורים נוספים',
};

/** What a shelf of the radio menu is called, as on Android (ADR-050). */
export function shelfLabel(s: RadioShelf): string {
  return s.kind === 'NATIONAL' ? 'ארצי' : s.kind === 'REGIONAL' ? REGION_LABEL[s.region] : s.label;
}

const SMART: Array<{ id: string; label: string; icon: IconName }> = [
  { id: 'all', label: 'כל התחנות', icon: 'grid' },
  { id: 'fav', label: 'מועדפים', icon: 'star' },
  { id: 'recent', label: 'נשמעו לאחרונה', icon: 'history' },
];

/**
 * The menu (ADR-050): one flat list, national, the regions from the north, "other regions", then
 * the genres; a shelf with no station is left out.
 */
const menu = computed(() =>
  radioMenu(radioStations.value).map((shelf) => ({ key: shelfKey(shelf), label: shelfLabel(shelf) })),
);

function radioFilterLabel(id: string): string {
  return SMART.find((s) => s.id === id)?.label ?? menu.value.find((m) => m.key === id)?.label ?? 'כל התחנות';
}

/** The radio's row height, as the channel list's (ui/LiveScreen.tsx). */
const ROW = 68;

/**
 * The radio (ADR-015): the channel list's layout repointed at the radio playlist, as Android does
 * — the menu on the right, the stations, and on the left the station that plays. Radio is heard,
 * not seen: there is no picture slot, so the player keeps the sound and draws nothing. OK on a
 * station plays it; OK on the one playing pauses or resumes it.
 */
export function RadioScreen() {
  watch.renders++;
  const list = visibleStations.value;
  const current = playback.value.role === 'radio' ? playback.value.channel : null;
  const selected = current ? list.findIndex((c) => c.key === current.key) : -1;
  const loading = sources.value.some((s) => s.role === 'radio' && refreshing.value[s.id] === 'playlist');
  const all = radioStations.value;

  const hasRows = list.length > 0;
  useEffect(() => {
    if (!hasRows || remoteUsed.value) return;
    const row = document.querySelector<HTMLElement>('.ch-row.selected') ?? document.querySelector<HTMLElement>('.ch-row');
    if (row) focus(row);
  }, [hasRows]);

  const count = (id: string) =>
    id === 'all' ? all.length : id === 'fav' ? all.filter((c) => favourites.value.has(c.key)).length : recents.value.filter((k) => all.some((c) => c.key === k)).length || undefined;

  return (
    <div class="live radio">
      <aside class="groups" aria-label="תפריט הרדיו" data-scroll>
        {SMART.map((s) => (
          <button key={s.id} class="group-btn" aria-pressed={radioFilter.value === s.id} onClick={() => setRadioFilter(s.id)}>
            <Icon name={s.icon} size={18} />
            <span class="name">{s.label}</span>
            <span class="count">{count(s.id)}</span>
          </button>
        ))}
        {menu.value.length > 0 && (
          <div class="group-section">
            <h3>קטגוריות</h3>
            {menu.value.map((m) => (
              <button key={m.key} class="group-btn" aria-pressed={radioFilter.value === m.key} onClick={() => setRadioFilter(m.key)}>
                <span class="name" dir="auto">{m.label}</span>
              </button>
            ))}
          </div>
        )}
      </aside>

      <section class="channels" aria-label="תחנות">
        <div class="channels-head">
          <span class="title" dir="auto">{radioFilterLabel(radioFilter.value)}</span>
          <span class="faint">{list.length.toLocaleString('he-IL')}</span>
        </div>
        {list.length === 0 ? (
          <div class="empty">
            {loading ? <div class="spinner" /> : <Icon name="radio" size={36} />}
            <p>{loading ? 'טוען תחנות…' : all.length ? 'אין תחנות במדף הזה.' : 'אין תחנות רדיו: רשימת הרדיו לא נטענה.'}</p>
            {!loading && all.length > 0 && <button class="btn" onClick={() => setRadioFilter('all')}>הצג את כל התחנות</button>}
          </div>
        ) : (
          <VirtualList key={radioFilter.value} items={list} rowHeight={ROW} scrollTo={selected} render={(c) => <StationRow station={c} selected={c.key === current?.key} />} />
        )}
      </section>

      <section class="stage" aria-label="רדיו">
        {current ? <NowPlaying station={current} /> : <RadioStatus />}
        <div class="build faint ltr">{buildLine()}</div>
      </section>
    </div>
  );
}

function activate(station: Channel, selected: boolean) {
  if (!selected) {
    play(station, 'radio');
    return;
  }
  if (playback.value.status === 'error') engine.retry();
  else engine.togglePause();
}

function StationRow({ station, selected }: { station: Channel; selected: boolean }) {
  const pb = playback.value;
  const fav = isFavourite(station.key);
  return (
    <div
      class={`ch-row${selected ? ' selected' : ''}`}
      role="button"
      tabIndex={0}
      aria-current={selected ? 'true' : undefined}
      onClick={() => activate(station, selected)}
      onKeyDown={(e) => {
        if (e.keyCode !== 13 || e.target !== e.currentTarget) return;
        e.preventDefault();
        activate(station, selected);
      }}
    >
      <Logo url={station.logoUrl} name={station.name} size={44} round />
      <div class="body">
        <span class="name-line">
          <span class="name" dir="auto">{station.name}</span>
          {fav && <Icon name="starOn" size={14} />}
          {selected && <span class="onscreen-tag">{pb.status === 'playing' ? 'מתנגן' : pb.status === 'paused' ? 'בהשהיה' : 'נבחר'}</span>}
        </span>
        {station.group && <span class="now faint" dir="auto">{station.group}</span>}
      </div>
    </div>
  );
}

/** The station that plays, its state, and the keys for it: heard, with nothing to see. */
function NowPlaying({ station }: { station: Channel }) {
  const pb = playback.value;
  const playing = pb.status === 'playing';
  const paused = pb.status === 'paused' || pb.status === 'blocked';
  const fav = isFavourite(station.key);
  const message =
    pb.status === 'loading' ? 'מתחבר…' :
    pb.status === 'error' || pb.status === 'reconnecting' ? (pb.message ?? 'לא ניתן לנגן את התחנה') :
    paused ? 'בהשהיה' : 'מתנגן עכשיו';
  return (
    <div class="radio-now">
      <Logo url={station.logoUrl} name={station.name} size={160} round />
      <div class="radio-state">
        <span class={`eq${playing ? ' on' : ''}`} aria-hidden="true"><i /><i /><i /><i /></span>
        <span class={pb.status === 'error' ? 'warn' : 'muted'}>{message}</span>
      </div>
      <h2 class="radio-name" dir="auto">{station.name}</h2>
      {station.group && <p class="faint" dir="auto">{station.group}</p>}
      <div class="actions">
        {pb.status === 'error' ? (
          <button class="btn" onClick={() => engine.retry()}>
            <Icon name="refresh" size={18} /> <span>נסה שוב</span>
          </button>
        ) : (
          <button class="btn" onClick={() => engine.togglePause()}>
            <Icon name={paused ? 'play' : 'pause'} size={18} /> <span>{paused ? 'נגן' : 'השהה'}</span>
          </button>
        )}
        <button class="btn" onClick={() => engine.stop()}>
          <Icon name="close" size={18} /> <span>עצור</span>
        </button>
        <button class="btn" onClick={() => toggleFavourite(station.key)} aria-pressed={fav}>
          <Icon name={fav ? 'starOn' : 'star'} size={18} /> <span>{fav ? 'במועדפים' : 'הוסף למועדפים'}</span>
        </button>
      </div>
      <p class="faint radio-keys">▶❚❚ השהיה · BACK או צהוב חזרה לערוצים</p>
      <div class="actions">
        <TvButton />
      </div>
    </div>
  );
}

/** What the radio source is doing, while nothing plays. */
function RadioStatus() {
  const radio = sources.value.filter((s) => s.enabled && s.role === 'radio');
  return (
    <div class="status radio-idle">
      <Icon name="radio" size={48} />
      <p>בחר תחנה ולחץ OK</p>
      {radio.map((s) => (
        <p key={s.id}>
          <span class="name">{s.name}</span>
          {': '}
          <span class={s.lastRefreshOk === false ? 'warn' : 'faint'}>
            {refreshing.value[s.id] === 'playlist' ? 'טוען…' : (s.lastRefreshMessage ?? 'עוד לא נטען')}
          </span>
        </p>
      ))}
      <div class="actions">
        <TvButton />
      </div>
    </div>
  );
}

function TvButton() {
  return (
    <button class="btn" onClick={() => showScreen('live')}>
      <Icon name="tv" size={18} /> <span>ערוצי טלוויזיה</span>
    </button>
  );
}
