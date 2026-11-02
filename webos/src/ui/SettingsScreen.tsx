import { useState } from 'preact/hooks';
import type { Source } from '../data/model';
import { settings, updateSettings } from '../state/settings';
import { guideProgress, guides, refreshAll, refreshGuide, refreshing, refreshSource, removeSource, setSourceEnabled, sources } from '../state/store';
import { Dialog, Toggle } from './common';
import { Icon } from './icons';
import { SourceDialog } from './SourceDialog';

/*
 * Settings on the television. What the computer version has and this one does not (ADR-052):
 * audio remux (the television decodes AC-3 itself), installing as an app, signing in to a server,
 * and backup files (a television has no file picker to read one, nor a downloads folder to
 * write one).
 */

const UA_PRESETS: Array<[string, string]> = [
  ['ברירת המחדל', ''],
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
  const [removing, setRemoving] = useState<Source | null>(null);
  const s = settings.value;
  const customUa = !UA_PRESETS.some(([, v]) => v === s.userAgent);

  return (
    <div class="settings">
      <div class="topbar">
        <h1>הגדרות</h1>
      </div>
      <div class="settings-body">
        <section class="card" aria-labelledby="s-sources">
          <h2 id="s-sources"><Icon name="storage" /> רשימות ולוח שידורים</h2>
          {[...sources.value].sort((a, b) => a.sortIndex - b.sortIndex).map((src) => (
            <SourceRow key={src.id} source={src} onEdit={() => setEditing(src)} onRemove={() => setRemoving(src)} />
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
            <select class="input" style={{ width: 160 }} value={s.epgOffsetMinutes} onChange={(e) => updateSettings({ epgOffsetMinutes: Number((e.target as HTMLSelectElement).value) })}>
              {OFFSETS.map((m) => <option key={m} value={m}>{offsetLabel(m)}</option>)}
            </select>
          </div>
        </section>

        <section class="card" aria-labelledby="s-play">
          <h2 id="s-play"><Icon name="play" /> ניגון</h2>
          <div class="setting">
            <div class="text">
              <span class="label">הפעלת הערוץ האחרון בפתיחה</span>
              <span class="desc">כשפותחים את שידור, הערוץ שנצפה אחרון מתחיל לבד.</span>
            </div>
            <Toggle checked={s.autoplay} onChange={(v) => updateSettings({ autoplay: v })} label="הפעלת הערוץ האחרון בפתיחה" />
          </div>
        </section>

        <section class="card" aria-labelledby="s-net">
          <h2 id="s-net"><Icon name="network" /> רשת</h2>
          <div class="setting">
            <div class="text">
              <span class="label">מזהה לקוח (User agent)</span>
              <span class="desc">נשלח לספק כשקוראים רשימות ולוח שידורים. ספקים דוחים לעיתים לקוחות לא מוכרים, והחלפתו היא הפתרון הרגיל לשגיאת 403.</span>
            </div>
            <select class="input" style={{ width: 240 }} value={customUa ? '__custom' : s.userAgent} onChange={(e) => {
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

        <section class="card" aria-labelledby="s-keys">
          <h2 id="s-keys"><Icon name="grid" /> השלט</h2>
          <dl class="kbd-list">
            <dt>חצים ו-OK</dt><dd>תנועה בין הפריטים ובחירה</dd>
            <dt>חץ למעלה / למטה במסך מלא</dt><dd>הערוץ הקודם / הבא</dd>
            <dt>OK במסך מלא</dt><dd>פרטי הערוץ והתוכנית</dd>
            <dt>0–9</dt><dd>מעבר לערוץ לפי מספר</dd>
            <dt>נגן / השהה</dt><dd>השהיה והמשך</dd>
            <dt>אדום</dt><dd>הערוץ הקודם שנצפה</dd>
            <dt>ירוק</dt><dd>מדריך</dd>
            <dt>צהוב</dt><dd>חיפוש</dd>
            <dt>כחול</dt><dd>רדיו</dd>
            <dt>חזרה</dt><dd>יציאה ממסך מלא, סגירת חלון, חזרה לערוצים, ומשם יציאה מהאפליקציה</dd>
          </dl>
        </section>

        <section class="card" aria-labelledby="s-about">
          <h2 id="s-about"><Icon name="info" /> אודות</h2>
          <div class="muted">
            שידור לטלוויזיה {__APP_VERSION__} · <span class="ltr">{__APP_SHA__}</span>
          </div>
          <div class="faint">נגן בלבד: האפליקציה לא מספקת ערוצים, מלבד רשימת הערוצים הפתוחים בישראל והרדיו הישראלי.</div>
        </section>
      </div>

      {editing && <SourceDialog source={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      {removing && (
        <Dialog title="מחיקת רשימה" onClose={() => setRemoving(null)}>
          <p style={{ margin: 0 }}>{`למחוק את "${removing.name}"? הערוצים והמועדפים שלה יוסרו מהטלוויזיה הזו.`}</p>
          <div class="buttons">
            <button class="btn" onClick={() => setRemoving(null)}>ביטול</button>
            <button class="btn primary" onClick={() => {
              const id = removing.id;
              setRemoving(null);
              void removeSource(id);
            }}>מחיקה</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}

function SourceRow({ source, onEdit, onRemove }: { source: Source; onEdit: () => void; onRemove: () => void }) {
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
          <button class="icon-btn" onClick={onRemove} aria-label={`מחיקת ${source.name}`} title="מחיקה">
            <Icon name="trash" size={18} />
          </button>
        </>
      )}
    </div>
  );
}
