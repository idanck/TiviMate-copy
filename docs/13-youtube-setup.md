# 13 — הקמת YouTube בפרויקט Firebase (ADR-061)

הכול בפרויקט `shidur-idanck`. שום ערך מכאן לא נכנס לריפו: המפתח וסודות ה-OAuth נשמרים
ב-Secret Manager, והפונקציות קוראות אותם בזמן ריצה.

## 1. ה-API ו-Secret Manager

```
gcloud services enable youtube.googleapis.com secretmanager.googleapis.com --project shidur-idanck
```

## 2. מפתח API

1. ‏APIs & Services → Credentials → Create credentials → API key.
2. ‏Edit → API restrictions → Restrict key → רק **YouTube Data API v3**. אין הגבלת אפליקציה: המפתח
   משמש רק את הפונקציות בשרת.
3. שמור אותו כסוד:

```
printf '%s' '<המפתח>' | gcloud secrets create YOUTUBE_API_KEY --data-file=- --project shidur-idanck
```

## 3. הסודות של ה-OAuth

ה-deploy נכשל כשסוד חסר, ולכן יוצרים את כולם מיד, עם `unset` עד שה-client קיים. ערך `unset` רק
מכבה את דרך החיבור ההיא.

```
for s in YOUTUBE_TV_CLIENT_ID YOUTUBE_TV_CLIENT_SECRET GOOGLE_WEB_CLIENT_ID GOOGLE_WEB_CLIENT_SECRET; do
  printf unset | gcloud secrets create $s --data-file=- --project shidur-idanck
done
```

## 4. הרשאות לסודות

1. חשבון הריצה של הפונקציות קורא את הסודות:

```
for s in YOUTUBE_API_KEY YOUTUBE_TV_CLIENT_ID YOUTUBE_TV_CLIENT_SECRET GOOGLE_WEB_CLIENT_ID GOOGLE_WEB_CLIENT_SECRET; do
  gcloud secrets add-iam-policy-binding $s --project shidur-idanck \
    --member serviceAccount:157879630053-compute@developer.gserviceaccount.com --role roles/secretmanager.secretAccessor
done
```

2. חשבון ה-deploy (`github-deploy@shidur-idanck.iam.gserviceaccount.com`) צריך לראות את הסודות
   ולתת עליהם הרשאה: התפקיד **Secret Manager Admin** בפרויקט.

## 5. מסך ההסכמה (OAuth consent screen)

1. ‏Google Auth Platform → Data access → Add or remove scopes → סמן
   `https://www.googleapis.com/auth/youtube.readonly` ו-`https://www.googleapis.com/auth/youtube.force-ssl`
   (הלייקים מלשונית המוזיקה, ADR-069) → Update → Save.
2. ‏Audience: כל עוד האפליקציה ב-**Testing**, רק החשבונות שברשימת **Test users** יכולים לחבר
   YouTube. הוסף את החשבונות שלך ושל מי שבודק.
3. לפתיחה לכולם: ‏Publish app ובקשת אימות (verification) מ-Google להרשאת YouTube. זה תהליך של
   Google, עם סרטון שמראה איך ההרשאה משמשת.

## 6. ‏Client לטלוויזיה

1. ‏Google Auth Platform → Clients → Create client → Application type: **TVs and Limited Input
   devices**, שם: `Shidur TV`.
2. ‏Client ID ו-Client secret נכנסים לסודות:

```
printf '%s' '<client id>' | gcloud secrets versions add YOUTUBE_TV_CLIENT_ID --data-file=- --project shidur-idanck
printf '%s' '<client secret>' | gcloud secrets versions add YOUTUBE_TV_CLIENT_SECRET --data-file=- --project shidur-idanck
```

## 7. ה-web client, בשביל הטלפון

הטלפון מקבל מ-Google קוד חד-פעמי עבור ה-web client של הפרויקט (זה שנוצר עם Firebase
Authentication, "Web client (auto created by Google Service)"), והפונקציה מחליפה אותו. מאותו
client: ‏Client ID ו-Client secret.

```
printf '%s' '<web client id>' | gcloud secrets versions add GOOGLE_WEB_CLIENT_ID --data-file=- --project shidur-idanck
printf '%s' '<web client secret>' | gcloud secrets versions add GOOGLE_WEB_CLIENT_SECRET --data-file=- --project shidur-idanck
```

## 8. אחרי שינוי סוד

הפונקציות קוראות את הגרסה האחרונה של סוד כשהן עולות. אחרי `versions add`: ‏GitHub → Actions →
*Firebase* → Run workflow על `main`, או המתן ל-deploy הבא.

## 9. מכסה

ברירת המחדל: ‏10,000 יחידות ביום לכל השיטות חוץ מחיפוש, ו-100 חיפושים ביום. היום מתאפס בחצות לפי
שעון האוקיינוס השקט. הצריכה של היום: ‏Firestore → `ytQuota/<YYYY-MM-DD>`. להגדלה: ‏APIs & Services
→ YouTube Data API v3 → Quotas, ובקשה דרך הטופס של YouTube (YouTube API Services audit).
