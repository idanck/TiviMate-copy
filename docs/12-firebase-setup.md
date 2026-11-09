# 12 — הקמת פרויקט Firebase (שלב 0, ידני)

הצעדים שעידן מבצע בעצמו, לפי הסדר, לפני שהחשבונות עובדים (ADR-059). שום ערך מהצעדים האלה לא נכנס
לריפו. הכול נשמר ב-GitHub secrets: ‏Settings → Secrets and variables → Actions.

## 1. יצירת הפרויקט ומסלול Blaze

1. ‏console.firebase.google.com → Add project. בחר שם, למשל `shidur`. אין צורך ב-Google Analytics.
2. ‏Upgrade → Blaze. בלי Blaze אי אפשר לפרוס Cloud Functions.
3. התראת תקציב: ‏console.cloud.google.com → Billing → Budgets & alerts → Create budget.
   סכום חודשי נמוך, למשל 5$, עם התראות ב-50%, ב-90% וב-100%.

## 2. Authentication עם Google

1. ‏Build → Authentication → Get started → Sign-in method → Google → Enable.
2. בחר כתובת תמיכה (Support email) ושמור.

## 3. Firestore

1. ‏Build → Firestore Database → Create database → **Production mode**.
2. מיקום: ההמלצה היא `europe-west1`, אותו אזור שבו רצות הפונקציות. אפשר גם `me-west1` (תל אביב).
   **אי אפשר לשנות את המיקום אחר כך.**
3. אופציונלי: צור את המסמך `config/app` עם השדה `requireSignIn` מסוג boolean וערך `false`.
   מסמך חסר נחשב "כבוי", אבל כשהמסמך קיים קל יותר להדליק את המתג אחר כך.

## 4. אפליקציות אנדרואיד וטביעות האצבע

1. ‏Project settings → General → Add app → Android:
   - Package name: ‏`com.idanck.shidur`
   - SHA-1: של מפתח ה-release ש-CI חותם בו (הקובץ ש-`RELEASE_KEYSTORE_B64` מחזיק):
     ```
     keytool -list -v -keystore release.jks -alias <alias>
     ```
   - אחרי ההוספה: Add fingerprint → גם ה-SHA-256 של אותו מפתח.
2. אפליקציה שנייה: ‏`com.idanck.shidur.debug`, עם ה-SHA-1 של מפתח ה-debug בכל מחשב שבונה debug:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
3. אם האפליקציה תגיע פעם ל-Google Play: גם טביעת האצבע של Play App Signing
   (Play Console → Setup → App signing), על `com.idanck.shidur`.
4. ‏`com.tivicopy.player` ו-`com.tivicopy.player.debug`, שנרשמו קודם, נשארים: זו החבילה של הגשר
   (ADR-060). מוחקים אותם רק כשאין יותר אף התקנה של החבילה הישנה.
5. **רק אחרי שלב 2** הורד את `google-services.json`. הוא צריך להכיל `"client_type": 3`, כלומר את ה-web
   client ש-Sign in with Google צריך. אם הוא חסר, הורד את הקובץ מחדש.
6. ‏GitHub secret בשם **`FIREBASE_ANDROID_CONFIG`**: הדבק את כל תוכן הקובץ, כמו שהוא.

## 5. אפליקציית ווב ו-Hosting

1. ‏Project settings → Add app → Web. סמן *Also set up Firebase Hosting*.
2. אין צורך להעתיק את ה-config. דפי האישור קוראים אותו מ-Hosting עצמו (`/__/firebase/init.json`).
   שלב 5 (גרסת הווב) עשוי לבקש אותו כ-secret נפרד.

## 6. כתובות ההתחברות של ה-web client

ההתחברות בדפי האישור רצה על הדומיין של האתר עצמו, ולכן צריך לרשום אותו:

1. ‏console.cloud.google.com → APIs & Services → Credentials → *Web client (auto created by Google Service)*.
2. ‏Authorized JavaScript origins: הוסף `https://<project-id>.web.app`.
3. ‏Authorized redirect URIs: הוסף `https://<project-id>.web.app/__/auth/handler`.
4. אם תחבר דומיין משלך ל-Hosting: גם אותו, וגם ב-Authentication → Settings → Authorized domains.
   במקרה הזה צור את `firebase/functions/.env` (הוא לא נכנס ל-git) עם השורה
   `PAIR_BASE_URL=https://<הדומיין>`.

## 7. חשבון שירות ל-deploy מ-CI

1. ‏console.cloud.google.com → IAM & Admin → Service accounts → Create, למשל `github-deploy`.
2. תפקידים: *Firebase Admin*, *Cloud Functions Admin*, *Cloud Run Admin*, *Artifact Registry Administrator*,
   *Service Account User*, *Service Usage Admin*. האחרון נדרש כי ה-deploy הראשון מפעיל APIs.
3. ‏Keys → Add key → JSON. הקובץ יורד פעם אחת. **אל תשמור אותו בשום מקום אחר.**
4. ‏GitHub secrets: **`FIREBASE_SERVICE_ACCOUNT`** עם כל תוכן ה-JSON, ו-**`FIREBASE_PROJECT_ID`** עם ה-project ID.

## 8. Deploy ראשון

1. ‏GitHub → Actions → *Firebase* → Run workflow על `main`. הריצה מעלה את חוקי Firestore, את ה-TTL,
   את הפונקציות ואת דפי האישור.
2. אם השלב נכשל על הרשאה, היומן מציין איזה תפקיד חסר. הוסף אותו לחשבון השירות והרץ שוב.
3. בדיקה: ‏`https://<project-id>.web.app/pair` נפתח ומציג "התחברות עם Google".

## 9. הרשאה להנפקת custom token

הפונקציה `claimPairing` מנפיקה custom token לטלוויזיה, ולשם כך חשבון הריצה שלה צריך לחתום בשם עצמו:

1. ‏IAM & Admin → IAM. חשבון הריצה של הפונקציות הוא
   `<project-number>-compute@developer.gserviceaccount.com`.
2. ‏Grant access לאותו חשבון, עם התפקיד **Service Account Token Creator**.
3. ‏APIs & Services → Library → *IAM Service Account Credentials API* → Enable.

## 10. TTL

ה-deploy מגדיר TTL על `pairings.expiresAt` ועל `rateLimits.expiresAt`. בדוק ב-Firestore → TTL
ששתי המדיניות מופיעות. אם לא, הוסף אותן ידנית.

## 11. גרסת אנדרואיד עם חשבונות

ה-APK שנבנה לפני שה-secret `FIREBASE_ANDROID_CONFIG` קיים יוצא בלי חשבונות, ובהגדרות כתוב
"חשבונות לא זמינים בגרסה הזו". אחרי שלב 4 צריך build חדש ב-`main` עם `versionCode` גבוה יותר.

## 12. הדלקת חובת ההתחברות

1. בטלפון: הגדרות → חשבון → התחברות עם Google.
2. בטלוויזיה: הגדרות → חשבון. התחבר דרך הקוד והטלפון, ובאנדרואיד 14 ומעלה גם ישירות.
3. בדוק התנתקות והתחברות מחדש בשני המכשירים.
4. רק אז: Firestore → `config/app` → `requireSignIn` = `true`. המכשירים מקבלים את השינוי תוך שניות.
5. לכיבוי מיידי, למשל אם משהו נשבר אצל אבא: אותו שדה, `false`.

## 13. הרשאות למשתמש

אין עדיין תשלום. כדי לתת למשתמש הרשאה: Firestore → `users/<uid>` → `entitlements.web` או
`entitlements.premium` = `true`.
