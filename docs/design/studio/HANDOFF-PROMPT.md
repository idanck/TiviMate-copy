# הנחיה לפתיחת המשימה

להדביק כמו שהיא בהודעה הראשונה של משימת Cowork (או של סשן Claude Code):

---

אני רוצה להחליף את שפת העיצוב ואת השלד בקוד של האפליקציה בריפו idanck/TiviMate-copy (שם האפליקציה: שידור) בשפה החדשה "אולפן".

כל מה שצריך נמצא בענף `feature/studio-handoff`, בתיקייה `docs/design/studio/`:
- `MIGRATION.md`: ההחלטות, המיפוי מהקנבס לקוד והשלבים.
- `studio.css` ו-`studio-phone.css`: הטוקנים.
- `canvas/`: מקורות כל הלוחות. הקנבס החי: https://claude.ai/artifact/HGAeW2DuNx6d6ETaxito6C

גישה לריפו: דרך ה-Pi (Pi-Exec-MCP). העותק נמצא ב-`/home/idan/TiviMate-copy`. פעולות git מבצעים לפי הסקילים git-workflow ו-credentials-vault.

סדר העבודה:
1. לקרוא את `CLAUDE.md` בשורש הריפו ואחריו את `MIGRATION.md`. הכללים ב-`CLAUDE.md` גוברים.
2. לבצע את השלבים לפי הסדר ב-`MIGRATION.md`, כל שלב בענף feature משלו, מהענף `feature/studio-handoff`.
3. לפני כל שלב שנוגע באחד הנושאים בסעיף "נשאר פתוח", לשאול אותי ולחכות להחלטה.
4. בסוף כל שלב: לדחוף, לחכות ל-CI ירוק, ולעצור לאישור שלי לפני מיזוג ל-`main`, כי מיזוג מפרסם עדכון לכל המכשירים.
5. לכתוב ADR לכל החלטה שמשנה דרישה, לפי NFR-MAINT-05.
