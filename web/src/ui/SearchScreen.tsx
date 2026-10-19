import { useMemo, useState } from 'preact/hooks';
import { clockRange, dayLabel, offsetMs, startOfDay } from '../data/guide';
import type { Channel, Programme } from '../data/model';
import { go, play } from '../state/actions';
import { settings } from '../state/settings';
import { now, programmesOf, radioStations, tvChannels } from '../state/store';
import { Logo } from './common';
import { Icon } from './icons';

const LIMIT = 40;

/** Channels, radio stations, and what the guide has coming up — the same scope the TV's search explains. */
export function SearchScreen() {
  const [query, setQuery] = useState('');
  // Plain lower-case containment: the guide's titles run to hundreds of thousands, and this runs
  // on every key press.
  const q = query.trim().toLowerCase();

  const results = useMemo(() => {
    if (q.length < 2) return null;
    const match = (name: string) => name.toLowerCase().includes(q);
    const channels = tvChannels.value.filter((c) => match(c.name)).slice(0, LIMIT);
    const stations = radioStations.value.filter((c) => match(c.name)).slice(0, LIMIT);
    const programmes: Array<{ channel: Channel; p: Programme }> = [];
    const t = now.value;
    for (const c of tvChannels.value) {
      const shift = offsetMs(c, settings.value.epgOffsetMinutes);
      for (const p of programmesOf(c) ?? []) {
        if (p.end + shift <= t) continue;
        if (match(p.title)) programmes.push({ channel: c, p: { ...p, start: p.start + shift, end: p.end + shift } });
      }
      if (programmes.length > LIMIT * 4) break;
    }
    programmes.sort((a, b) => a.p.start - b.p.start);
    return { channels, stations, programmes: programmes.slice(0, LIMIT) };
  }, [q]);

  const today = startOfDay(now.value);
  return (
    <div class="search">
      <div class="search-head">
        <div class="search-box">
          <Icon name="search" size={20} />
          <input
            class="input"
            type="search"
            autoFocus
            placeholder="חיפוש ערוץ, תחנה או תוכנית"
            value={query}
            onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
            aria-label="חיפוש"
          />
        </div>
        <p class="hint">שמות ערוצים ותחנות, ותוכניות שעוד לא הסתיימו בלוח השידורים.</p>
      </div>
      {results && (
        <div class="results">
          {results.channels.length + results.stations.length + results.programmes.length === 0 && (
            <div class="empty"><p>לא נמצא דבר.</p></div>
          )}
          {results.channels.length > 0 && <h2>ערוצים</h2>}
          {results.channels.map((c) => (
            <button key={c.key} class="result" onClick={() => { play(c); go('live'); }}>
              <Logo url={c.logoUrl} name={c.name} size={40} />
              <span class="body">
                <span class="name" dir="auto">{c.name}</span>
                <span class="sub">{c.group}</span>
              </span>
            </button>
          ))}
          {results.stations.length > 0 && <h2>רדיו</h2>}
          {results.stations.map((c) => (
            <button key={c.key} class="result" onClick={() => { play(c, 'radio'); go('radio'); }}>
              <Logo url={c.logoUrl} name={c.name} size={40} round />
              <span class="body">
                <span class="name" dir="auto">{c.name}</span>
                <span class="sub">{c.group}</span>
              </span>
            </button>
          ))}
          {results.programmes.length > 0 && <h2>בלוח השידורים</h2>}
          {results.programmes.map(({ channel, p }) => {
            const airing = p.start <= now.value;
            const day = startOfDay(p.start);
            return (
              <button key={`${channel.key}@${p.start}`} class="result" onClick={() => { play(channel); go('live'); }}>
                <Logo url={channel.logoUrl} name={channel.name} size={40} />
                <span class="body">
                  <span class="name" dir="auto">{p.title}</span>
                  <span class="sub">
                    {channel.name} · {day === today ? '' : `${dayLabel(p.start)} · `}{clockRange(p.start, p.end)}
                  </span>
                </span>
                {airing && <span class="badge-live">עכשיו</span>}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
