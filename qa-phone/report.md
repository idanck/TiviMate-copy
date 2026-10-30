# Shidur QA — phone

Device: `{'model': 'sdk_gphone64_x86_64', 'sdk': '34', 'features_leanback': False, 'screen': 'Physical size: 1080x2400', 'density': 'Physical density: 420'}`

Counts: FAIL 8, INFO 1, PASS 8, SKIP 7, WARN 3

| ID | Status | Scenario | Detail |
|---|---|---|---|
| QA-01 | PASS | Install the debug APK | Performing Streamed Install Success · APK 17841 KB · uid 10192 |
| QA-02 | WARN | Cold and warm start time | cold [4519, 3833, 4605] ms (budget 4000 on an emulator), warm 60 ms |
| QA-03 | PASS | First run: built-in Israeli channels and radio bootstrap | ערוצים פתוחים בישראל: 9 channels, ok=1 ; רדיו ישראלי: 98 channels, ok=1  |
| QA-04 | FAIL | Every top-level destination opens | opened 4/5: ['Live', 'Radio', 'Guide', 'VOD', 'Settings']; missing ['VOD'] |
| QA-26 | SKIP | Home: from the side menu, the arrow towards the content returns the remote to where it left from (ADR-035) | television only: the phone has no side menu |
| QA-05 | PASS | Bad playlist addresses fail cleanly | QA Malformed: saved=True alive=True row=[] \|\| QA Missing: saved=True alive=True row=[] \|\| QA Empty: saved=True alive=True row=[] |
| QA-07 | FAIL | Add the mock Xtream account (live, VOD, series) | test tapped=True, ok shown=False, no row |
| QA-06 | FAIL | Add the mock M3U playlist with its XMLTV guide through the UI | fields filled [False, False, False], saved=True, no playlist row |
| QA-08 | FAIL | Live playback starts from the shidur:// deep link (HLS, TS) | HLS: channel not in DB; MPEG-TS: channel not in DB |
| QA-09 | SKIP | Zapping: channel up/down, number entry, last channel | mock playlist missing |
| QA-10 | FAIL | Broken stream, failover and slow start are handled | broken: not in DB \|\| failover: not in DB \|\| slow: not in DB |
| QA-11 | WARN | D-pad: focus is always visible and never lost | no focused node after 1/18 keys; path: DPAD_DOWN:Back DPAD_DOWN:ערוצים פתוחים בישר DPAD_DOWN:רדיו ישראלי, Last  DPAD_DOWN:Add a playlist, Xt DPAD_RIGHT:Add a playlist, Xt DPAD_RIGHT:Add a playlist, Xt DPAD_RIGHT:Add a playlist, Xt DPAD_UP:רדיו ישראלי, Last  DPAD_UP:ערוצים פתוחים בישר DPAD_LEFT:ערוצים פתוחים בישר DPAD_LEFT:ערוצים פתוחים בישר DPAD_LEFT:ערוצים פתוחים בישר DPAD_LEFT:ערוצים פתוחים בישר DPAD_CENTER:∅ DPAD_DOWN:רדיו ישראלי, Last  DPAD_DOWN:Add a playlist, Xt BACK:Back DPAD_UP:Back |
| QA-12 | FAIL | Guide shows mock programmes; search finds a channel and a programme | guide cells seen: []; search hits: [] |
| QA-24 | SKIP | Back from a screen opened from the video returns to the video (ADR-036) | television only: the phone's MobileRoot handles Back itself |
| QA-25 | SKIP | The guide opened from the video lands on what the playing channel is airing | television only: the phone's guide is a tab of its own |
| QA-27 | SKIP | Settings by remote: a slider shows focus and moves with left and right; a text field brings the keyboard up only on OK | television only: on a phone a finger picks the field and drags the slider |
| QA-13 | PASS | Lifecycle: Home and back, Back to exit (sound stops), process death, rotation, PiP | process death -> relaunch 2472 ms alive=True; rotation x4 alive=True |
| QA-14 | SKIP | Network drops for 20 s during playback and comes back | mock playlist missing |
| QA-15 | PASS | Malformed and hostile deep links are ignored | 7 links, crashes on: [] |
| QA-23 | SKIP | A scheduled recording starts from its alarm with the app closed | mock playlist missing |
| QA-21 | WARN | A refresh that gets an HTML error page keeps the existing channels | could not set up: before 0 channels; provider switched to an HTML 'Account suspended' page; Refresh now tapped=True; switchable.m3u requests=0; after: [] sample=[] |
| QA-16 | PASS | Hebrew UI is complete and right-to-left | 48 Hebrew labels; untranslated-looking labels: [] |
| QA-17 | FAIL | Scale: a 5,000-channel playlist loads and scrolls | 0 channels in 121.9 s; alive=True; {'channels': 0, 'load_s': 121.9, 'jank': 'Janky frames: 119 (92.97%)', 'frames': '128', 'p90': '150'} |
| QA-22 | FAIL | Parental PIN: a locked channel and Settings wait for it | try 1: settings=True parental=True; PIN saved=False; settings switch tapped=True; qa.ch05 locked rows=0; deep link to the locked channel: prompt=False locked stream requests behind it=0; wrong PIN refused=False (field=yes typed=4 ok=no; screen=['Parental controls', 'Set a PIN', '••••', '4 to 6 digits', 'Save', 'Save', 'Require the PIN to open Settings', 'Require the PIN to open Settings']); right PIN typed (field=yes typed=4 ok=no); right PIN: prompt closed=True locked stream requests=0 sound=False; Settings after leaving the app: PIN asked=False settings shown=False; Settings after the PIN: o |
| QA-18 | PASS | Monkey: 4,000 random events with a fixed seed | injected 4000; crash=False anr=False  |
| QA-19 | PASS | Memory and storage after the run | PSS 130 MB while playing (budget 450); java 27384 KB native 40440 KB; storage: 852	databases 544	cache 16	files |
| QA-20 | INFO | Log audit: crashes, ANRs, error volume | FATAL=0 ANR=0 StrictMode=337; top error tags: [('RecoverySnapshotStorage', 85), ('system_server', 55), ('ExoPlayerImplInternal', 30), ('AmbientMusicServiceMana', 28), ('FrameTracker', 27), ('AppOps', 15), ('TaskPersister', 13), ('AiAiEcho', 13), ('WifiChipAidlImpl', 13), ('PackageManager', 12), ('WifiStaIfaceAidlImpl', 11), ('QuickAccessWalletKeyguardQuickAffordanceConfig', 9)] |

## Facts

```json
{
 "device": {
  "model": "sdk_gphone64_x86_64",
  "sdk": "34",
  "features_leanback": false,
  "screen": "Physical size: 1080x2400",
  "density": "Physical density: 420"
 },
 "durations": {
  "QA-01": 8.8,
  "QA-02": 34.2,
  "QA-03": 4.0,
  "QA-04": 44.3,
  "QA-26": 2.3,
  "QA-05": 189.7,
  "QA-07": 162.3,
  "QA-06": 173.1,
  "QA-08": 2.2,
  "QA-09": 2.2,
  "QA-10": 2.3,
  "QA-11": 52.5,
  "QA-12": 31.4,
  "QA-24": 2.1,
  "QA-25": 2.1,
  "QA-27": 2.1,
  "QA-13": 26.7,
  "QA-14": 2.4,
  "QA-15": 16.7,
  "QA-23": 2.1,
  "QA-21": 186.4,
  "QA-16": 29.4,
  "QA-17": 203.1,
  "QA-22": 236.5,
  "QA-18": 160.2,
  "QA-19": 3.3
 },
 "cold_start_ms": [
  4519,
  3833,
  4605
 ],
 "warm_start_ms": 60,
 "bootstrap_playlists": [
  [
   1,
   "ערוצים פתוחים בישראל",
   "https://il-epg.idanck.com/channels.m3u",
   1,
   null,
   9
  ],
  [
   2,
   "רדיו ישראלי",
   "https://il-epg.idanck.com/radio.m3u",
   1,
   null,
   98
  ]
 ],
 "typing_mismatch": [
  "Display name",
  "Playlist URL",
  "Display name",
  "Playlist URL",
  "Display name",
  "Playlist URL",
  "Display name",
  "Server URL",
  "Username",
  "Display name",
  "Playlist URL",
  "Guide (XMLTV) URL",
  "Display name",
  "Playlist URL",
  "Display name",
  "Playlist URL"
 ],
 "m3u_refresh_seconds": 93.6,
 "process_restarts": [
  "QA-13",
  "QA-16",
  "QA-22"
 ],
 "scale": {
  "channels": 0,
  "load_s": 121.9,
  "jank": "Janky frames: 119 (92.97%)",
  "frames": "128",
  "p90": "150"
 },
 "memory": {
  "pss_mb": 130,
  "java_kb": "27384",
  "native_kb": "40440",
  "storage": "852\tdatabases\n544\tcache\n16\tfiles"
 },
 "log_errors_by_tag": [
  [
   "RecoverySnapshotStorage",
   85
  ],
  [
   "system_server",
   55
  ],
  [
   "ExoPlayerImplInternal",
   30
  ],
  [
   "AmbientMusicServiceMana",
   28
  ],
  [
   "FrameTracker",
   27
  ],
  [
   "AppOps",
   15
  ],
  [
   "TaskPersister",
   13
  ],
  [
   "AiAiEcho",
   13
  ],
  [
   "WifiChipAidlImpl",
   13
  ],
  [
   "PackageManager",
   12
  ],
  [
   "WifiStaIfaceAidlImpl",
   11
  ],
  [
   "QuickAccessWalletKeyguardQuickAffordanceConfig",
   9
  ]
 ],
 "db_summary": {
  "playlists": [
   [
    1,
    "ערוצים פתוחים בישראל",
    "M3U_URL",
    1,
    null,
    9
   ],
   [
    2,
    "רדיו ישראלי",
    "M3U_URL",
    1,
    null,
    98
   ]
  ],
  "programmes": [
   [
    588
   ]
  ],
  "history": [
   [
    0
   ]
  ]
 }
}
```
