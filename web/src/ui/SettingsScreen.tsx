import { signal } from '@preact/signals';
import { useState } from 'preact/hooks';
import { BackupError, readBackup, writeBackup, type BackupImport } from '../data/backup';
import type { Source } from '../data/model';
import { settings, updateSettings } from '../state/settings';
import {
  addFavourites, addSources, favourites, guideProgress, guides, refreshAll, refreshGuide, refreshing, refreshSource,
  removeSource, serverInfo, setSourceEnabled, sources,
} from '../state/store';
import { accountSignOut } from '../account/signout';
import { Dialog, toast, Toggle } from './common';
import { Icon } from './icons';
import { SourceDialog } from './SourceDialog';

/** The browser's install offer, kept from the moment it is made (main.tsx) until it is used. */
export const installPrompt = signal<{ prompt: () => Promise<void> } | null>(null);

const UA_PRESETS: Array<[string, string]> = [
  ['ברירת המחדל של השרת', ''],
  ['VLC', 'VLC/3.0.20 LibVLC/3.0.20'],
  ['Kodi', 'Kodi/20.2 (Linux; Android 12) libcurl/7.79.1'],
  ['Lavf', 'Lavf/60.16.100'],
  ['Android TV', 'Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'],
  ['Smart TV', 'Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36'],
  ['MX Player', 'MXPlayer/1.0'],
];

const OFFSETS = Array.from({ length: 49 }, (_, i) => (i - 24) * 30);

function offsetLabel(m: number) {
  if (m === 0) return 'ללא היסט';
  const sign = m > 0 ? '+' : '−';
  const h = Math.floor(Math.abs(m) / 60);
  const min = Math.abs(m) % 60;
  return `⁦${sign}${h}:${String(min).padStart(2, '0')}⁩`;
}

export function SettingsScreen() {
  const [editing, setEditing] = useState<Source | 'new' | null>(null);
  const [pendingImport, setPendingImport] = useState<BackupImport | null>(null);
  const [withPasswords, setWithPasswords] = useState(false);
  const s = settings.value;
  const info = serverInfo.value;
  const customUa = !UA_PRESETS.some(([, v]) => v === s.userAgent);

  const onBackupFile = async (file: File) => {
    try {
      setPendingImport(readBackup(await file.text(), sources.value));
    } catch (err) {
      toast(err instanceof BackupError ? err.message : 'לא ניתן לקרוא את הקובץ');
    }
  };

  const applyImport = async (r: BackupImport) => {
    setPendingImport(null);
    if (r.epgOffsetMinutes !== undefined || r.userAgent) {
      updateSettings({
        ...(r.epgOffsetMinutes !== undefined ? { epgOffsetMinutes: r.epgOffsetMinutes } : {}),
        ...(r.userAgent ? { userAgent: r.userAgent } : {}),
      });
    }
    addFavourites(r.favourites);
    toast(`יובאו ${r.sources.length} רשימות ו-${r.favourites.length} מועדפים`);
    await addSources(r.sources);
  };

  const exportBackup = () => {
    const text = writeBackup(sources.value, favourites.value, { epgOffsetMinutes: s.epgOffsetMinutes, userAgent: s.userAgent || undefined }, withPasswords);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    a.download = `shidur-${new Date().toISOString().slice(0, 10)}.tivicopy.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };

  return (
    <div class="settings">
      <div class="topbar">
        <h1>הגדרות</h1>
      </div>
      <div class="settings-body">
        <section class="card" aria-labelledby="s-sources">
          <h2 id="s-sources"><Icon name="storage" /> רשימות ולוח שידורים</h2>
          {[...sources.value].sort((a, b) => a.sortIndex - b.sortIndex).map((src) => (
            <SourceRow key={src.id} source={src} onEdit={() => setEditing(src)} />
          ))}
          <div class="actions">
            <button class="btn primary" onClick={() => setEditing('new')}>
              <Icon name="plus" size={18} /> הוספת רשימה
            </button>
            <button class="btn" onClick={() => void refreshAll()} disabled={Object.keys(refreshing.value).length > 0}>
              <Icon name="refresh" size={18} /> רענון הכול
            </button>
          </div>
        </section>

        <section class="card" aria-labelledby="s-guide">
          <h2 id="s-guide"><Icon name="guide" /> לוח שידורים</h2>
          <div class="setting">
            <div class="text">
              <span class="label">היסט זמנים כללי</span>
              <span class="desc">לספק שמפרסם את לוח השידורים מוקדם או מאוחר מהשעה האמיתית. ערוץ עם tvg-shift משלו לא מושפע.</span>
            </div>
            <select class="input" style={{ width: 140 }} value={s.epgOffsetMinutes} onChange={(e) => updateSettings({ epgOffsetMinutes: Number((e.target as HTMLSelectElement).value) })}>
              {OFFSETS.map((m) => <option key={m} value={m}>{offsetLabel(m)}</option>)}
            </select>
          </div>
        </section>

        <section class="card" aria-labelledby="s-play">
          <h2 id="s-play"><Icon name="play" /> ניגון</h2>
          <div class="setting">
            <div class="text">
              <span class="label">הפעלת הערוץ האחרון בפתיחה</span>
              <span class="desc">כמו בטלוויזיה. הדפדפן עשוי להתחיל בלי קול עד לחיצה ראשונה.</span>
            </div>
            <Toggle checked={s.autoplay} onChange={(v) => updateSettings({ autoplay: v })} label="הפעלת הערוץ האחרון בפתיחה" />
          </div>
          <div class="setting">
            <div class="text">
              <span class="label">המרת שמע בשרת</span>
              <span class="desc">
                {info?.remux
                  ? 'דפדפנים לא מנגנים שמע AC-3 (דולבי). השרת ממיר אותו ל-AAC כשצריך, בלי לגעת בתמונה.'
                  : 'לא זמין: השרת רץ בלי ffmpeg.'}
              </span>
            </div>
            <div class="segmented" aria-disabled={!info?.remux}>
              {([['auto', 'כשצריך'], ['always', 'תמיד'], ['never', 'אף פעם']] as const).map(([v, label]) => (
                <button type="button" key={v} disabled={!info?.remux} aria-pressed={s.remux === v} onClick={() => updateSettings({ remux: v })}>{label}</button>
              ))}
            </div>
          </div>
        </section>

        <section class="card" aria-labelledby="s-net">
          <h2 id="s-net"><Icon name="network" /> רשת</h2>
          <div class="setting">
            <div class="text">
              <span class="label">מזהה דפדפן (User agent)</span>
              <span class="desc">ספקים דוחים לעיתים לקוחות לא מוכרים. החלפת ה-user agent היא הפתרון הרגיל לשגיאת 403.</span>
            </div>
            <select class="input" style={{ width: 220 }} value={customUa ? '__custom' : s.userAgent} onChange={(e) => {
              const v = (e.target as HTMLSelectElement).value;
              if (v !== '__custom') updateSettings({ userAgent: v });
              else updateSettings({ userAgent: s.userAgent || ' ' });
            }}>
              {UA_PRESETS.map(([label, v]) => <option key={label} value={v}>{label}</option>)}
              <option value="__custom">מותאם אישית…</option>
            </select>
          </div>
          {customUa && (
            <input class="input ltr-input" value={s.userAgent.trim()} onChange={(e) => updateSettings({ userAgent: (e.target as HTMLInputElement).value.trim() })} aria-label="User agent מותאם" />
          )}
        </section>

        <section class="card" aria-labelledby="s-backup">
          <h2 id="s-backup"><Icon name="backup" /> גיבוי ושחזור</h2>
          <div class="setting">
            <div class="text">
              <span class="label">ייבוא גיבוי מהאפליקציה</span>
              <span class="desc">קובץ ‎.tivicopy.json‎ מהגדרות ← גיבוי ושחזור בטלוויזיה או בטלפון. רשימות, מועדפים, היסט ו-user agent.</span>
            </div>
            <label class="btn">
              <Icon name="update" size={18} /> בחירת קובץ
              <input type="file" accept=".json,application/json" class="sr-only" onChange={(e) => {
                const f = (e.target as HTMLInputElement).files?.[0];
                if (f) void onBackupFile(f);
                (e.target as HTMLInputElement).value = '';
              }} />
            </label>
          </div>
          <div class="setting">
            <div class="text">
              <span class="label">ייצוא גיבוי</span>
              <span class="desc">קובץ שהאפליקציה בטלוויזיה ובטלפון יודעת לשחזר.</span>
            </div>
            <label class="check muted">
              <input type="checkbox" checked={withPasswords} onChange={(e) => setWithPasswords((e.target as HTMLInputElement).checked)} />
              כולל סיסמאות (בטקסט גלוי)
            </label>
            <button class="btn" onClick={exportBackup}><Icon name="backup" size={18} /> ייצוא</button>
          </div>
        </section>

        <section class="card" aria-labelledby="s-install">
          <h2 id="s-install"><Icon name="tv" /> התקנה במחשב</h2>
          <div class="setting">
            <div class="text">
              <span class="label">שידור כאפליקציה</span>
              <span class="desc">
                {installPrompt.value
                  ? 'חלון משלה, אייקון בשולחן העבודה ובתפריט התחל, ופתיחה בלי שורת הכתובת.'
                  : window.matchMedia('(display-mode: standalone)').matches
                    ? 'שידור כבר מותקן ופתוח כאפליקציה.'
                    : 'ב-Chrome או ב-Edge: סמל ההתקנה בשורת הכתובת, או תפריט ← "התקנת שידור".'}
              </span>
            </div>
            {installPrompt.value && (
              <button class="btn primary" onClick={() => void installPrompt.value?.prompt().then(() => (installPrompt.value = null))}>
                <Icon name="update" size={18} /> התקנה
              </button>
            )}
          </div>
        </section>

        <section class="card" aria-labelledby="s-keys">
          <h2 id="s-keys"><Icon name="grid" /> קיצורי מקלדת</h2>
          <dl class="kbd-list">
            <dt><kbd>↑</kbd> <kbd>↓</kbd></dt><dd>הערוץ הקודם / הבא (גם <kbd>PgUp</kbd> <kbd>PgDn</kbd>)</dd>
            <dt><kbd>0</kbd>–<kbd>9</kbd></dt><dd>מעבר לערוץ לפי מספר</dd>
            <dt><kbd>Space</kbd></dt><dd>השהיה והמשך</dd>
            <dt><kbd>F</kbd> / לחיצה כפולה</dt><dd>מסך מלא</dd>
            <dt><kbd>M</kbd></dt><dd>השתקה</dd>
            <dt><kbd>P</kbd></dt><dd>תמונה בתוך תמונה</dd>
            <dt><kbd>Backspace</kbd></dt><dd>חזרה לערוץ הקודם שנצפה</dd>
            <dt><kbd>/</kbd></dt><dd>חיפוש</dd>
            <dt><kbd>1</kbd>–<kbd>5</kbd> עם <kbd>Alt</kbd></dt><dd>ערוצים, מדריך, רדיו, חיפוש, הגדרות</dd>
          </dl>
        </section>

        <section class="card" aria-labelledby="s-about">
          <h2 id="s-about"><Icon name="info" /> אודות</h2>
          <div class="muted">
            שידור למחשב {__APP_VERSION__} · <span class="ltr">{__APP_SHA__}</span>
            {info && <> · שרת {info.version}</>}
          </div>
          <div class="faint">נגן בלבד: האפליקציה לא מספקת ערוצים, מלבד רשימת הערוצים הפתוחים בישראל והרדיו הישראלי.</div>
          {info?.authRequired && (
            <div>
              <button class="btn" onClick={async () => {
                // With a Google account, out of it too, or the page would sign straight back in.
                if (accountSignOut.leave) await accountSignOut.leave().catch(() => {});
                else await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
                location.reload();
              }}>
                <Icon name="logout" size={18} /> התנתקות
              </button>
            </div>
          )}
        </section>
      </div>

      {editing && <SourceDialog source={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      {pendingImport && (
        <Dialog title="ייבוא גיבוי" onClose={() => setPendingImport(null)}>
          <p style={{ margin: 0 }}>
            {pendingImport.sources.length === 0
              ? 'כל הרשימות שבגיבוי כבר קיימות כאן.'
              : `יתווספו ${pendingImport.sources.length} רשימות: ${pendingImport.sources.map((x) => x.name).join(', ')}.`}
          </p>
          <p style={{ margin: 0 }} class="muted">{`${pendingImport.favourites.length} מועדפים.`}</p>
          {pendingImport.missingCredentials.length > 0 && (
            <p class="error-text" style={{ margin: 0 }}>
              {`הגיבוי נוצר בלי סיסמאות. אחרי הייבוא צריך להזין שם משתמש וסיסמה ל: ${pendingImport.missingCredentials.join(', ')}.`}
            </p>
          )}
          {pendingImport.skipped > 0 && <p class="faint" style={{ margin: 0 }}>{`${pendingImport.skipped} קבצים מקומיים לא יובאו: הם נשארו במכשיר שקרא אותם.`}</p>}
          <div class="buttons">
            <button class="btn" onClick={() => setPendingImport(null)}>ביטול</button>
            <button class="btn primary" onClick={() => void applyImport(pendingImport)}>ייבוא</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}

function SourceRow({ source, onEdit }: { source: Source; onEdit: () => void }) {
  const busy = refreshing.value[source.id];
  const guide = guides.value[source.id];
  const kind = source.builtIn ? 'מובנית' : source.type === 'XTREAM' ? 'Xtream' : source.type === 'M3U_FILE' ? 'קובץ' : 'M3U';
  const role = source.role === 'radio' ? ' · רדיו' : '';
  const status = busy === 'playlist'
    ? 'טוען ערוצים…'
    : busy === 'guide'
      ? `טוען לוח שידורים… ${guideProgress.value[source.id] ?? ''}`
      : [source.lastRefreshMessage, guideProgress.value[source.id] ?? (guide ? `לוח שידורים: ${guide.matched} מתוך ${guide.total}` : '')]
          .filter(Boolean).join(' · ') || 'עדיין לא נטען';
  return (
    <div class="source-row">
      <Toggle checked={source.enabled} onChange={(v) => void setSourceEnabled(source.id, v)} label={`הפעלת ${source.name}`} />
      <div class="text">
        <span class="name">{source.name} <span class="kind">{kind}{role}</span></span>
        <span class={`status${source.lastRefreshOk === false && !busy ? ' bad' : ''}`}>{status}</span>
      </div>
      <button class="icon-btn" disabled={!!busy || !source.enabled} onClick={() => void refreshSource(source.id)} aria-label={`רענון ${source.name}`} title="רענון">
        <Icon name="refresh" size={18} />
      </button>
      {source.resolvedEpgUrl && (
        <button class="icon-btn" disabled={!!busy || !source.enabled} onClick={() => void refreshGuide(source.id)} aria-label={`עדכון לוח השידורים של ${source.name}`} title="עדכון לוח שידורים">
          <Icon name="guide" size={18} />
        </button>
      )}
      {!source.builtIn && (
        <>
          <button class="icon-btn" onClick={onEdit} aria-label={`עריכת ${source.name}`} title="עריכה">
            <Icon name="edit" size={18} />
          </button>
          <button class="icon-btn" onClick={() => {
            if (confirm(`למחוק את "${source.name}"? הערוצים והמועדפים שלה יוסרו מהמחשב הזה.`)) void removeSource(source.id);
          }} aria-label={`מחיקת ${source.name}`} title="מחיקה">
            <Icon name="trash" size={18} />
          </button>
        </>
      )}
    </div>
  );
}
