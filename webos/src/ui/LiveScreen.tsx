import { computed } from '@preact/signals';
import { useEffect, useLayoutEffect, useRef } from 'preact/hooks';
import { clockRange, nowNext, offsetMs, progress } from '../data/guide';
import type { Channel } from '../data/model';
import { engine, playback } from '../player/engine';
import { groupKey, liveFilter, play, playerSlot, setFilter, showScreen, toggleFullscreen, visibleChannels } from '../state/actions';
import { settings } from '../state/settings';
import {
  favourites, guideProgress, isFavourite, now, programmesOf, recents, refreshing, sources, storageOk, toggleFavourite, tvChannels,
} from '../state/store';
import { Logo, Progress, VirtualList } from './common';
import { Icon, type IconName } from './icons';
import { flashInfo } from './PlayerHost';
import { focus } from './spatial';
import { remoteUsed } from './remote';
import { watch } from './watch';
import { buildLine } from './shell';

interface GroupEntry {
  id: string;
  label: string;
  count: number;
}

/** The groups of every enabled TV source, in playlist order, each under its source when there are several. */
const groupsBySource = computed(() => {
  const bySource = new Map<string, Map<string, GroupEntry>>();
  for (const c of tvChannels.value) {
    let groups = bySource.get(c.sourceId);
    if (!groups) bySource.set(c.sourceId, (groups = new Map()));
    const id = groupKey(c);
    const g = groups.get(id);
    if (g) g.count++;
    else groups.set(id, { id, label: c.group || 'ללא קבוצה', count: 1 });
  }
  return [...bySource.entries()].map(([sourceId, groups]) => ({
    source: sources.value.find((s) => s.id === sourceId)?.name ?? '',
    groups: [...groups.values()],
  }));
});

const SMART: Array<{ id: string; label: string; icon: IconName }> = [
  { id: 'all', label: 'כל הערוצים', icon: 'grid' },
  { id: 'fav', label: 'מועדפים', icon: 'star' },
  { id: 'recent', label: 'נצפו לאחרונה', icon: 'history' },
  { id: 'sport', label: 'ספורט ישראלי', icon: 'sport' },
  { id: 'news', label: 'חדשות ישראל', icon: 'news' },
];

export function filterLabel(id: string): string {
  const smart = SMART.find((s) => s.id === id);
  if (smart) return smart.label;
  for (const s of groupsBySource.value) for (const g of s.groups) if (g.id === id) return g.label;
  return 'כל הערוצים';
}

function smartCount(id: string): number | undefined {
  if (id === 'all') return tvChannels.value.length;
  if (id === 'fav') return tvChannels.value.filter((c) => favourites.value.has(c.key)).length;
  if (id === 'recent') return recents.value.length || undefined;
  return undefined;
}

/** The channel row's height, in the computer version's pixels (ui/common.tsx `rem`). */
const ROW = 68;

/**
 * The channel list: groups on the right, the channels, and on the left the picture in its box with
 * what is on. OK on a channel plays it; OK on the one already playing opens it full screen.
 */
export function LiveScreen() {
  watch.renders++;
  const slot = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = slot.current;
    playerSlot.value = el;
    return () => {
      if (playerSlot.value === el) playerSlot.value = null;
    };
  }, []);

  const list = visibleChannels.value;
  const current = playback.value.role === 'tv' ? playback.value.channel : null;
  const selected = current ? list.findIndex((c) => c.key === current.key) : -1;
  const loading = Object.values(refreshing.value).includes('playlist');
  const many = groupsBySource.value.length > 1;

  // The first channels to arrive take the remote, unless the viewer has already moved it: on a
  // first start the list is empty until the playlist has loaded.
  const hasRows = list.length > 0;
  useEffect(() => {
    if (!hasRows || remoteUsed.value) return;
    const row = document.querySelector<HTMLElement>('.ch-row.selected') ?? document.querySelector<HTMLElement>('.ch-row');
    if (row) focus(row);
  }, [hasRows]);

  return (
    <div class="live">
      <aside class="groups" aria-label="קבוצות" data-scroll>
        {SMART.map((s) => (
          <button key={s.id} class="group-btn" aria-pressed={liveFilter.value === s.id} onClick={() => setFilter(s.id)}>
            <Icon name={s.icon} size={18} />
            <span class="name">{s.label}</span>
            <span class="count">{smartCount(s.id)}</span>
          </button>
        ))}
        {groupsBySource.value.map((s) => (
          <div key={s.source} class="group-section">
            <h3>{many ? s.source : 'קבוצות'}</h3>
            {s.groups.map((g) => (
              <button key={g.id} class="group-btn" aria-pressed={liveFilter.value === g.id} onClick={() => setFilter(g.id)}>
                <span class="name" dir="auto">{g.label}</span>
                <span class="count">{g.count}</span>
              </button>
            ))}
          </div>
        ))}
      </aside>

      <section class="channels" aria-label="ערוצים">
        <div class="channels-head">
          <span class="title" dir="auto">{filterLabel(liveFilter.value)}</span>
          <span class="faint">{list.length.toLocaleString('he-IL')}</span>
        </div>
        {list.length === 0 ? (
          <EmptyList loading={loading} filtered={liveFilter.value !== 'all'} />
        ) : (
          // A new filter is a new list: from its top, or from the channel on screen.
          <VirtualList key={liveFilter.value} items={list} rowHeight={ROW} scrollTo={selected} render={(c) => <ChannelRow channel={c} selected={c.key === current?.key} />} />
        )}
      </section>

      <section class="stage" aria-label="צפייה">
        <div class="slot" ref={slot}>
          {!current && <div class="slot-empty">בחר ערוץ ולחץ OK</div>}
        </div>
        {current ? <NowPanel channel={current} /> : <Status />}
        <div class="build faint ltr">{buildLine()}</div>
      </section>
    </div>
  );
}

/** What the sources are doing, while nothing plays: so a list that does not arrive says why. */
function Status() {
  const tv = sources.value.filter((s) => s.enabled && s.role === 'tv');
  return (
    <div class="status">
      {!storageOk.value && <p class="warn">האחסון של הטלוויזיה לא זמין: הרשימה לא תישמר עד הפעם הבאה.</p>}
      {tv.map((s) => (
        <p key={s.id}>
          <span class="name">{s.name}</span>
          {': '}
          <span class={s.lastRefreshOk === false ? 'warn' : 'faint'}>
            {refreshing.value[s.id] === 'playlist' ? 'טוען…' : (s.lastRefreshMessage ?? 'עוד לא נטען')}
          </span>
          {guideProgress.value[s.id] && <span class="faint">{` · ${guideProgress.value[s.id]}`}</span>}
        </p>
      ))}
      <div class="actions">
        <GuideButton />
        <RadioButton />
      </div>
    </div>
  );
}

function EmptyList({ loading, filtered }: { loading: boolean; filtered: boolean }) {
  if (loading) {
    return (
      <div class="empty">
        <div class="spinner" />
        <p>טוען ערוצים…</p>
      </div>
    );
  }
  if (filtered) {
    return (
      <div class="empty">
        <p>אין ערוצים שמתאימים לסינון.</p>
        <button class="btn" onClick={() => setFilter('all')}>הצג את כל הערוצים</button>
      </div>
    );
  }
  return (
    <div class="empty">
      <h2>אין עדיין ערוצים</h2>
      <p>הרשימה לא נטענה. הסיבה כתובה מימין לתמונה.</p>
    </div>
  );
}

function activate(channel: Channel, selected: boolean) {
  if (!selected) {
    play(channel);
    return;
  }
  // The channel on screen: try again after a failure, else full screen.
  if (playback.value.status === 'error') engine.retry();
  else {
    toggleFullscreen();
    flashInfo.value++;
  }
}

function ChannelRow({ channel, selected }: { channel: Channel; selected: boolean }) {
  const air = nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes));
  const fav = isFavourite(channel.key);
  return (
    <div
      class={`ch-row${selected ? ' selected' : ''}`}
      role="button"
      tabIndex={0}
      aria-current={selected ? 'true' : undefined}
      onClick={() => activate(channel, selected)}
      onKeyDown={(e) => {
        if (e.keyCode !== 13 || e.target !== e.currentTarget) return;
        e.preventDefault();
        activate(channel, selected);
      }}
    >
      <span class="num">{channel.number ?? ''}</span>
      <Logo url={channel.logoUrl} name={channel.name} size={44} />
      <div class="body">
        <span class="name-line">
          <span class="name" dir="auto">{channel.name}</span>
          {fav && <Icon name="starOn" size={14} />}
          {selected && <span class="onscreen-tag">על המסך</span>}
        </span>
        {air.now ? (
          <>
            <span class="now" dir="auto">{air.now.title}</span>
            <Progress value={progress(air.now, now.value)} live={selected} />
          </>
        ) : (
          air.next && <span class="now faint">{`הבא: ${air.next.title}`}</span>
        )}
      </div>
    </div>
  );
}

function NowPanel({ channel }: { channel: Channel }) {
  const air = nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes));
  const fav = isFavourite(channel.key);
  return (
    <div class="now-panel">
      <div class="now-head">
        <Logo url={channel.logoUrl} name={channel.name} size={44} />
        <div class="now-name">
          <span class="ch-name" dir="auto">{channel.name}</span>
          <span class="faint">{[channel.number, channel.group].filter(Boolean).join(' · ')}</span>
        </div>
        <span class="badge-live">שידור חי</span>
      </div>
      {air.now ? (
        <>
          <h2 class="now-title" dir="auto">{air.now.title}</h2>
          <div class="now-meta">
            <span>{clockRange(air.now.start, air.now.end)}</span>
            {air.now.subtitle && <span>{` · ${air.now.subtitle}`}</span>}
          </div>
          <Progress value={progress(air.now, now.value)} live />
          {air.now.description && <p class="now-desc" dir="auto">{air.now.description}</p>}
        </>
      ) : (
        <p class="muted">אין מידע מלוח השידורים לערוץ הזה.</p>
      )}
      {air.next && (
        <div class="next-line">
          <span class="label">הבא</span>
          <span>{clockRange(air.next.start, air.next.end)}</span>
          <span dir="auto">{air.next.title}</span>
        </div>
      )}
      <div class="actions">
        <button class="btn" onClick={() => toggleFullscreen()}>
          <Icon name="fullscreen" size={18} /> <span>מסך מלא</span>
        </button>
        <button class="btn" onClick={() => toggleFavourite(channel.key)} aria-pressed={fav}>
          <Icon name={fav ? 'starOn' : 'star'} size={18} /> <span>{fav ? 'במועדפים' : 'הוסף למועדפים'}</span>
        </button>
        <GuideButton />
        <RadioButton />
      </div>
    </div>
  );
}

/** Opens the radio (ui/RadioScreen.tsx); the remote's Yellow key does the same from anywhere. */
function RadioButton() {
  return (
    <button class="btn" onClick={() => showScreen('radio')}>
      <Icon name="radio" size={18} /> <span>רדיו</span>
    </button>
  );
}

/** Opens the guide (ui/GuideScreen.tsx); the remote's Green key does the same from anywhere. */
function GuideButton() {
  return (
    <button class="btn" onClick={() => showScreen('guide')}>
      <Icon name="guide" size={18} /> <span>לוח שידורים</span>
    </button>
  );
}
