import { computed } from '@preact/signals';
import { useLayoutEffect, useRef } from 'preact/hooks';
import { clockRange, nowNext, offsetMs, progress } from '../data/guide';
import type { Channel } from '../data/model';
import { playback } from '../player/engine';
import {
  go, groupKey, liveFilter, play, playerSlot, setFilter, textFilter, toggleFullscreen, visibleChannels,
} from '../state/actions';
import { settings } from '../state/settings';
import { favourites, isFavourite, now, programmesOf, recents, refreshing, sources, toggleFavourite, tvChannels } from '../state/store';
import { Logo, Progress, VirtualList } from './common';
import { Icon, type IconName } from './icons';

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

export function GroupOptions() {
  return (
    <>
      {SMART.map((s) => (
        <option value={s.id} key={s.id}>{s.label}</option>
      ))}
      {groupsBySource.value.map((s) => (
        <optgroup label={s.source} key={s.source}>
          {s.groups.map((g) => (
            <option value={g.id} key={g.id}>{`${g.label} (${g.count})`}</option>
          ))}
        </optgroup>
      ))}
    </>
  );
}

export function LiveScreen() {
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

  return (
    <div class="live">
      <aside class="groups" aria-label="קבוצות">
        {SMART.map((s) => (
          <button key={s.id} class="group-btn" aria-pressed={liveFilter.value === s.id} onClick={() => setFilter(s.id)}>
            <Icon name={s.icon} size={18} />
            <span class="name">{s.label}</span>
            <span class="count">{smartCount(s.id)}</span>
          </button>
        ))}
        {groupsBySource.value.map((s) => (
          <div key={s.source}>
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
          <div class="row">
            <span class="title" dir="auto">{filterLabel(liveFilter.value)}</span>
            <span class="faint">{list.length.toLocaleString('he-IL')}</span>
          </div>
          <select class="input group-select" value={liveFilter.value} onChange={(e) => setFilter((e.target as HTMLSelectElement).value)} aria-label="קבוצה">
            <GroupOptions />
          </select>
          <div class="search-box">
            <Icon name="search" size={18} />
            <input
              class="input"
              type="search"
              placeholder="סינון לפי שם או מספר"
              value={textFilter.value}
              onInput={(e) => (textFilter.value = (e.target as HTMLInputElement).value)}
              aria-label="סינון ערוצים"
            />
          </div>
        </div>
        {list.length === 0 ? (
          <EmptyList loading={loading} filtered={liveFilter.value !== 'all' || textFilter.value !== ''} />
        ) : (
          <VirtualList items={list} rowHeight={68} scrollTo={selected} render={(c) => <ChannelRow channel={c} selected={c.key === current?.key} />} />
        )}
      </section>

      <section class="stage" aria-label="צפייה">
        <div class="slot" ref={slot} />
        {current ? <NowPanel channel={current} /> : <div class="empty"><p>בחר ערוץ מהרשימה כדי לצפות.</p></div>}
      </section>
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
        <button class="btn" onClick={() => { setFilter('all'); textFilter.value = ''; }}>הצג את כל הערוצים</button>
      </div>
    );
  }
  return (
    <div class="empty">
      <h2>אין עדיין ערוצים</h2>
      <p>הוסף רשימת M3U או חשבון Xtream, או ייבא גיבוי מהאפליקציה בטלוויזיה.</p>
      <button class="btn primary" onClick={() => go('settings')}>
        <Icon name="plus" size={18} /> הוספת רשימה
      </button>
    </div>
  );
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
      onClick={() => !selected && play(channel)}
      onDblClick={() => toggleFullscreen(document.querySelector('.player-host'))}
      onKeyDown={(e) => {
        if (e.key === 'Enter') play(channel);
      }}
    >
      <span class="num">{channel.number ?? ''}</span>
      <Logo url={channel.logoUrl} name={channel.name} size={44} />
      <div class="body">
        <span class="name" dir="auto">{channel.name}</span>
        {air.now ? (
          <>
            <span class="now" dir="auto">{air.now.title}</span>
            <Progress value={progress(air.now, now.value)} live={selected} />
          </>
        ) : (
          air.next && <span class="now faint">{`הבא: ${air.next.title}`}</span>
        )}
      </div>
      <button
        class={`icon-btn star${fav ? ' on' : ''}`}
        onClick={(e) => {
          e.stopPropagation();
          toggleFavourite(channel.key);
        }}
        aria-label={fav ? 'הסר ממועדפים' : 'הוסף למועדפים'}
        aria-pressed={fav}
      >
        <Icon name={fav ? 'starOn' : 'star'} size={18} />
      </button>
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
        <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <span class="ch-name" dir="auto">{channel.name}</span>
          <span class="faint">{[channel.number, channel.group].filter(Boolean).join(' · ')}</span>
        </div>
        <span class="badge-live" style={{ marginInlineStart: 'auto' }}>שידור חי</span>
      </div>
      {air.now ? (
        <>
          <h2 class="now-title" dir="auto">{air.now.title}</h2>
          <div class="now-meta">
            <span>{clockRange(air.now.start, air.now.end)}</span>
            {air.now.subtitle && <span>· {air.now.subtitle}</span>}
            {air.now.category && <span class="faint">· {air.now.category}</span>}
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
        <button class="btn" onClick={() => toggleFavourite(channel.key)} aria-pressed={fav}>
          <Icon name={fav ? 'starOn' : 'star'} size={18} /> {fav ? 'במועדפים' : 'הוסף למועדפים'}
        </button>
        <button class="btn" onClick={() => toggleFullscreen(document.querySelector('.player-host'))}>
          <Icon name="fullscreen" size={18} /> מסך מלא
        </button>
        <button class="btn" onClick={() => go('guide')}>
          <Icon name="guide" size={18} /> לוח שידורים
        </button>
      </div>
    </div>
  );
}
