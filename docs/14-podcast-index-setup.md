# 14 — הגדרת Podcast Index

החיפוש והשורה "פודקאסטים בעברית" במסך "גילוי והוספה" מגיעים מ-Podcast Index (ADR-067), דרך שתי
פונקציות בענן: `pcSearch` ו-`pcTrending`. המפתח והסוד נשמרים ב-Secret Manager, והאפליקציה לא רואה
אותם. כל עוד הם לא מוגדרים, האפליקציה מציעה רק "הוספה מקישור".

## 1. מפתח מ-Podcast Index

נרשמים בחינם בכתובת <https://api.podcastindex.org/>. אחרי האישור במייל מקבלים **API Key** ו-**API
Secret**.

## 2. הסודות

לפני ה-deploy הראשון שכולל את הפונקציות האלה, שני הסודות חייבים להתקיים, אחרת ה-deploy נכשל. אם
עדיין אין מפתח, יוצרים אותם עם הערך `unset`:

```sh
for s in PODCASTINDEX_API_KEY PODCASTINDEX_API_SECRET; do
  printf unset | gcloud secrets create $s --data-file=- --project shidur-idanck
  gcloud secrets add-iam-policy-binding $s --project shidur-idanck \
    --member serviceAccount:157879630053-compute@developer.gserviceaccount.com --role roles/secretmanager.secretAccessor
done
```

כשיש מפתח, מוסיפים גרסה:

```sh
printf '%s' '<API Key>' | gcloud secrets versions add PODCASTINDEX_API_KEY --data-file=- --project shidur-idanck
printf '%s' '<API Secret>' | gcloud secrets versions add PODCASTINDEX_API_SECRET --data-file=- --project shidur-idanck
```

ואז מריצים שוב את **Firebase** ב-Actions על `main` (Run workflow), כדי שהפונקציות יקראו את הגרסה החדשה.

## 3. מה נשמר ולכמה זמן

- `pcCache/{hash}`: תשובה לחיפוש נשמרת יום, והשורה הפופולרית שש שעות. אותו חיפוש מכל משתמש לא עולה
  כלום עד אז. Firestore מוחק אותן לבד (TTL על `expiresAt`).
- מגבלות לכל חשבון או כתובת: 60 חיפושים בשעה, 120 קריאות לשורה בשעה (`PC_LIMITS` ב-`config.ts`).
