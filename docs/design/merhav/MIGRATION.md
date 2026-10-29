# מרחב — שפת העיצוב בקוד

מסמך העברה לסשנים שמממשים את "מרחב" (Claude Code). הוא ממפה את הטוקנים ואת הרכיבים של הקנבס לקובצי Kotlin, ומפרט מה כבר מומש ומה נשאר. ההחלטה עצמה ב-ADR-046.

**לפני כל דבר אחר: לקרוא את `CLAUDE.md` בשורש הריפו.** הכללים שם גוברים על המסמך הזה, ובמיוחד שניים:
- הסביבה בענן לא בונה את האפליקציה. ה-CI ב-GitHub הוא הבדיקה היחידה, ואסור לכתוב "נבנה" או "עבר" לפני שה-CI ירוק.
- מיזוג ל-`main` מפרסם עדכון חתום לכל המכשירים המותקנים, וכל שינוי כזה מחייב העלאת `versionCode`.

## מקור האמת של העיצוב

- **הקנבס החי:** <https://claude.ai/artifact/HGAeW2DuNx6d6ETaxito6C>. אפשר לקרוא אותו עם כלי ה-Artifact (action `read`).
- **עותק בריפו:** `docs/design/merhav/canvas/` (לוחות `.dc.html` ו-`canvas.json`).
- **טוקנים:** `studio.css` לטלוויזיה ולערכים המשותפים, ו-`studio-phone.css` שנטען אחריו ומשנה מידות לטלפון, ואת "קיו" לענבר. שמות הטוקנים (`--st-*`) נשארו של "אולפן", כדי שכל לוח וכל שורת קוד שממופה אליהם ימשיכו לעבוד.
- **לוחות ההצעה המאושרים:** `Sp*.dc.html` ו-`spatial.css` (עמוד `spatial`). הם המראה; השלד האמיתי הוא `studio.css`.

**יחידות.** בלוחות הטלוויזיה (1920×1080) פיקסל אחד שווה חצי dp. בלוחות הטלפון (390×844) פיקסל אחד שווה dp אחד.

## השפה בקצרה

- **התוכן הוא הסביבה.** השידור נשאר מאחורי הכול, והממשק צף מעליו בשכבות זכוכית. עומק (צל, קצה מואר) במקום קווים ומסגרות.
- **זכוכית מעושנת**, בשלושה עוביים: דקה (שורות, אריחים, צ'יפים וכפתורים במנוחה), רגילה (פאנלים), עבה (חלונות, גיליונות ופאנלים גדולים). אף פעם לא לבנה.
- **מגירות** (תפריט הצד, תפריט הנגינה, תפריט הערוץ, תפריטי הפאנלים): אטומות בשני השלישים שליד קצה המסך, ומשם מעבר הדרגתי ל-75% אטימות לכיוון התוכן. בלי טשטוש.
- **פוקוס בטלוויזיה** — שני טיפולים בלבד:
  1. **פקדים** (כפתור, צ'יפ, מקש, שורת תפריט, שורת רשימה שמתנהגת כמו פריט תפריט): המשטח המואר `#F4F6FB`, תוכן כהה, צל והגדלה (1.06, ושורה רחבה 1.03).
  2. **אריחי תוכן** (כרטיס ערוץ, פוסטר, תא במדריך, דיסק תחנה, שורת ערוץ ברשימה): זכוכית מוארת, טבעת לבנה, צל והגדלה. הטקסט נשאר לבן.
- **נוכחי ונבחר = ענבר** `#FFB547`: המסך הנוכחי בתפריט הצד (גם פס ענבר בקצה המסך), היום, הצ'יפ או המקטע שנבחרו, הערך שנבחר ברשימה. אף פעם לא לסטטוס, קטגוריה, מועדף, טעינה או הודעה.
- **בטלפון** אין פוקוס, ו"קיו" הוא ענבר: הלשונית הפעילה, הפעולה הראשית, וי ומתג דלוק.
- **אדום (טאלי)** רק למה שעל המסך, בשידור חי או מקליט עכשיו, ותמיד עם מילה.
- **גופן אחד:** Noto Sans Hebrew, משקלים 400 עד 800. "תצוגה" הוא אותו גופן ב-800.

## מיפוי טוקנים לקוד

### צבע — `ui/theme/Theme.kt` (`StudioColors`)

| בקנבס | בקוד |
|---|---|
| `--st-ground` | `ground` (ב-AMOLED: שחור אמיתי) |
| `--st-card` (זכוכית דקה), `--st-control` | `card`, `control` |
| `--st-surface` (זכוכית) | `surface` |
| `--st-panel` (זכוכית עבה) | `panel` |
| `--st-card-focus` (זכוכית מוארת) | `cardFocus` |
| `--st-airing`, `--st-future`, `--st-past` | `airing`, `future`, `past` |
| `--st-glass-lit` | `glassLit` |
| `--st-glass-fallback` | `glassFallback` |
| `--st-light` | `light` |
| `--st-drawer`, `--st-drawer-start`, `--st-drawer-end` | `drawer`, והמעבר ב-`Modifier.drawerSurface` (`ui/common/Glass.kt`) |
| `--st-rail` | `rail` (ב-`railSurface` של `ui/tv/SideMenu.kt`) |
| `--st-dim-open`, `--st-scrim` | `dimOpen`, `scrim` |
| `--st-hairline`, `--st-hairline-soft`, `--st-track`, `--st-halo` | `hairline`, `hairlineSoft`, `track`, `halo` |
| `--st-edge` | `edgeHighlight` ו-`edgeLine`, ב-`Modifier.litEdge` |
| `--st-ink`, `--st-ink-2`, `--st-ink-3` | `ink`, `ink2`, `ink3` |
| `--st-next` | `next` |
| `--st-cue`, `--st-on-cue` | `cue`, `onCue` — בטלוויזיה המשטח המואר, בטלפון ענבר (`MerhavPhone`) |
| `--st-current`, `--st-current-glass`, `--st-current-edge`, `--st-on-current` | `current`, `currentGlass`, `currentEdge`, `onCurrent` |
| `--st-tally`, `--st-on-tally`, `--st-onscreen`, `--st-tally-glow` | `tally`, `onTally`, `onScreen`, `tallyGlow` |
| `--st-live-glass`, `--st-live-ink` (והקצה האדום של `st-live`) | `liveGlass`, `liveInk`, `liveEdge` |
| `--st-error`, `--st-ok` | `error`, `ok` |
| `--st-amb-*`, `--st-saver-*`, `--st-eq-bar` | ללא שינוי |

התפקידים של Material (`MaterialTheme.colorScheme`) מקבלים את הזכוכית *מעל הקרקע* (`compositeOver`), כדי שמשטחים ש-Material צובע בעצמו יישארו אטומים. `primary` הוא "קיו".

### טיפוגרפיה — `ui/theme/Type.kt`

| בקנבס | בקוד |
|---|---|
| `--st-font-ui`, `--st-font-display` | `NotoSansHebrew` (`res/font/noto_sans_hebrew_*.ttf`) |
| `.st-t-hero` … `.st-t-caption` | `StudioType.hero` … `caption` (`TelevisionRamp`, `PhoneRamp`) |
| `.st-num` | אין צורך: הספרות טבלאיות בכל משקל |

טלוויזיה (dp): hero 44, title 28, title-s 20, h1 17, action 14, nav 14, body 12, meta 11, caption 10. טלפון: 32, 26, 22, 18, 16, 12, 16, 14, 13.

הגופן נחתך מהקובץ המשתנה של `google/fonts` (`ofl/notosanshebrew`) עם `fontTools.varLib.instancer` ב-`wdth` 100 ובמשקלים 400/500/600/700/800, ומוקטן ללטינית, עברית ופיסוק (שיטת ADR-019). הרישיון ב-`assets/licenses/noto-sans-hebrew-ofl.txt` וב-`docs/licenses/NotoSansHebrew-OFL.txt`.

### צורה ומידות — `ui/theme/Theme.kt` (`Shapes`) ו-`ui/common/TvMetrics.kt`

| בקנבס | בקוד | טלוויזיה / טלפון |
|---|---|---|
| `--st-r-pill`, `--st-r-chip`, `--st-r-badge` | `CircleShape`, `PILL_CORNER`, `chipCorner`, `shapes.extraSmall` | גלולה |
| `--st-r-control` | `controlCorner`, `shapes.medium` | 11 / 16 |
| `--st-r-card` | `rowCorner` (כרטיסים ושורות) | 12 / 20 |
| `--st-r-tile` | `shapes.small` | 10 / 14 |
| `--st-r-panel`, `--st-r-sheet` | `panelCorner`, `shapes.large`, `shapes.extraLarge` | 22 / 28 |
| תא במדריך (`GuideCell`, `PGuideCell`) | `cellCorner` | 12 / 8 |
| `--st-w-rail` | `homeNavRailCollapsedWidth` | 56 |
| `--st-w-drawer` | `homeNavRailWidth` | 190 |
| `--st-h-row` | גובה `ListRow` ושורות תפריט הצד | 32 |
| `--st-h-control-l`, `--st-h-control-m` | `StudioButton` | 40, 32 |
| `--st-h-chip` | `StudioChip` | 28 / 44 |
| `--st-inset-browse` | `sideMenuInset()` | |
| `--st-safe-x`, `--st-safe-y` | ללא שינוי (48, 27) | |

### חומר — `ui/common/Glass.kt`

| בקנבס | בקוד |
|---|---|
| `st-glass-thin`, `st-glass`, `st-glass-thick`, `st-panel` | `Modifier.glass(GlassMaterial.THIN / REGULAR / THICK)`; `panelSurface` (`Panel.kt`), `tvGlassPanel` (`TvSurfaces.kt`) |
| `--st-edge`, `st-rim` | `Modifier.litEdge` |
| `--st-depth-1..3` | `Depth.ONE..THREE`, `Modifier.depth` |
| מגירה | `Modifier.drawerSurface`, `tvDrawer` |
| `--st-blur`, `--st-blur-thick` | `rememberWindowBlurBehind`, `LocalGlassBlur` (ראו "טשטוש") |
| `st-live`, `st-live-dot` | `LiveBadge`, `LiveDot` (`ui/common/Components.kt`) |

### פוקוס — `ui/common/Focus.kt`

| בקנבס | בקוד |
|---|---|
| `st-lit` (פקד בפוקוס) | `focusSurface(fill = FocusFill.FILLED)` לכפתור, צ'יפ ומקש; `FocusFill.LIT` לשורת תפריט |
| אריח בפוקוס (`--st-card-focus` + `--st-ring`) | `FocusFill.TINT` (שורת תוכן, תא), `RAISE` (כרטיס), `NONE` (פוסטר) |
| נבחר (`--st-current-*`) | `selected = true` בכל אחד מהם; `filledContentColour` |
| תוכן על המשטח המואר | `LocalOnLitSurface` (מסופק על ידי `FocusableRow`) |
| `--st-ring` | `focusRingWidth` (2dp) |
| `--st-focus-scale`, `--st-focus-scale-row` | `focusScale` 1.06, `rowFocusScale` 1.03 |
| `--st-lift` | `focusLift` (צל 12dp בטלוויזיה, 6dp בטלפון; בלי ההילה הלבנה) |

### תנועה — `ui/common/Motion.kt`

| בקנבס | בקוד |
|---|---|
| `--st-dur-focus` (220) | `Motion.FAST`, ו-`Motion.FocusSpring` (קפיץ שמתייצב בכ-220ms עם חריגה קלה) |
| `--st-dur-panel` (360) | `Motion.ENTRANCE` |
| `--st-dur-exit` (200) | `Motion.EXIT` |
| `--st-ease`, `--st-ease-spring` | `Motion.Ease`, `Motion.EaseSpring` |
| `prefers-reduced-motion` | `LocalReducedMotion` (מקנה מידת האנימציה של המערכת = 0): בלי הגדלה ובלי עלייה, צבע ב-120ms |

## מיפוי רכיבים לקוד

| לוח | בקוד |
|---|---|
| `Button`, `PButton` | `ui/common/StudioButton.kt` |
| `Chip`, `PChip`, `Segmented`, `Field`, `PField`, `Toggle`, `PToggle`, `Notice`, `PNotice`, `EmptyState`, `PEmptyState` | `ui/common/StudioControls.kt` |
| `ListRow`, `PListRow` | `ui/common/ListRow.kt` (`ListRow`, `StatusPill`) על `FocusableRow` (`ui/common/Components.kt`) |
| `LiveBadge` | `LiveBadge` ו-`LiveDot` ב-`ui/common/Components.kt` |
| `Dialog`, `PDialog` | `StudioDialog` ב-`ui/common/Panel.kt` |
| `TvChannelMenu`, `PChannelMenu` (וכל הגיליונות) | `StudioSheet` ב-`ui/common/Panel.kt` |
| `SideMenu`, `TvShell`, `TopBar` | `ui/tv/SideMenu.kt` (`SideMenu`, `StudioTopBar`) |
| `ChannelCard` | `ui/tv/ChannelCard.kt` |
| `LowerThird`, `Live` | `ChannelInfoCard` ב-`ui/tv/TvRoot.kt` |
| `PlayerTransport` | `TransportBar` ב-`ui/tv/PlaybackControls.kt` |
| `PlayerMenu` | `PlaybackMenu` ב-`ui/tv/PlaybackControls.kt` |
| תפריטי ספורט וחדשות | `SportPanelMenu`, `SportPicker` ב-`ui/tv/SportScreen.kt` |
| `GuideCell` | `ui/screens/GuideScreen.kt` (דרך `focusSurface` ו-`cellCorner`) |
| `PTopBar`, `PNavBar`, `PMiniPlayer` | `ui/mobile/PhoneChrome.kt` |
| `PChannelRow` | `ui/screens/PhoneChannelRow.kt` |

## טשטוש

`RenderEffect` מטשטש רק את התוכן של השכבה עצמה, והווידאו הוא משטח נפרד ששום שכבה של האפליקציה לא רואה. לכן הטשטוש אמיתי רק במקום אחד: מאחורי חלון של דיאלוג (`StudioDialog`, `StudioSheet` שאינו מגירה), מ-Android 12, כשהמכשיר מאפשר טשטוש בין חלונות. שם הזכוכית שומרת על הגוון השקוף שלה. בכל מקום אחר פאנל מקבל את `--st-glass-fallback` (`rgba(22, 27, 40, 0.92)`) בשקיפות שהמשתמש בחר בהגדרות, בלי שינוי בפריסה. זכוכית דקה שומרת על הגוון שלה, כי היא יושבת על פאנל או על הקרקע ולא ישירות על השידור. מגירות לא מטושטשות אף פעם.

## מה מומש (0.20.0, ‏112)

שלב אחד: מערכת העיצוב והרכיבים המשותפים, כך שכל מסך מקבל את השפה דרכם.
- גופן, צבע, צורה, תנועה ומידות.
- חומרים, פוקוס, רכיבי בסיס, דיאלוגים וגיליונות.
- בטלוויזיה: תפריט הצד, השורה העליונה, כרטיס הערוץ, השליש התחתון, פס השליטה ותפריטי המגירה.
- בטלפון: השורה העליונה, הלשוניות, הנגן המוקטן ושורת הערוץ.

## מה עוד לא

- **המסכים עצמם.** כל מסך מקבל את השפה דרך הרכיבים, אבל מסך שמכפיל צעד תצוגה ביחס שנקבע ל-Karantina (למשל `title * 88/108`) מקבל עכשיו גודל יחסי לצעד של Noto. כל מסך יעבור בנפרד מול הלוח שלו בקנבס.
- **שורת רשימה בטלפון** נשארת שקופה במנוחה. בלוח `PListRow` היא זכוכית דקה עם רווח בין השורות, אבל רוב השורות האלה יושבות בתוך גיליון שכבר עשוי זכוכית.
- **שורת המחוון בהגדרות** נשארת שורת תוכן (זכוכית מוארת וטבעת), כי הידית שלה בצבע המואר.
- **ההילה הלבנה של `--st-lift`** לא מצוירת, וההילה האדומה של נורית החי היא קירוב (אין צל צבעוני לפני API 28).
- **השליש התחתון ופס השליטה** מצוירים מעל הווידאו, מחוץ ל-`LocalTvMetrics` של מסכי הדפדוף, ולכן הכפתורים שם לא גדלים בפוקוס. כך היה גם לפני השינוי.
- **סמל האפליקציה** עדיין מצויר מקווי המתאר של Karantina (`docs/design/studio/ICON.md`), ולכן `docs/licenses/Karantina-OFL.txt` נשאר.
- **גרסת הווב** (`web/`, ADR-044) לא השתנתה.
