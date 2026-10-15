# מערכת העיצוב

> **הוחלף.** שפת העיצוב היא **אולפן**: `docs/design/studio/` (ראו `MIGRATION.md`
> ו-ADR-027). כל חמשת שלבי ההחלפה נחתו (גרסאות 0.11.0 עד 0.13.3). המסמך, הקנבס
> ו-`build_canvas.py` כאן מתארים את המערכת שלפני אולפן ונשארים לעיון היסטורי בלבד; אין
> לעדכן אותם, ואין להפיק מהם מחדש.

הקנבס: <https://claude.ai/code/artifact/e3301cab-e64d-45cd-9f33-364df385a5d8>

תשעה לוחות ב־Claude Design שמרכזים את מה שהיה עד עכשיו פזור בין `Theme.kt`,
`Type.kt`, `TvSurfaces.kt`, `Motion.kt`, `TvMetrics.kt`, `Components.kt`
ו־ADR‑019 עד ADR‑023.

**מקור האמת נשאר הקוד.** הקנבס מתאר אותו, לא מגדיר אותו. ערך שמשתנה שם הוא
הצעה עד שהוא נוחת ב־`app/src/main/java/com/tivicopy/player/ui/`.

## מה יש בו

| עמוד | לוח | מקור |
| --- | --- | --- |
| יסודות | צבע | `ui/theme/Theme.kt` |
| יסודות | טיפוגרפיה | `ui/theme/Type.kt`, ADR-019 |
| יסודות | צורה ומרווח | `ui/theme/Theme.kt`, `ui/tv/TvSurfaces.kt` |
| יסודות | תנועה | `ui/common/Motion.kt`, ADR-021, ADR-024 |
| רכיבים ומצבים | משטחים | `ui/tv/TvSurfaces.kt`, ADR-023 |
| רכיבים ומצבים | פוקוס | `ui/common/Focus.kt`, ADR-025 |
| רכיבים ומצבים | רכיבים | `ui/common/Components.kt` |
| רכיבים ומצבים | מידות | `ui/common/TvMetrics.kt` |
| רכיבים ומצבים | תפריטים | `ui/common/Panel.kt`, ADR-025 |

## הפקה מחדש

```sh
python3 docs/design/build_canvas.py
```

כותב את תשעת קובצי ה־`.dc.html` ואת `canvas.json`. משם הקנבס נזרע מחדש
ומתפרסם לאותו קישור. הפלט הזרוע עצמו (`shidur-design-system.html`, כ־2MB של
עורך ארוז) אינו נשמר בגיט — הוא נבנה מחדש מהקבצים האלה.
