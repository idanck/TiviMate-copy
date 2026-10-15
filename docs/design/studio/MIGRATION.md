# אולפן — החלפת שפת העיצוב בקוד

מסמך העברה לסשנים שמממשים את העיצוב (Claude Code). הוא נכתב בסוף סשני העיצוב, כדי שכל סשן יתחיל עם כל ההקשר.

**לפני כל דבר אחר: לקרוא את `CLAUDE.md` בשורש הריפו.** הכללים שם גוברים על המסמך הזה, ובמיוחד שניים:
- הסביבה בענן לא בונה את האפליקציה. ה-CI ב-GitHub הוא הבדיקה היחידה, ואסור לכתוב "נבנה" או "עבר" לפני שה-CI ירוק.
- מיזוג ל-`main` מפרסם עדכון חתום לכל המכשירים המותקנים, וכל שינוי כזה מחייב העלאת `versionCode`.

המסמך מחולק לשניים:
- **סבב 1** הוא ההעברה המקורית. שלבים 1 עד 6d כבר נחתו ב-`main`.
- **סבב 2** הוא כל מה שעוצב אחר כך ועוד לא קיים בקוד.

## מקור האמת של העיצוב

- **הקנבס החי:** <https://claude.ai/artifact/HGAeW2DuNx6d6ETaxito6C>. אפשר לקרוא אותו עם כלי ה-Artifact (action `read`).
- **עותק בריפו:** `docs/design/studio/canvas/`. כל הלוחות הם קובצי `.dc.html` (HTML עם תבנית), ו-`canvas.json` הוא האינדקס ומחלק אותם לעמודים.
- **טוקנים:** `docs/design/studio/studio.css` לטלוויזיה ולערכים המשותפים, ו-`studio-phone.css` שנטען אחריו ומשנה רק מידות לטלפון.

**יחידות.** בלוחות הטלוויזיה (1920×1080) פיקסל אחד שווה חצי dp, כי מסך 1080p הוא 960×540dp. בלוחות הטלפון (390×844) פיקסל אחד שווה dp אחד.

**עמודי הקנבס:**
- טלוויזיה: מסכים, מסכים משלימים, רכיבים, יסודות.
- טלפון: מסכים, מסכים משלימים, רכיבים, יסודות.

לוחות שהשם שלהם מתחיל ב-`P` שייכים לטלפון. לוחות שמתחילים ב-`Tv` הם מסכי טלוויזיה משלימים.

## השפה בקצרה

- **שם:** אולפן. גרפיקה של שידור: שליש תחתון, נורית טאלי ותגים.
- **צבעים ותפקידים** (הפירוט המלא בלוחות "שימוש בצבע" שבעמודי היסודות):
  - **קיו** `#FFB547`: בטלוויזיה, רק פוקוס, כלומר איפה השלט נמצא. בטלפון, שבו אין שלט, רק מה שפעיל או נבחר: לשונית פעילה, פעולה ראשית, וי של בחירה ומתג דלוק.
  - **טאלי** `#FF5A5F`: מה שעל המסך, בשידור חי, או מקליט עכשיו.
  - **הבא** `#9DC0F7`: מה שמגיע אחרי התוכנית הנוכחית.
  - **תקין ושגיאה:** הצלחה, וכשל או פעולה הרסנית.
  - **כל השאר:** בצבעי הטקסט. זה כולל מועדף (כוכב מלא בלבן), קטגוריה וטעינה. **הענבר אף פעם לא מסמן סטטוס, קטגוריה, מועדף, טעינה או הודעה.**
- **גופנים:** Karantina 700 לכותרות של תוכניות ולשם האפליקציה, ו-IBM Plex Sans Hebrew לכל השאר, עם ספרות טבלאיות לשעות.
- **אייקונים:** סט קווי משלנו. הפירוט בסבב 2.

## סבב 1 — ההעברה המקורית (נחתה)

**החלטות שנסגרו:**
1. **ניווט בטלוויזיה.** תפריט צד מימין ברוחב 480px. במצב סגור מוצג פס צר של 112px עם אייקונים. השורה העליונה מכילה את שם האפליקציה מימין ושעון משמאל.
2. **ניווט בטלפון.** חמש לשוניות: ערוצים, מדריך, רדיו, הקלטות, הגדרות. הקלטות מחליפה את VOD (ADR-032).
3. **אקולייזר ברדיו.** המקצב מגיע מ-`TeeAudioProcessor` של Media3 (ADR-031).
4. **רקע מתחלף ברדיו**, והערוץ שעל המסך מודגש באדום.

**נושאים שהיו פתוחים ונסגרו בקוד:**
- ערכות נושא: נשארו רק כהה ו-AMOLED (ADR-028).
- שומר המסך של הרדיו (ADR-030).
- רישיונות הגופנים: בשלב 1.

**פתוח:** גדלי הטקסט בטלוויזיה (ADR-029) מסומנים "זמני" עד בדיקה על טלוויזיה אמיתית.

**מחוץ לתכולה:** שומר המסך לא מעוצב מחדש. לפי החלטת עידן הוא לא יהיה רלוונטי.

## סבב 2 — מה שעוצב אחרי ההעברה ועוד לא בקוד

### 1. מסכים

#### טלוויזיה — עמוד "מסכים"

| לוח | מה | בקוד |
|---|---|---|
| `MainRail` | מסך הבית עם הפס הסגור (המצב הסופי) | `tv/TvHomeScreen.kt` |
| `HomeRails` | מסך הבית אחרי גלילה: המשך צפייה, מתחיל בקרוב, ערוצים | `tv/TvHomeScreen.kt` (`HomeRails`) |
| `Channels` | ערוצים: קבוצות, רשימה ותצוגה מקדימה, עם תג "ארכיון" | `screens/ChannelListScreen.kt` |
| `PlayerTransport` | לחיצה על OK בזמן צפייה: שליש תחתון ושורת כפתורים | `tv/PlaybackControls.kt` |
| `PlayerDetails` | פרטי תוכנית והקלטה, בפאנל מימין מעל הווידאו | `screens/ProgrammeDetails.kt` |
| `PlayerMenu` | לחיצה ארוכה על OK: אפשרויות, בפאנל משמאל | `tv/PlaybackControls.kt` (`PlaybackMenu`) |

#### טלוויזיה — עמוד "מסכים משלימים"

| לוח | בקוד |
|---|---|
| `TvPin` | `common/PinEntry.kt`, `ParentalLock.kt` |
| `TvReconnect`, `TvPlayError` | `common/PlaybackStateOverlay.kt`, `common/ErrorText.kt` |
| `TvUpdatePrompt`, `TvUpdateDownloading`, `TvUpdatePermission` | `common/UpdatePrompt.kt` |
| `TvAddSource` | `screens/AddSourceScreen.kt` |
| `TvChannelMenu`, `TvChannelAdvanced`, `TvGuideMap`, `TvChannelEdit`, `TvGroups`, `TvGroupMenu`, `TvGroupNew` | `screens/ChannelMenu.kt` |
| `TvSearch` | `screens/SearchScreen.kt` |
| `TvVod` | `screens/VodScreen.kt` |
| `TvSettingsSources`, `TvSettingsParental`, `TvSettingsRecording`, `TvSettingsBackup`, `TvSettingsDiagnostics` | `settings/SettingsSections.kt` |
| `TvSettingsUpdates` | `settings/UpdateSettings.kt` |
| `TvFilmstrip` | `tv/ChannelFilmstrip.kt` (הוא גם ה-`MINI_GUIDE`) |
| `TvHomeApps` | `tv/HomeAppStrip.kt` |
| `TvNotice` | `MainActivity.kt` (`SnackbarHost`) |
| `TvEmptyChannels`, `TvEmptyHidden` | `screens/ChannelListScreen.kt` |
| `TvGuideNoData` | `screens/GuideScreen.kt` |

#### טלפון — עמוד "מסכים משלימים"

אותם מסכים בגרסת טלפון:
- `PPin`, `PReconnect`, `PPlayError`
- `PUpdatePrompt`, `PUpdateDownloading`, `PUpdatePermission`
- `PAddSource`
- `PChannelMenu`, `PChannelAdvanced`, `PGuideMap`, `PChannelEdit`, `PGroups`, `PGroupMenu`, `PGroupNew`
- `PSearch`, `PVod`
- `PSettingsSources`, `PSettingsParental`, `PSettingsUpdates`, `PSettingsRecording`, `PSettingsBackup`, `PSettingsDiagnostics`
- `PNoticeBoard`, `PEmptyChannels`, `PEmptyHidden`, `PGuideNoData`

**דפוסים בטלפון:**
- חלונות ותפריטים עולים כגיליון תחתון.
- מצבי הנגן (טעינה ושגיאה) מוצגים בתוך אזור הווידאו של "מתנגן עכשיו".
- לרצועת הערוצים ולשורת האפליקציות אין גרסת טלפון.

### 2. רכיבים שמחליפים את Material

הכל מוצג בלוח "רכיבי בסיס — במקום Material" בשני עמודי הרכיבים.

| ב-Material (בקוד היום) | באולפן — טלוויזיה | באולפן — טלפון | איפה בקוד |
|---|---|---|---|
| `OutlinedTextField` | `Field` (רגיל, פוקוס, שגיאה עם טקסט עזר, לא זמין) | `PField` | `PinEntry`, `AddSourceScreen`, `ChannelMenu`, `SearchScreen`, `VodScreen`, `SettingsSections`, `ChannelListScreen` |
| `TextButton` | `Button` במצב `ghost` | `PButton` במצב `ghost` | 9 קבצים. התוויות: סגירה, ביטול, אחר כך, אישור, דלג, הסתר, בחירת קובץ, הזזת המדריך בשעות, "עכשיו" |
| `Button` | `Button` במצבים `primary`, `secondary` ו-`danger` | `PButton` במצבים `primary`, `secondary` ו-`danger` | אותם קבצים |
| `Switch` | `Toggle` | `PToggle` | `settings/SettingsWidgets.kt`. גם `ListRow` ו-`PListRow` מציירים את המתג דרכם |
| בחירה בין אפשרויות | `Segmented`, משותף לשני המכשירים (`size="phone"`) | אותו רכיב | מפענח וידאו ואודיו ב-`ChannelMenu` |
| `LinearProgressIndicator` | `Progress`, עם אחוז או בלי (`indeterminate`) | אותו רכיב | `UpdatePrompt`, `UpdateSettings`, `SearchScreen`, `VodScreen` |
| `CircularProgressIndicator` | `Loader`: טבעת בצבע הטקסט, לא בענבר | אותו רכיב | `PlaybackStateOverlay` |
| `AlertDialog` (`AppDialog` ב-`common/Panel.kt`) | `Dialog` | `PDialog` | 13 חלונות ב-`ChannelMenu`, `ProgrammeDetails`, `UpdatePrompt` ו-`PinEntry` |
| `Snackbar` | `Notice` | `PNotice` | `MainActivity.kt` |
| (אין רכיב היום) | `EmptyState` | `PEmptyState` | הודעות ריקות ב-`ChannelListScreen` ו-`GuideScreen` |

**כללים לרכיבים:**
- בחלון הרסני בטלוויזיה הפוקוס מתחיל על "ביטול".
- בטלפון, הפעולה הראשית בחלון צבועה בענבר.
- ל-`ListRow` ו-`PListRow` נוספו שלושה דברים:
  - מצב `danger`, שבו התווית באדום.
  - סיום שורה מסוג `status`, שמציג תג צבעוני.
  - מצב `onscreen` עם הדגשה אדומה, כמו בכרטיס.
- בטלפון, תווית ארוכה נשברת לשתי שורות, ותיאור מגיע עד שלוש שורות.

### 3. כללי צבע — שישה מקומות לתקן

| קובץ | מה | היום | צריך להיות |
|---|---|---|---|
| `common/PlaybackStateOverlay.kt` | גלגל הטעינה | `primary` (ענבר) | `Loader` בצבע הטקסט |
| `screens/AddSourceScreen.kt` | תוצאת בדיקת החיבור | `primary` | ירוק עם וי. בכישלון: צבע שגיאה |
| `screens/ProgrammeDetails.kt` | קטגוריית התוכנית | `primary` | צ'יפ אפור על כרטיס |
| `screens/VodScreen.kt` (×2) | כוכב מועדף | `primary` | כוכב מלא (`star-on`) בלבן |
| `settings/SettingsSections.kt` | המקש האחרון באבחון | `primary` | בצבע הטקסט |

### 4. סט האייקונים

**הסט:** 43 אייקונים בלוח "סט האייקונים" בשני עמודי הרכיבים. ההגדרות שלהם (נתיבי SVG) נמצאות ב-`canvas/Icon.dc.html`.

**כללי סגנון:**
- קו בעובי 2 על גריד 24, עם קצוות מעוגלים.
- אייקון מלא רק בניגון, השהיה, הקלטה ומועדף שסומן (`star-on`).

**גדלים:** בטלוויזיה 30/26/22, בטלפון 24/22/20/18.

**מימוש:** כל 35 אייקוני `Icons.Filled.*` שבקוד (86 שימושים ב-15 קבצים) צריכים לעבור לסט משלנו, למשל `ImageVector` ב-`ui/common/StudioIcons.kt`. המיפוי:

| Material | אולפן |
|---|---|
| `PlayArrow`, `PlayCircle` | `play` |
| `Pause` | `pause` |
| `DateRange` | `guide` |
| `StarBorder` / `Star` | `star` / `star-on` |
| `FiberManualRecord` | `record` |
| `Search` | `search` |
| `Settings` | `settings` |
| `KeyboardArrowUp`, `SkipPrevious` | `chevron-up` |
| `KeyboardArrowDown`, `SkipNext` | `chevron-down` |
| `Check` | `check` |
| `Lock` | `lock` |
| `Delete` | `trash` |
| `Replay` | `restart` |
| `AspectRatio` | `aspect` |
| `LiveTv`, `Storage` | `tv` |
| `Radio` | `radio` |
| `VisibilityOff` | `hide` |
| `Info` | `info` |
| `ArrowBack` | `back` |
| `Home` | `home` |
| `Palette` | `appearance` |
| `Wifi` | `network` |
| `Backup` | `backup` |
| `BugReport` | `pulse` |
| `Close` | `close` |
| `History` | `history` |
| `SelectAll` | `grid` |
| `MusicNote` | `music` |
| `Movie` | `movie` |
| `Fullscreen` | `fullscreen` |

### 5. תוספות בעיצוב שלא קיימות היום בקוד

אלה שינויי התנהגות, לא רק מראה. **לפני מימוש כל אחת מהן לשאול את עידן, ואם היא משנה דרישה, לכתוב ADR.**

- **פס השליטה (OK בזמן צפייה):** כפתורי "פרטים" ו"הקלטה". בקוד יש היום רק ערוצים קודם והבא, השהיה, מועדף, מדריך, ושמע, כתוביות ויחס תצוגה.
- **התפריט בלחיצה ארוכה על OK:** שורה "פרטי התוכנית והקלטה".
- **מסך שגיאת ניגון:** כפתורים "נסה שוב", "הגדרות רשת" ו"רשימת ערוצים". בקוד מוצגת היום רק הודעה.
- **קוד הורים בטלוויזיה:** מקלדת ספרות על המסך, נוסף על מקשי המספרים בשלט.
- **חיפוש בטלוויזיה:** פאנל "איפה מחפשים".
- **מדריך:** ערוץ בלי לוח שידורים מוצג כשורה מפוספסת עם "אין נתוני לוח שידורים", ולא נעלם.

### 6. שלבים מוצעים לסבב 2

כל שלב: ענף ו-PR נפרדים, העלאת `versionCode`, ו-CI ירוק.

7. **רכיבי בסיס:** (מומש. הרכיבים ב-`ui/common/StudioControls.kt`, `StudioButton.kt` ו-`Panel.kt`. גלגל הטעינה ב-`PlaybackStateOverlay` כבר עבר ל-`Loader`, כך שבשלב 8 נשארים חמישה תיקונים.)
   - בונים את `StudioField`, `StudioToggle`, `StudioSegmented`, `StudioDialog` (שמחליף את המראה של `AppDialog`), `Loader`, `Notice` ו-`EmptyState`.
   - מוסיפים ל-`StudioButton` את המצבים `ghost` ו-`danger`, ול-`StudioProgress` את המצב בלי אחוז.
   - מחליפים בהם את כל השימושים ברכיבי Material, לפי הטבלה בסעיף 2.
8. **כללי צבע:** ששת התיקונים מסעיף 3. (מומש. נוספו ארבעה מקומות שהתגלו באותו סבב: הכוכב ברשימת הערוצים, נקודת "מתנגן" ברצועת הבית שעברה לטאלי, הווי בתפריט הניגון שעבר לדיו, ו-`Badge` שהפך לצ'יפ אפור.)
9. **סט האייקונים:** מסעיף 4. (מומש: `ui/common/StudioIcons.kt`, ADR-033. התלות ב-`material-icons-extended` הוסרה.)
10. **מסכי טלוויזיה:**
    - כל המסכים בעמוד "מסכים משלימים".
    - מעמוד "מסכים": `Channels`, `HomeRails` ושלושת לוחות "בתוך ערוץ" (רק המראה, בלי התוספות מסעיף 5).
11. **מסכי טלפון:** כל המסכים בעמוד "מסכים משלימים" של הטלפון.

**תיעוד:** לעדכן ADR-023 ו-ADR-025 (משטחים ותפריט אחד) כשהחלונות עוברים ל-`StudioDialog`, ולכתוב ADR לסט האייקונים.

## מיפוי טוקנים לקוד (סבב 1, בתוקף)

| בקנבס | בקוד |
|---|---|
| צבעים (`--st-*` ב-`studio.css`) | `ui/theme/Theme.kt` ואובייקט הצבעים המורחב (`StudioColors`) |
| טיפוגרפיה (`.st-t-*`) | `ui/theme/Type.kt` |
| צורה ומרווח | `ui/tv/TvSurfaces.kt`, `ui/common/TvMetrics.kt` (בטלפון: `TvMetrics.Phone`) |
| פוקוס | `ui/common/Focus.kt` |
| תנועה | `ui/common/Motion.kt` |
| רקע מתחלף ואקולייזר | `ui/common/StationBackdrop.kt` |

**טוקנים שנוספו בסבב 2 ב-`studio.css`:**
- `--st-next`, `--st-onscreen`, `--st-tally-glow`
- `--st-drawer`, `--st-rail`, `--st-dim-open`, `--st-w-rail`, `--st-inset-start`
- האנימציות `st-spin` (טעינה) ו-`st-indet` (התקדמות בלי אחוז)

חלק מהם אולי כבר מומשו בשלבים 6a עד 6d. צריך לבדוק בקוד לפני שמוסיפים.
