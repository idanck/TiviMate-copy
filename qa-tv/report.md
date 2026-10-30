# Shidur QA — tv

Device: `{'model': 'AOSP TV on x86', 'sdk': '34', 'features_leanback': True, 'screen': 'Physical size: 1920x1080', 'density': 'Physical density: 320'}`

Counts: FAIL 5, PASS 22, WARN 2

| ID | Status | Scenario | Detail |
|---|---|---|---|
| QA-01 | PASS | Install the debug APK | Performing Streamed Install Success · APK 17841 KB · uid 10122 |
| QA-02 | WARN | Cold and warm start time | cold [4337, 3121, 2351] ms (budget 4000 on an emulator), warm 980 ms |
| QA-03 | PASS | First run: built-in Israeli channels and radio bootstrap | ערוצים פתוחים בישראל: 9 channels, ok=1 ; רדיו ישראלי: 98 channels, ok=1  |
| QA-04 | PASS | Every top-level destination opens | opened 7/7: ['Home', 'Live', 'Guide', 'Radio', 'DVR', 'Search', 'Settings']; missing [] |
| QA-26 | FAIL | Home: from the side menu, the arrow towards the content returns the remote to where it left from (ADR-035) | back from the menu: wanted i24NEWS, הערביסטים, got ערוץ 13, הישרדות - פרק הבכורה!; OK on the lit Home: wanted i24NEWS, הערביסטים, got ערוץ 13, הישרדות - פרק הבכורה! \|\| down:Home (menu) · menu_to_content:ערוץ 13, הישרדות - פרק ה · along:i24NEWS, הערביסטים · to_menu:ערוץ 13, הישרדות - פרק ה · to_menu:Radio (menu) · in_menu:Sport · back:ערוץ 13, הישרדות - פרק ה · to_menu_again:Radio (menu) · up:Guide (menu) · up:Live (menu) · up:Home (menu) · ok_on_home:ערוץ 13, הישרדות - פרק ה · next:i24NEWS, הערביסטים · after_ok:i24NEWS, הערביסטים |
| QA-05 | PASS | Bad playlist addresses fail cleanly | QA Malformed: saved=True alive=True row=[(3, 0, 'ParseFailed', 0)] \|\| QA Missing: saved=True alive=True row=[(4, 0, 'NotFound', 0)] \|\| QA Empty: saved=True alive=True row=[(5, 0, 'empty', 0)] |
| QA-07 | PASS | Add the mock Xtream account (live, VOD, series) | test ok shown=True; 12 live (expect 12), 8 VOD rows (expect 6 films + 2 series), refresh ok=1 ; password not stored in clear; xmltv.php requested 1 times in the 30+ s after Save; password in 0 channel URLs and not the guide address; an Xtream channel requested /live/qa/qa123/ |
| QA-06 | PASS | Add the mock M3U playlist with its XMLTV guide through the UI | fields typed ok=[True, True, True]; 30 channels (expect 30), 3 groups, refresh ok=1 , 0.2s; failover urls=[('["http:\\/\\/10.0.2.2:8080\\/hls\\/gone\\/live.m3u8","http:\\/\\/10.0.2.2:8080\\/hls\\/a\\/live.m3u8"]',)] |
| QA-06b | PASS | Guide loads by itself after a playlist is added | 1 guide requests and 3612 programmes in the 60+ s after Save, with no user action |
| QA-06c | PASS | Update guide now loads and matches the mock XMLTV | pressed=True; 3612 programmes, 30/30 channels matched in 0.4 s |
| QA-08 | PASS | Live playback starts from the shidur:// deep link (HLS, TS) | HLS: motion=0.126 audio=True; MPEG-TS: motion=0.098 audio=True |
| QA-09 | PASS | Zapping: channel up/down, number entry, last channel | CHANNEL_UP->QA Sport 02 · CHANNEL_DOWN->QA News 01 · digits 1 2->QA Kids 12 · LAST_CHANNEL->QA News 01 · DPAD_UP->QA Sport 02 |
| QA-10 | PASS | Broken stream, failover and slow start are handled | broken: alive=True motion=0.0 audio=False text@4s=['Connecting…', 'QA Broken 404'] text@18s=['QA Broken 404', 'The server has no stream at that address any more.', 'Up and down still change channel from here.', 'Network settings', 'Network settings', 'Channel list'] \|\| failover: alive=True motion=0.133 audio=True text@4s=['QA Failover'] text@18s=[] \|\| slow: alive=True motion=0.191 audio=True text@4s=['Connecting…', 'QA Slow Start'] text@18s=[] |
| QA-11 | PASS | D-pad: focus is always visible and never lost | no focused node after 0/18 keys; path: DPAD_DOWN:QA Slow Start, qa. DPAD_DOWN:QA News 01, qa.ch0 DPAD_DOWN:כאן 88 DPAD_DOWN:qa.ch01 show 26, Q DPAD_RIGHT:qa.ch02 show 26, Q DPAD_RIGHT:qa.ch03 show 26, Q DPAD_RIGHT:qa.ch04 show 26, Q DPAD_UP:רדיו תשעים DPAD_UP:QA Sport 02, qa.ch DPAD_LEFT:QA News 01, qa.ch0 DPAD_LEFT:Radio DPAD_LEFT:Radio DPAD_LEFT:Radio DPAD_CENTER:Recently watched DPAD_DOWN:Hidden channels DPAD_DOWN:National BACK:Watch fullscreen DPAD_UP:Watch fullscreen |
| QA-12 | PASS | Guide shows mock programmes; search finds a channel and a programme | guide cells seen: ['\u2068qa.slow show 25\u2069   \u206614:30 – 15:00\u2069  ·  \u2068On air · 6 min left\u2069  ·  \u2068On screen · QA Slow Start\u2069  ·  \u2068QA programme 25 on qa.slow\u2069', 'QA News 01', 'qa.ch01 show 24', 'qa.ch01 show 25', 'qa.ch01 show 26', 'qa.ch01 show 27', 'qa.ch01 show 28', 'qa.ch01 show 29']; search hits: ['QA Sport 05', 'QA Sport 05'] |
| QA-24 | FAIL | Back from a screen opened from the video returns to the video (ADR-036) | OK on the channel: wanted video, got Live; back: wanted Home, got video; back: wanted video, got Home \|\| deep link->video · arrow->Live · back->video · back->Live · back->Home · deep link->video · arrow->Live · OK on the channel->Live · back->Live · back->video · deep link->video · arrow->Live · back->video · yellow->Guide · back->video · back->Live · back->Home · deep link->video · transport bar's guide->Guide · back->Home · deep link->video · arrow->Live · menu guide->Guide · back->Home · deep link->video · arrow->Live · menu lit entry->Live · back->Home |
| QA-25 | FAIL | The guide opened from the video lands on what the playing channel is airing | focused: nothing; panel says on air: True |
| QA-27 | PASS | Settings by remote: a slider shows focus and moves with left and right; a text field brings the keyboard up only on OK | into0:Home · into1:Playlists and guide · into2:User agent · down:App default, TiviCopy/1.0 (And · down:VLC, VLC/3.0.20 LibVLC/3.0.20 · down:Kodi, Kodi/20.2 (Linux; Androi · down:Lavf, Lavf/60.16.100 · down:Android TV browser, Mozilla/5. · down:Smart TV, Mozilla/5.0 (SMART-T · down:MX Player, MXPlayer/1.0 · down:Timeouts and retries, 15s / 30 · raise:Timeouts and retries, 16s / 30 · lower:Timeouts and retries, 15s / 30 · to_field0:MX Player, MXPlayer/1.0 · to_field1:Smart TV, Mozilla/5.0 (SMART-T · to_field2:Android TV browser, Mozilla/5. · to_field3:Lavf, Lavf/60.16.100 · to_field4:Kodi, Kodi/ |
| QA-13 | PASS | Lifecycle: Home and back, Back to exit (sound stops), process death, rotation, PiP | Home: pip=False audio=False; return: motion=0.042 audio=True; Back x3: left the app=True audio=False; process death -> relaunch 1460 ms alive=True |
| QA-14 | PASS | Network drops for 20 s during playback and comes back | alive=True; on-screen while down: ['Connecting…', 'QA Kids 03']; picture back after 3.5 s (budget 45 s, no user action) |
| QA-15 | PASS | Malformed and hostile deep links are ignored | 7 links, crashes on: [] |
| QA-23 | PASS | A scheduled recording starts from its alarm with the app closed | process killed=True; alarm entries=1; 35 s after the start: process=True row=('RECORDING', 0, None) stream requests=0; after the end: row=('COMPLETED', 3146116, None) |
| QA-21 | PASS | A refresh that gets an HTML error page keeps the existing channels | before 30 channels; provider switched to an HTML 'Account suspended' page; Refresh now tapped=True; switchable.m3u requests=2; after: [(30, 0, 'ParseFailed')] sample=[('QA News 01', '["http:\\/\\/10.0.2.2:8080\\/hls\\/a\\/live.m3u8?ch=01"]'), ('QA News 04', '["http:\\/\\/10.0.2.2:8080\\/hls\\/a\\/live.m3u8?ch=04"]'), ('QA News 07', '["http:\\/\\/10.0.2.2:8080\\/hls\\/a\\/live.m3u8?ch=07"]')] |
| QA-16 | PASS | Hebrew UI is complete and right-to-left | 31 Hebrew labels; untranslated-looking labels: [] |
| QA-17 | PASS | Scale: a 5,000-channel playlist loads and scrolls | 5000 channels in 9.0 s; alive=True; {'channels': 5000, 'load_s': 9.0, 'jank': 'Janky frames: 6 (100.00%)', 'frames': '6', 'p90': '300'} |
| QA-22 | FAIL | Parental PIN: a locked channel and Settings wait for it | try 1: settings=True parental=True; PIN saved=True; settings switch tapped=True; qa.ch05 locked rows=1; deep link to the locked channel: prompt=True locked stream requests behind it=0; wrong PIN refused=True (field=no typed=- ok=yes; screen=['QA Sport 05', 'Enter your PIN', '4 to 6 digits', 'That PIN is not correct.', '1', '1', '2', '2']); right PIN typed (field=no typed=- ok=yes); right PIN: prompt closed=True locked stream requests=7 sound=True; Settings after leaving the app: PIN asked=True settings shown=False; Settings after the PIN: open=False |
| QA-18 | WARN | Monkey: 4,000 random events with a fixed seed | injected 78; crash=False anr=False (monkey itself aborted, no app crash)  |
| QA-19 | PASS | Memory and storage after the run | PSS 170 MB while playing (budget 350); java 61660 KB native 46868 KB; storage: 1884	databases 464	cache 3104	files |
| QA-20 | FAIL | Log audit: crashes, ANRs, error volume | FATAL=0 ANR=2 StrictMode=292; top error tags: [('AppOps', 636), ('ExoPlayerImplInternal', 270), ('Finsky', 249), ('katniss_search_LoadCastServiceInfoTaskImpl', 198), ('katniss_interactor_LoadCastServiceInfoTaskImpl', 198), ('Clearcut-EventLogger', 85), ('CellBroadcastUtils', 72), ('ActivityManager', 55), ('AudioMediaPlayerWrapper', 51), ('MediaControllerCompat', 50), ('EGL_emulation', 38), ('GlideExecutor', 36)] |

## Facts

```json
{
 "device": {
  "model": "AOSP TV on x86",
  "sdk": "34",
  "features_leanback": true,
  "screen": "Physical size: 1920x1080",
  "density": "Physical density: 320"
 },
 "durations": {
  "QA-01": 8.7,
  "QA-02": 31.7,
  "QA-03": 9.4,
  "QA-04": 78.6,
  "QA-26": 65.9,
  "QA-05": 276.8,
  "QA-07": 155.0,
  "QA-06": 189.7,
  "QA-08": 27.9,
  "QA-09": 36.6,
  "QA-10": 83.5,
  "QA-11": 62.5,
  "QA-12": 37.4,
  "QA-24": 170.5,
  "QA-25": 16.8,
  "QA-27": 90.5,
  "QA-13": 51.8,
  "QA-14": 44.2,
  "QA-15": 17.6,
  "QA-23": 190.7,
  "QA-21": 128.3,
  "QA-16": 37.7,
  "QA-17": 89.7,
  "QA-22": 205.6,
  "QA-18": 20.2,
  "QA-19": 20.1
 },
 "cold_start_ms": [
  4337,
  3121,
  2351
 ],
 "warm_start_ms": 980,
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
 "tv_rail": [
  "Home",
  "Live",
  "Guide",
  "Radio",
  "DVR",
  "Search",
  "Settings"
 ],
 "bad_playlists": [
  [
   3,
   0,
   "ParseFailed",
   0
  ],
  [
   4,
   0,
   "NotFound",
   0
  ],
  [
   5,
   0,
   "empty",
   0
  ]
 ],
 "xtream_playlist_id": 6,
 "xtream_password_at_rest": "password not stored in clear",
 "m3u_refresh_seconds": 0.2,
 "m3u_playlist_id": 7,
 "guide_manual": {
  "pressed": true,
  "programmes": 3612,
  "matched": 30,
  "secs": 0.4
 },
 "play_HLS": {
  "motion": 0.12590277777777775,
  "audio": true,
  "secs": 12.6
 },
 "play_MPEG-TS": {
  "motion": 0.0984375,
  "audio": true,
  "secs": 12.5
 },
 "process_restarts": [
  "QA-13",
  "QA-23",
  "QA-16",
  "QA-22"
 ],
 "scale": {
  "channels": 5000,
  "load_s": 9.0,
  "jank": "Janky frames: 6 (100.00%)",
  "frames": "6",
  "p90": "300"
 },
 "memory": {
  "pss_mb": 170,
  "java_kb": "61660",
  "native_kb": "46868",
  "storage": "1884\tdatabases\n464\tcache\n3104\tfiles"
 },
 "log_errors_by_tag": [
  [
   "AppOps",
   636
  ],
  [
   "ExoPlayerImplInternal",
   270
  ],
  [
   "Finsky",
   249
  ],
  [
   "katniss_search_LoadCastServiceInfoTaskImpl",
   198
  ],
  [
   "katniss_interactor_LoadCastServiceInfoTaskImpl",
   198
  ],
  [
   "Clearcut-EventLogger",
   85
  ],
  [
   "CellBroadcastUtils",
   72
  ],
  [
   "ActivityManager",
   55
  ],
  [
   "AudioMediaPlayerWrapper",
   51
  ],
  [
   "MediaControllerCompat",
   50
  ],
  [
   "EGL_emulation",
   38
  ],
  [
   "GlideExecutor",
   36
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
   ],
   [
    3,
    "QA Malformed",
    "M3U_URL",
    0,
    "ParseFailed",
    0
   ],
   [
    4,
    "QA Missing",
    "M3U_URL",
    0,
    "NotFound",
    0
   ],
   [
    5,
    "QA Empty",
    "M3U_URL",
    0,
    "empty",
    0
   ],
   [
    6,
    "QA Xtream",
    "XTREAM",
    1,
    null,
    12
   ],
   [
    7,
    "QA M3U",
    "M3U_URL",
    1,
    null,
    30
   ],
   [
    8,
    "QA Switch",
    "M3U_URL",
    0,
    "ParseFailed",
    30
   ],
   [
    9,
    "QA Big",
    "M3U_URL",
    1,
    null,
    5000
   ]
  ],
  "programmes": [
   [
    3696
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
