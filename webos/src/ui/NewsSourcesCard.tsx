import { useState } from 'preact/hooks';
import { host, MAX_SOURCES, sourceLabel, SUGGESTED_NEWS_SOURCES } from '../data/news';
import { addNewsSource, digests, removeNewsSource, setNewsSource } from '../state/news';
import { settings } from '../state/settings';
import { toast, Toggle } from './common';
import { Icon } from './icons';

/**
 * The headlines' sources (FR-UI-33): what News mode's panel of headlines and its running strip
 * read (ADR-053). Android keeps them under the screensaver; this version has no screensaver, so
 * they have a card of their own. Nothing is on by default: which newsroom scrolls past on
 * somebody's television is theirs to choose (ADR-037). The suggestions spare typing an address
 * with a remote.
 */
export function NewsSourcesCard() {
  const list = settings.value.newsSources;
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const suggested = new Set(SUGGESTED_NEWS_SOURCES.map((s) => s.url.toLowerCase()));
  const own = list.filter((s) => !suggested.has(s.url.toLowerCase()));

  const add = async () => {
    setBusy(true);
    const result = await addNewsSource(url);
    setBusy(false);
    if (result === 'added') {
      setUrl('');
      toast('המקור נוסף');
    } else if (result === 'refused') {
      toast(`צריך כתובת https:// שעוד לא ברשימה (עד ${MAX_SOURCES} מקורות)`);
    } else {
      toast('לא נמצא מקור מבזקים בכתובת הזו');
    }
  };

  return (
    <section class="card" aria-labelledby="s-news">
      <h2 id="s-news"><Icon name="news" /> <span>מבזקים</span></h2>
      <div class="faint">המבזקים במצב חדשות: בפאנל המבזקים ובפס הרץ. המבזקים מכל המקורות מוצגים יחד לפי השעה, ולכל מקור צבע משלו.</div>
      {SUGGESTED_NEWS_SOURCES.map((s) => {
        const entry = list.find((e) => e.url.toLowerCase() === s.url.toLowerCase());
        const kind = s.flashes ? (s.timed ? 'מבזקים' : 'מבזקים, בלי שעה') : 'כתבות';
        return (
          <div class="setting" key={s.id}>
            <div class="text">
              <span class="label">{s.name}</span>
              <span class="desc">{kind}</span>
            </div>
            <Toggle checked={!!entry?.enabled} onChange={(v) => setNewsSource(entry?.url ?? s.url, v)} label={`מבזקים מ-${s.name}`} />
          </div>
        );
      })}
      {own.map((s) => (
        <div class="setting" key={s.url}>
          <div class="text">
            <span class="label" dir="auto">{sourceLabel(s, digests.value[s.url])}</span>
            <span class="desc ltr">{host(s.url)}</span>
          </div>
          <Toggle checked={s.enabled} onChange={(v) => setNewsSource(s.url, v)} label={`מבזקים מ-${host(s.url)}`} />
          <button class="icon-btn" onClick={() => removeNewsSource(s.url)} aria-label={`הסרת ${host(s.url)}`} title="הסרה">
            <Icon name="trash" size={18} />
          </button>
        </div>
      ))}
      <div class="setting">
        <input
          class="input ltr-input"
          value={url}
          placeholder="https://"
          onInput={(e) => setUrl((e.target as HTMLInputElement).value)}
          aria-label="כתובת מקור חדש (RSS או JSON)"
        />
        <button class="btn" disabled={busy || !url.trim()} onClick={() => void add()}>
          <Icon name="plus" size={18} /> <span>הוספת מקור</span>
        </button>
      </div>
    </section>
  );
}
