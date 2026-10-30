import { useState } from 'preact/hooks';
import type { Source, SourceType } from '../data/model';
import { saveSource } from '../state/store';
import { Dialog, toast } from './common';

const TYPE_LABEL: Record<SourceType, string> = { M3U_URL: 'כתובת M3U', XTREAM: 'Xtream', M3U_FILE: 'קובץ' };

const isAddress = (s: string) => /^https?:\/\/\S+$/i.test(s.trim());

/** Adding or editing a playlist: an M3U address, an Xtream account, or an M3U file from this computer. */
export function SourceDialog({ source, onClose }: { source?: Source; onClose: () => void }) {
  const [type, setType] = useState<SourceType>(source?.type ?? 'M3U_URL');
  const [name, setName] = useState(source?.name ?? '');
  const [url, setUrl] = useState(source?.url ?? '');
  const [username, setUsername] = useState(source?.username ?? '');
  const [password, setPassword] = useState(source?.password ?? '');
  const [outputFormat, setOutputFormat] = useState<'TS' | 'HLS'>(source?.outputFormat ?? 'TS');
  const [epgUrl, setEpgUrl] = useState(source?.epgUrl ?? '');
  const [userAgent, setUserAgent] = useState(source?.userAgent ?? '');
  const [referer, setReferer] = useState(source?.headers?.Referer ?? '');
  const [fileText, setFileText] = useState(source?.fileText ?? '');
  const [fileName, setFileName] = useState('');
  const [error, setError] = useState('');

  const submit = (e: Event) => {
    e.preventDefault();
    if (type !== 'M3U_FILE' && !isAddress(url)) return setError('כתובת צריכה להתחיל ב-http:// או https://');
    if (type === 'XTREAM' && (!username.trim() || !password)) return setError('חסרים שם משתמש או סיסמה');
    if (type === 'M3U_FILE' && !fileText) return setError('בחר קובץ M3U');
    if (epgUrl && !isAddress(epgUrl)) return setError('כתובת לוח השידורים אינה תקינה');
    const headers = { ...(source?.headers ?? {}) };
    if (referer.trim()) headers.Referer = referer.trim();
    else delete headers.Referer;
    const next: Source = {
      id: source?.id ?? `src-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      name: name.trim() || (type === 'XTREAM' ? 'Xtream' : type === 'M3U_FILE' ? fileName || 'קובץ' : 'רשימה'),
      type,
      role: source?.role ?? 'tv',
      url: type === 'M3U_FILE' ? '' : url.trim(),
      username: type === 'XTREAM' ? username.trim() : undefined,
      password: type === 'XTREAM' ? password : undefined,
      outputFormat,
      userAgent: userAgent.trim() || undefined,
      headers,
      epgUrl: epgUrl.trim() || undefined,
      enabled: source?.enabled ?? true,
      fileText: type === 'M3U_FILE' ? fileText : undefined,
      sortIndex: source?.sortIndex ?? Date.now(),
      lastRefreshUtc: undefined,
    };
    onClose();
    toast(source ? 'הרשימה עודכנה, טוען מחדש…' : 'הרשימה נוספה, טוען ערוצים…');
    void saveSource(next);
  };

  return (
    <Dialog title={source ? 'עריכת רשימה' : 'הוספת רשימה'} onClose={onClose}>
      <form style={{ display: 'flex', flexDirection: 'column', gap: 14 }} onSubmit={submit}>
        {!source && (
          <div class="segmented" role="group" aria-label="סוג">
            {(['M3U_URL', 'XTREAM', 'M3U_FILE'] as SourceType[]).map((t) => (
              <button type="button" key={t} aria-pressed={type === t} onClick={() => setType(t)}>
                {TYPE_LABEL[t]}
              </button>
            ))}
          </div>
        )}
        <label class="field">
          <span>שם</span>
          <input class="input" value={name} onInput={(e) => setName((e.target as HTMLInputElement).value)} placeholder="למשל: הספק שלי" />
        </label>
        {type !== 'M3U_FILE' && (
          <label class="field">
            <span>{type === 'XTREAM' ? 'כתובת השרת' : 'כתובת הרשימה'}</span>
            <input
              class="input ltr-input"
              value={url}
              onInput={(e) => setUrl((e.target as HTMLInputElement).value)}
              placeholder={type === 'XTREAM' ? 'http://provider.example:8080' : 'https://example.com/playlist.m3u'}
              inputMode="url"
              autoComplete="off"
            />
          </label>
        )}
        {type === 'XTREAM' && (
          <>
            <label class="field">
              <span>שם משתמש</span>
              <input class="input ltr-input" value={username} onInput={(e) => setUsername((e.target as HTMLInputElement).value)} autoComplete="off" />
            </label>
            <label class="field">
              <span>סיסמה</span>
              <input class="input ltr-input" type="password" value={password} onInput={(e) => setPassword((e.target as HTMLInputElement).value)} autoComplete="new-password" />
            </label>
            <div class="setting">
              <div class="text">
                <span class="label">פורמט השידור</span>
                <span class="desc">TS הוא ברירת המחדל של רוב הספקים. HLS רק אם הספק מאפשר.</span>
              </div>
              <div class="segmented">
                {(['TS', 'HLS'] as const).map((f) => (
                  <button type="button" key={f} aria-pressed={outputFormat === f} onClick={() => setOutputFormat(f)}>{f}</button>
                ))}
              </div>
            </div>
          </>
        )}
        {type === 'M3U_FILE' && (
          <label class="field">
            <span>קובץ M3U</span>
            <input
              class="input"
              type="file"
              accept=".m3u,.m3u8,audio/x-mpegurl,application/x-mpegurl,text/plain"
              onChange={async (e) => {
                const f = (e.target as HTMLInputElement).files?.[0];
                if (!f) return;
                setFileName(f.name.replace(/\.m3u8?$/i, ''));
                setFileText(await f.text());
              }}
            />
            {fileText && <span class="hint">{`${fileText.split('\n').filter((l) => l.startsWith('#EXTINF')).length.toLocaleString('he-IL')} רשומות בקובץ`}</span>}
          </label>
        )}
        <details>
          <summary>מתקדם</summary>
          <div>
            <label class="field">
              <span>כתובת לוח שידורים (XMLTV)</span>
              <input class="input ltr-input" value={epgUrl} onInput={(e) => setEpgUrl((e.target as HTMLInputElement).value)} placeholder="ריק = מהרשימה עצמה" />
            </label>
            <label class="field">
              <span>User agent לרשימה הזו</span>
              <input class="input ltr-input" value={userAgent} onInput={(e) => setUserAgent((e.target as HTMLInputElement).value)} placeholder="ריק = ההגדרה הכללית" />
            </label>
            <label class="field">
              <span>Referer</span>
              <input class="input ltr-input" value={referer} onInput={(e) => setReferer((e.target as HTMLInputElement).value)} />
            </label>
          </div>
        </details>
        {error && <div class="error-text" role="alert">{error}</div>}
        <div class="buttons">
          <button type="button" class="btn" onClick={onClose}>ביטול</button>
          <button type="submit" class="btn primary">{source ? 'שמירה' : 'הוספה'}</button>
        </div>
      </form>
    </Dialog>
  );
}
