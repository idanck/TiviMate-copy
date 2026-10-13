#!/usr/bin/env python3
"""
Runs the Shidur QA scenarios (docs/qa/QA-PLAN.md) against an installed debug build on a booted
emulator, through adb only — the way a viewer would: taps, D-pad keys, the shidur:// deep link,
network loss, backgrounding, process death. Each scenario is independent: a failure is recorded
and the run moves on, so one broken step never hides the state of the rest.

Evidence lands in <out>: report.json, report.md, screenshots/, ui/ (hierarchy dumps), logcat.txt,
plus whatever the database said at the end (db_summary.json).

Usage: shidur_qa.py --form tv|phone --apk <path> --out <dir> [--mock http://10.0.2.2:8080]
"""
import argparse
import io
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

try:
    from PIL import Image, ImageChops, ImageStat  # optional: frame-difference checks
except ImportError:  # pragma: no cover
    Image = None

PKG = "com.tivicopy.player.debug"
ACTIVITY = "com.tivicopy.player.ui.MainActivity"


# ====================================================================== adb plumbing

def run(args, timeout=90, binary=False, check=False):
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {p.returncode}: {p.stderr.decode(errors='replace')[:400]}")
    return p.stdout if binary else p.stdout.decode("utf-8", errors="replace")


def adb(*args, **kw):
    return run(["adb", *args], **kw)


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def key(code, times=1, pause=0.35):
    for _ in range(times):
        sh(f"input keyevent {code}")
        time.sleep(pause)


def tap(x, y):
    sh(f"input tap {int(x)} {int(y)}")


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1920, 1080)


def swipe_up():
    w, h = screen_size()
    sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.30)} 300")
    time.sleep(0.8)


def pid():
    return sh(f"pidof {PKG}").strip()


def uid():
    m = re.search(r"uid:(\d+)", sh(f"cmd package list packages -U {PKG}"))
    if not m:
        m = re.search(r"(?:userId|appId)=(\d+)", sh(f"dumpsys package {PKG}"))
    return m.group(1) if m else None


def ime_shown():
    return "mInputShown=true" in sh("dumpsys input_method")


def hide_ime():
    if ime_shown():
        key("KEYCODE_BACK")
        time.sleep(0.5)


def type_text(text):
    # `input text` takes %s for a space and needs shell metacharacters escaped. Sent in short
    # chunks: one long injection raced the soft keyboard on the phone and arrived truncated
    # ("/malformed.m3u" reached the server as "/malforme").
    for i in range(0, len(text), 8):
        chunk = text[i:i + 8]
        esc = re.sub(r"([\\&|;<>()$`'\"?*~#!\[\]{}])", r"\\\1", chunk).replace(" ", "%s")
        sh(f"input text {esc}")
        time.sleep(0.3)
    time.sleep(0.6)


def clear_field():
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 80))
    time.sleep(0.3)


def in_foreground():
    """Whether the app holds the resumed activity (API 29+ prints topResumedActivity)."""
    out = sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'", timeout=30)
    return PKG in out


def launch(wait=True):
    out = sh(f"am start {'-W ' if wait else ''}-n {PKG}/{ACTIVITY}", timeout=60)
    m = re.search(r"TotalTime:\s*(\d+)", out) or re.search(r"WaitTime:\s*(\d+)", out)
    return int(m.group(1)) if m else None


def deep_link(uri):
    return sh(f"am start -W -a android.intent.action.VIEW -d '{uri}' {PKG}", timeout=60)


def force_stop():
    sh(f"am force-stop {PKG}")
    time.sleep(1)


# ====================================================================== UI hierarchy

class Node:
    def __init__(self, el):
        self.text = el.get("text") or ""
        self.desc = el.get("content-desc") or ""
        self.cls = el.get("class") or ""
        self.focused = el.get("focused") == "true"
        self.selected = el.get("selected") == "true"
        self.clickable = el.get("clickable") == "true"
        self.pkg = el.get("package") or ""
        m = re.findall(r"\d+", el.get("bounds") or "[0,0][0,0]")
        self.x1, self.y1, self.x2, self.y2 = map(int, m[:4])

    @property
    def center(self):
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    @property
    def label(self):
        return self.text or self.desc

    def __repr__(self):
        return f"<{self.cls.split('.')[-1]} '{self.label[:40]}' {self.center}{' F' if self.focused else ''}>"


class UI:
    def __init__(self, outdir):
        self.dir = os.path.join(outdir, "ui")
        os.makedirs(self.dir, exist_ok=True)
        self.n = 0
        self.last_xml = ""

    def dump(self, tag=None):
        for _ in range(3):
            out = sh("uiautomator dump --compressed /sdcard/qa_ui.xml", timeout=40)
            if "dumped" in out.lower():
                xml = adb("exec-out", "cat", "/sdcard/qa_ui.xml", timeout=30)
                if xml.strip().startswith("<?xml"):
                    self.last_xml = xml
                    if tag:
                        self.n += 1
                        with open(os.path.join(self.dir, f"{self.n:03d}_{tag}.xml"), "w") as f:
                            f.write(xml)
                    root = ET.fromstring(xml.encode("utf-8"))
                    return [Node(e) for e in root.iter("node")]
            time.sleep(1.5)
        return []

    def find(self, label, nodes=None, exact=False):
        nodes = self.dump() if nodes is None else nodes
        lo = label.lower()
        hits = [n for n in nodes if n.text == label or n.desc == label]
        if not hits and not exact:
            hits = [n for n in nodes if lo in n.text.lower() or lo in n.desc.lower()]
        # Prefer something on screen and of a real size.
        hits = [n for n in hits if n.x2 > n.x1 and n.y2 > n.y1]
        return hits[0] if hits else None

    def wait_for(self, label, timeout=20, exact=False):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(label, exact=exact)
            if n:
                return n
            time.sleep(1)
        return None

    def tap_label(self, label, scrolls=4, exact=False):
        for i in range(scrolls + 1):
            n = self.find(label, exact=exact)
            if n:
                tap(*n.center)
                time.sleep(1.2)
                return n
            if i < scrolls:
                swipe_up()
        return None

    def texts(self, nodes=None):
        nodes = self.dump() if nodes is None else nodes
        return [n.label for n in nodes if n.label]

    def focused(self, nodes=None):
        nodes = self.dump() if nodes is None else nodes
        return [n for n in nodes if n.focused]

    @staticmethod
    def label_within(node, nodes):
        """A focused Compose container usually carries no text itself; its children do."""
        if node.label:
            return node.label
        inside = [n.label for n in nodes if n.label and n.x1 >= node.x1 and n.x2 <= node.x2
                  and n.y1 >= node.y1 and n.y2 <= node.y2]
        return inside[0] if inside else ""


# ====================================================================== evidence

class Evidence:
    def __init__(self, outdir):
        self.dir = os.path.join(outdir, "screenshots")
        os.makedirs(self.dir, exist_ok=True)
        self.n = 0

    def shot(self, tag):
        self.n += 1
        name = f"{self.n:03d}_{tag}.png"
        data = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
        with open(os.path.join(self.dir, name), "wb") as f:
            f.write(data)
        return name

    def motion(self, tag, gap=3.0):
        """Fraction of the frame that changed between two captures `gap` seconds apart."""
        a = self.shot(f"{tag}_a")
        time.sleep(gap)
        b = self.shot(f"{tag}_b")
        if Image is None:
            return None, [a, b]
        try:
            ia = Image.open(os.path.join(self.dir, a)).convert("L").resize((320, 180))
            ib = Image.open(os.path.join(self.dir, b)).convert("L").resize((320, 180))
            diff = ImageChops.difference(ia, ib).point(lambda v: 255 if v > 24 else 0)
            return ImageStat.Stat(diff).mean[0] / 255.0, [a, b]
        except Exception:
            return None, [a, b]

    @staticmethod
    def mean_luma(path):
        if Image is None:
            return None
        return ImageStat.Stat(Image.open(path).convert("L")).mean[0]


def audio_playing(app_uid):
    """True when AudioService lists a started player owned by the app's uid."""
    if not app_uid:
        return None
    out = sh("dumpsys audio", timeout=30)
    for line in out.splitlines():
        if f"u/pid:{app_uid}/" in line and "state:started" in line:
            return True
    return False


def read_db(outdir):
    """Copies the app's SQLite files out with run-as (debug build) and opens them read-only."""
    tmp = tempfile.mkdtemp(dir=outdir)
    path = os.path.join(tmp, "tivicopy.db")
    for suffix in ("", "-wal", "-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/tivicopy.db{suffix}", binary=True, timeout=60)
        if data and not data.startswith(b"cat:") and not data.startswith(b"run-as:"):
            with open(path + suffix, "wb") as f:
                f.write(data)
    if not os.path.exists(path):
        return None
    return sqlite3.connect(path)


def q(db, sql, *args):
    try:
        return db.execute(sql, args).fetchall()
    except Exception as e:
        return [("ERR", str(e))]


# ====================================================================== runner

class Runner:
    def __init__(self, form, apk, out, mock):
        self.form, self.apk, self.out, self.mock = form, apk, out, mock.rstrip("/")
        os.makedirs(out, exist_ok=True)
        self.ui = UI(out)
        self.ev = Evidence(out)
        self.results = []
        self.facts = {}
        self.uid = None
        self.pids = []

    # ---------------------------------------------------------------- bookkeeping

    def record(self, sid, title, status, detail="", evidence=None, area=""):
        r = {"id": sid, "title": title, "area": area, "status": status, "detail": detail,
             "evidence": evidence or [], "form": self.form}
        self.results.append(r)
        print(f"[{self.form}] {sid} {status:5} {title} — {detail}", flush=True)

    def scenario(self, sid, title, area=""):
        def wrap(fn):
            started = time.time()
            before = pid()
            try:
                fn(sid, title, area)
            except Exception as e:
                self.record(sid, title, "FAIL", f"driver exception: {e!r}",
                            [self.ev.shot(f"{sid}_exception")], area)
                traceback.print_exc()
            crashes = self.crashes_since_mark()
            if crashes:
                self.record(f"{sid}-crash", f"{title}: app crashed", "FAIL",
                            "; ".join(crashes[:3]), [], area)
            now = pid()
            if before and now and before != now:
                self.facts.setdefault("process_restarts", []).append(sid)
            self.facts.setdefault("durations", {})[sid] = round(time.time() - started, 1)
            return fn
        return wrap

    def crashes_since_mark(self):
        out = adb("logcat", "-b", "crash", "-d", "-v", "brief", timeout=30)
        adb("logcat", "-b", "crash", "-c")
        found = []
        for block in out.split("FATAL EXCEPTION")[1:]:
            if PKG in block:
                head = block.strip().splitlines()[:4]
                found.append(" | ".join(h.strip() for h in head))
        return found

    def ensure_running(self):
        if not pid():
            launch()
            time.sleep(4)

    def home_of_app(self):
        """Brings the app to its top level: relaunch, then back out of any overlay."""
        launch()
        time.sleep(2)

    def go_settings(self):
        if self.form == "phone":
            # The full-screen player hides the bottom bar; Back folds it into the mini player.
            # A cold start takes ~10 s on the emulator and then opens the last channel full
            # screen, so a Back pressed blind lands on the splash and the player opens after it
            # (run 38413573319). Give the start a few looks before pressing Back on anything else.
            hide_ime()
            for attempt in range(6):
                nodes = self.ui.dump()
                n = self.ui.find("Settings", nodes, exact=True)
                player = self.ui.find("Minimise the player", nodes, exact=True)
                front = in_foreground()
                print(f"[{self.form}]   go_settings #{attempt}: {len(nodes)} nodes, settings={bool(n)} "
                      f"player={bool(player)} foreground={front}", flush=True)
                if n:
                    tap(*n.center)
                    time.sleep(1.2)
                    return True
                if not front:
                    launch()
                elif attempt >= 2 or player:
                    key("KEYCODE_BACK")
                time.sleep(2)
            return False
        # Television: Settings sits on the home rail; open the rail with Back/Left if needed.
        for attempt in range(4):
            n = self.ui.find("Settings", exact=True)
            if n:
                tap(*n.center)
                time.sleep(1.5)
                return True
            key("KEYCODE_BACK" if attempt % 2 == 0 else "KEYCODE_DPAD_LEFT")
            time.sleep(1)
        return False

    def db(self):
        return read_db(self.out)

    def edit_db(self, sql, args=()):
        """Changes the app's database (debug build): stop the app, edit a copy, put it back."""
        force_stop()
        tmp = tempfile.mkdtemp(dir=self.out)
        path = os.path.join(tmp, "tivicopy.db")
        for suffix in ("", "-wal", "-shm"):
            data = adb("exec-out", "run-as", PKG, "cat", f"databases/tivicopy.db{suffix}", binary=True, timeout=60)
            if data and not data.startswith(b"cat:") and not data.startswith(b"run-as:"):
                with open(path + suffix, "wb") as f:
                    f.write(data)
        db = sqlite3.connect(path)
        changed = db.execute(sql, args).rowcount
        db.commit()
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execute("PRAGMA journal_mode=DELETE")
        db.close()
        adb("push", path, "/data/local/tmp/qa_edit.db", timeout=60)
        sh("chmod 644 /data/local/tmp/qa_edit.db")
        sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/qa_edit.db > databases/tivicopy.db && "
           f"rm -f databases/tivicopy.db-wal databases/tivicopy.db-shm'")
        return changed

    # ---------------------------------------------------------------- scenarios

    def run_all(self):
        sh("settings put global window_animation_scale 0")
        sh("settings put global transition_animation_scale 0")
        sh("settings put global animator_duration_scale 0")
        sh("settings put system screen_off_timeout 1800000")
        sh("svc power stayon true")
        adb("logcat", "-c")
        adb("logcat", "-b", "crash", "-c")
        self.facts["device"] = {
            "model": sh("getprop ro.product.model").strip(),
            "sdk": sh("getprop ro.build.version.sdk").strip(),
            "features_leanback": "android.software.leanback" in sh("pm list features"),
            "screen": sh("wm size").strip(), "density": sh("wm density").strip(),
        }

        # Xtream before M3U, so the M3U playlist (with its guide loaded) is the active one for the
        # guide, zapping and search checks that follow.
        for fn in [self.s01_install, self.s02_cold_start, self.s03_bootstrap, self.s04_navigation,
                   self.s05_add_m3u_errors, self.s07_add_xtream, self.s06_add_m3u, self.s08_playback,
                   self.s09_zap, self.s10_failover_and_errors, self.s11_dpad_focus, self.s12_guide_search,
                   self.s13_lifecycle, self.s14_network_loss, self.s15_deep_link_fuzz, self.s21_suspended_refresh,
                   self.s16_locale, self.s17_scale, self.s18_monkey, self.s19_resources,
                   self.s22_parental]:
            fn()
        self.s20_audit()
        self.finish()

    def s01_install(self):
        @self.scenario("QA-01", "Install the debug APK", "install")
        def _(sid, title, area):
            sh(f"pm uninstall {PKG}")
            out = adb("install", "-r", "-g", self.apk, timeout=240)
            ok = "Success" in out
            self.uid = uid()
            size = os.path.getsize(self.apk) // 1024
            self.record(sid, title, "PASS" if ok else "FAIL", f"{out.strip()[-120:]} · APK {size} KB · uid {self.uid}",
                        area=area)

    def s02_cold_start(self):
        @self.scenario("QA-02", "Cold and warm start time", "performance")
        def _(sid, title, area):
            times = []
            for _ in range(3):
                force_stop()
                t = launch()
                if t:
                    times.append(t)
                time.sleep(3)
            sh("input keyevent KEYCODE_HOME")
            time.sleep(2)
            warm = launch()
            time.sleep(2)
            shot = self.ev.shot("first_screen")
            self.facts["cold_start_ms"], self.facts["warm_start_ms"] = times, warm
            worst = max(times) if times else None
            status = "PASS" if worst and worst < 4000 else ("WARN" if worst and worst < 8000 else "FAIL")
            self.record(sid, title, status, f"cold {times} ms (budget 4000 on an emulator), warm {warm} ms",
                        [shot], area)

    def s03_bootstrap(self):
        @self.scenario("QA-03", "First run: built-in Israeli channels and radio bootstrap", "sources")
        def _(sid, title, area):
            rows = []
            for _ in range(20):
                db = self.db()
                if db:
                    rows = q(db, "SELECT p.id, p.name, p.url, p.last_refresh_ok, p.last_refresh_message, "
                                 "(SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id) FROM playlists p")
                    if rows and all(r[5] for r in rows if r[0] != "ERR") and len(rows) >= 2:
                        break
                time.sleep(3)
            self.facts["bootstrap_playlists"] = rows
            shot = self.ev.shot("bootstrap")
            tv = [r for r in rows if "channels.m3u" in str(r[2])]
            radio = [r for r in rows if "radio.m3u" in str(r[2])]
            detail = "; ".join(f"{r[1]}: {r[5]} channels, ok={r[3]} {r[4] or ''}" for r in rows if r[0] != "ERR")
            if tv and radio and tv[0][5] and radio[0][5]:
                self.record(sid, title, "PASS", detail, [shot], area)
            elif tv or radio:
                self.record(sid, title, "WARN", f"bootstrapped but empty or failed: {detail}", [shot], area)
            else:
                self.record(sid, title, "FAIL", f"no built-in playlists after 60 s: {rows}", [shot], area)

    def s04_navigation(self):
        @self.scenario("QA-04", "Every top-level destination opens", "navigation")
        def _(sid, title, area):
            self.home_of_app()
            if self.form == "phone":
                dests = ["Live", "Radio", "Guide", "VOD", "Settings"]
            else:
                key("KEYCODE_BACK")
                nodes = self.ui.dump("tv_home")
                dests = [n.desc for n in nodes if n.desc in
                         ("Home", "Live", "Channels", "Radio", "Guide", "VOD", "DVR", "Search", "Settings")]
                dests = list(dict.fromkeys(dests)) or ["Guide", "Radio", "Settings"]
                self.facts["tv_rail"] = dests
            missing, shots = [], []
            for d in dests:
                if self.form == "tv":
                    self.home_of_app()
                    key("KEYCODE_BACK")
                n = self.ui.tap_label(d, scrolls=0, exact=True)
                time.sleep(2)
                if not n:
                    missing.append(d)
                    continue
                shots.append(self.ev.shot(f"nav_{d.lower()}"))
                self.ui.dump(f"nav_{d.lower()}")
                if not pid():
                    missing.append(f"{d} (app died)")
                    self.ensure_running()
            self.record(sid, title, "PASS" if not missing else "FAIL",
                        f"opened {len(dests) - len(missing)}/{len(dests)}: {dests}; missing {missing}", shots, area)

    def open_add_source(self):
        """Settings > Playlists and guide > Add a playlist, from wherever the app was left.

        After a Save the app returns to the Playlists screen inside Settings, and a relaunch
        resumes there, so the first thing to look for is the row itself.
        """
        for attempt in range(3):
            self.home_of_app()
            if self.ui.tap_label("Add a playlist", scrolls=0, exact=True):
                return True
            if self.ui.find("Playlists and guide", exact=True) or self.go_settings():
                if self.ui.tap_label("Playlists and guide", scrolls=2, exact=True) and \
                        self.ui.tap_label("Add a playlist", scrolls=3, exact=True):
                    return True
            if self.ui.tap_label("Add a playlist", scrolls=2, exact=True):
                return True
            key("KEYCODE_BACK", times=2, pause=0.8)
        return False

    def open_sources(self):
        """Settings > Playlists and guide, where "Refresh now" and "Update guide now" live."""
        for attempt in range(3):
            if self.ui.find("Refresh now", exact=True) or self.ui.find("Update guide now", exact=True):
                return True
            self.home_of_app()
            if self.ui.find("Refresh now", exact=True):
                return True
            if (self.ui.find("Playlists and guide", exact=True) or self.go_settings()) and \
                    self.ui.tap_label("Playlists and guide", scrolls=2, exact=True):
                return True
            key("KEYCODE_BACK", times=2, pause=0.8)
        return False

    def fill(self, label, value, secret=False):
        for attempt in range(2):
            n = self.ui.tap_label(label, scrolls=3)
            if not n:
                return False
            clear_field()
            type_text(value)
            hide_ime()
            if secret:
                return True
            nodes = self.ui.dump()
            if any(value in x.text for x in nodes if "EditText" in x.cls):
                return True
        self.facts.setdefault("typing_mismatch", []).append(label)
        return False

    def mock_requests(self, needle):
        path = os.path.join(self.out, "mock", "requests.jsonl")
        try:
            with open(path) as f:
                return [json.loads(l) for l in f if needle in l]
        except OSError:
            return []

    def mock_admin(self, action):
        import urllib.request
        port = self.mock.rsplit(":", 1)[-1]
        try:
            return urllib.request.urlopen(f"http://127.0.0.1:{port}/admin/{action}", timeout=5).read().decode()
        except Exception as e:
            return repr(e)

    def s05_add_m3u_errors(self):
        @self.scenario("QA-05", "Bad playlist addresses fail cleanly", "sources")
        def _(sid, title, area):
            outcomes = []
            for name, path in (("QA Malformed", "/malformed.m3u"), ("QA Missing", "/nope.m3u"),
                               ("QA Empty", "/empty.m3u")):
                if not self.open_add_source():
                    outcomes.append(f"{name}: could not reach Add a playlist")
                    continue
                self.ui.tap_label("M3U / M3U8 playlist", scrolls=1, exact=True)
                self.fill("Display name", name)
                self.fill("Playlist URL", self.mock + path)
                saved = self.ui.tap_label("Save", scrolls=3, exact=True)
                time.sleep(6)
                alive = bool(pid())
                db = self.db()
                row = q(db, "SELECT id, last_refresh_ok, last_refresh_message, "
                            "(SELECT COUNT(*) FROM channels c WHERE c.playlist_id=playlists.id) "
                            "FROM playlists WHERE name=?", name) if db else []
                outcomes.append(f"{name}: saved={bool(saved)} alive={alive} row={row}")
                self.ev.shot(f"bad_{path.strip('/').split('.')[0]}")
                # Leave nothing behind for the rest of the run.
                if row and row[0][0] != "ERR":
                    self.facts.setdefault("bad_playlists", []).append(row[0])
            bad = [o for o in outcomes if "alive=False" in o or "could not" in o]
            silent = [o for o in outcomes if ", 1, None" in o or ", 1, '', " in o]
            status = "FAIL" if bad else ("WARN" if silent else "PASS")
            self.record(sid, title, status,
                        " || ".join(outcomes) + (" — a failed refresh recorded as OK" if silent else ""), [], area)

    def s06_add_m3u(self):
        @self.scenario("QA-06", "Add the mock M3U playlist with its XMLTV guide through the UI", "sources")
        def _(sid, title, area):
            if not self.open_add_source():
                self.record(sid, title, "FAIL", "could not reach Settings > Playlists and guide > Add a playlist",
                            [self.ev.shot("add_source_unreachable")], area)
                return
            self.ui.dump("add_source_form")
            self.ui.tap_label("M3U / M3U8 playlist", scrolls=1, exact=True)
            ok = [self.fill("Display name", "QA M3U"),
                  self.fill("Playlist URL", f"{self.mock}/playlist.m3u"),
                  self.fill("Guide (XMLTV) URL", f"{self.mock}/guide.xml.gz")]
            shot = self.ev.shot("add_m3u_filled")
            saved = self.ui.tap_label("Save", scrolls=3, exact=True)
            t0 = time.time()
            rows = []
            while time.time() - t0 < 90:
                db = self.db()
                if db:
                    rows = q(db, "SELECT id, (SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id), "
                                 "(SELECT COUNT(*) FROM channel_groups g WHERE g.playlist_id=p.id), last_refresh_ok, "
                                 "last_refresh_message FROM playlists p WHERE name='QA M3U'")
                    if rows and rows[0][0] != "ERR" and rows[0][1]:
                        break
                time.sleep(4)
            elapsed = round(time.time() - t0, 1)
            self.facts["m3u_refresh_seconds"] = elapsed
            after = self.ev.shot("after_add_m3u")
            if not rows or rows[0][0] == "ERR":
                self.record(sid, title, "FAIL", f"fields filled {ok}, saved={bool(saved)}, no playlist row",
                            [shot, after], area)
                return
            pid_, count, groups, ok_flag, msg = rows[0]
            self.facts["m3u_playlist_id"] = pid_
            db = self.db()
            failover = q(db, "SELECT urls FROM channels WHERE playlist_id=? AND tvg_id='qa.failover'", pid_)
            expected_channels = 30  # 31 entries, the failover pair folds into one
            detail = (f"fields typed ok={ok}; {count} channels (expect {expected_channels}), {groups} groups, "
                      f"refresh ok={ok_flag} {msg or ''}, {elapsed}s; failover urls={failover}")
            status = "PASS" if count == expected_channels and groups >= 3 else "FAIL"
            self.record(sid, title, status, detail, [shot, after], area)

            # QA-06b: does the guide arrive on its own? A viewer who just added a playlist opens
            # the guide next; nothing in the UI tells them to press "Update guide now" first.
            sid_b, title_b = "QA-06b", "Guide loads by itself after a playlist is added"
            time.sleep(60)
            auto = self.mock_requests("/guide.xml")
            db = self.db()
            prog_auto = q(db, "SELECT COUNT(*) FROM programmes")[0][0] if db else None
            self.record(sid_b, title_b, "PASS" if auto and prog_auto else "FAIL",
                        f"{len(auto)} guide requests and {prog_auto} programmes in the 60+ s after Save, "
                        f"with no user action", [self.ev.shot("guide_not_loaded")], area)

            # QA-06c: the guide pipeline itself, triggered by hand.
            sid_c, title_c = "QA-06c", "Update guide now loads and matches the mock XMLTV"
            self.open_sources()
            pressed = self.ui.tap_label("Update guide now", scrolls=3, exact=True)
            prog, matched = 0, 0
            t1 = time.time()
            while time.time() - t1 < 120:
                db = self.db()
                if db:
                    prog = q(db, "SELECT COUNT(*) FROM programmes")[0][0]
                    matched = q(db, "SELECT COUNT(*) FROM channels WHERE playlist_id=? AND resolved_epg_id IS NOT NULL",
                                pid_)[0][0]
                    if prog and matched:
                        break
                time.sleep(4)
            secs = round(time.time() - t1, 1)
            self.facts["guide_manual"] = {"pressed": bool(pressed), "programmes": prog, "matched": matched, "secs": secs}
            self.record(sid_c, title_c, "PASS" if matched >= 28 and prog else "FAIL",
                        f"pressed={bool(pressed)}; {prog} programmes, {matched}/30 channels matched in {secs} s",
                        [self.ev.shot("guide_after_update")], area)

    def s07_add_xtream(self):
        @self.scenario("QA-07", "Add the mock Xtream account (live, VOD, series)", "sources")
        def _(sid, title, area):
            if not self.open_add_source():
                self.record(sid, title, "FAIL", "could not reach Add a playlist", [], area)
                return
            self.ui.tap_label("Xtream Codes account", scrolls=1, exact=True)
            self.fill("Display name", "QA Xtream")
            self.fill("Server URL", self.mock)
            self.fill("Username", "qa")
            self.fill("Password", "qa123", secret=True)
            self.ui.tap_label("HLS (better on unstable networks)", scrolls=2, exact=True)
            tested = self.ui.tap_label("Test connection", scrolls=3, exact=True)
            ok_msg = self.ui.wait_for("Connection succeeded", timeout=15)
            shot = self.ev.shot("xtream_test")
            self.ui.tap_label("Save", scrolls=3, exact=True)
            rows = []
            for _ in range(20):
                db = self.db()
                if db:
                    rows = q(db, "SELECT id, (SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id), "
                                 "(SELECT COUNT(*) FROM vod v WHERE v.playlist_id=p.id), last_refresh_ok, "
                                 "last_refresh_message, password FROM playlists p WHERE name='QA Xtream'")
                    if rows and rows[0][1] and rows[0][2]:
                        break
                time.sleep(3)
            if not rows:
                self.record(sid, title, "FAIL", f"test tapped={bool(tested)}, ok shown={bool(ok_msg)}, no row",
                            [shot], area)
                return
            pid_, live, vod, ok_flag, msg, stored_pw = rows[0]
            self.facts["xtream_playlist_id"] = pid_
            pw_note = "password stored in clear in SQLite" if stored_pw == "qa123" else "password not stored in clear"
            self.facts["xtream_password_at_rest"] = pw_note
            status = "PASS" if ok_msg and live == 12 and vod >= 8 else ("WARN" if live else "FAIL")
            time.sleep(30)
            xmltv = self.mock_requests("/xmltv.php")
            self.record(sid, title, status,
                        f"test ok shown={bool(ok_msg)}; {live} live (expect 12), {vod} VOD rows (expect 6 films + 2 "
                        f"series), refresh ok={ok_flag} {msg or ''}; {pw_note}; xmltv.php requested "
                        f"{len(xmltv)} times in the 30+ s after Save", [shot], area)

    def channel_id(self, tvg, playlist_key="m3u_playlist_id"):
        db = self.db()
        pl = self.facts.get(playlist_key)
        if not db or not pl:
            return None
        r = q(db, "SELECT id FROM channels WHERE playlist_id=? AND tvg_id=?", pl, tvg)
        return r[0][0] if r and r[0][0] != "ERR" else None

    def check_playing(self, tag, wait=8):
        time.sleep(wait)
        frac, shots = self.ev.motion(tag)
        audio = audio_playing(self.uid)
        return frac, audio, shots

    def s08_playback(self):
        @self.scenario("QA-08", "Live playback starts from the shidur:// deep link (HLS, TS)", "playback")
        def _(sid, title, area):
            results, shots = [], []
            for tvg, label in (("qa.ch01", "HLS"), ("qa.ts", "MPEG-TS")):
                cid = self.channel_id(tvg)
                if not cid:
                    results.append(f"{label}: channel not in DB")
                    continue
                t0 = time.time()
                deep_link(f"shidur://channel/{cid}")
                frac, audio, s = self.check_playing(f"play_{label.lower()}")
                shots += s
                results.append(f"{label}: motion={frac if frac is None else round(frac, 3)} audio={audio}")
                self.facts[f"play_{label}"] = {"motion": frac, "audio": audio, "secs": round(time.time() - t0, 1)}
            moving = all("not in DB" not in r and (("audio=True" in r) or
                         (re.search(r"motion=(\d\.\d+)", r) and float(re.search(r"motion=(\d\.\d+)", r).group(1)) > 0.01))
                         for r in results)
            self.record(sid, title, "PASS" if moving else "FAIL", "; ".join(results), shots, area)

    def s09_zap(self):
        @self.scenario("QA-09", "Zapping: channel up/down, number entry, last channel", "playback")
        def _(sid, title, area):
            cid = self.channel_id("qa.ch01")
            if not cid:
                self.record(sid, title, "SKIP", "mock playlist missing", [], area)
                return
            deep_link(f"shidur://channel/{cid}")
            time.sleep(6)
            steps = []
            def last_watched():
                d = self.db()
                r = q(d, "SELECT name FROM channels WHERE playlist_id=? ORDER BY last_watched_utc DESC LIMIT 1",
                      self.facts.get("m3u_playlist_id"))
                return r[0][0] if r else None
            for label, keys in (("CHANNEL_UP", ["KEYCODE_CHANNEL_UP"]), ("CHANNEL_DOWN", ["KEYCODE_CHANNEL_DOWN"]),
                                ("digits 1 2", ["KEYCODE_1", "KEYCODE_2"]),
                                ("LAST_CHANNEL", ["KEYCODE_LAST_CHANNEL"])):
                for k in keys:
                    key(k, pause=0.3)
                time.sleep(5)
                steps.append(f"{label}->{last_watched()}")
            if self.form == "tv":
                key("KEYCODE_DPAD_UP")
                time.sleep(4)
                steps.append(f"DPAD_UP->{last_watched()}")
            shot = self.ev.shot("after_zap")
            distinct = len({s.split("->")[1] for s in steps})
            status = "PASS" if distinct >= 3 else ("WARN" if distinct == 2 else "FAIL")
            self.record(sid, title, status, " · ".join(steps), [shot], area)

    def s10_failover_and_errors(self):
        @self.scenario("QA-10", "Broken stream, failover and slow start are handled", "playback")
        def _(sid, title, area):
            parts, shots = [], []
            for tvg, tag in (("qa.broken", "broken"), ("qa.failover", "failover"), ("qa.slow", "slow")):
                cid = self.channel_id(tvg)
                if not cid:
                    parts.append(f"{tag}: not in DB")
                    continue
                deep_link(f"shidur://channel/{cid}")
                time.sleep(4)
                early = self.ev.shot(f"{tag}_early")
                texts = self.ui.texts()
                frac, audio, s = self.check_playing(tag, wait=14)
                shots += [early] + s
                late_texts = self.ui.texts()
                parts.append(f"{tag}: alive={bool(pid())} motion={frac if frac is None else round(frac, 3)} "
                             f"audio={audio} text@4s={[t for t in texts if len(t) > 3][:6]} "
                             f"text@18s={[t for t in late_texts if len(t) > 3][:6]}")
            broken_ok = "broken: alive=True" in " ".join(parts)
            fo = next((p for p in parts if p.startswith("failover")), "")
            failover_ok = "audio=True" in fo or bool(re.search(r"motion=0\.(0[2-9]|[1-9])", fo))
            status = "PASS" if broken_ok and failover_ok else "FAIL"
            self.record(sid, title, status, " || ".join(parts), shots, area)

    def s11_dpad_focus(self):
        @self.scenario("QA-11", "D-pad: focus is always visible and never lost", "navigation")
        def _(sid, title, area):
            if self.form != "tv":
                # Phones get a keyboard/D-pad too (Bluetooth remotes, Chromebooks): still checked.
                pass
            self.home_of_app()
            key("KEYCODE_BACK")
            lost, path = 0, []
            seq = (["KEYCODE_DPAD_DOWN"] * 4 + ["KEYCODE_DPAD_RIGHT"] * 3 + ["KEYCODE_DPAD_UP"] * 2 +
                   ["KEYCODE_DPAD_LEFT"] * 4 + ["KEYCODE_DPAD_CENTER", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN",
                                               "KEYCODE_BACK", "KEYCODE_DPAD_UP"])
            for i, k in enumerate(seq):
                key(k, pause=0.6)
                nodes = self.ui.dump(f"focus_{i:02d}" if i % 4 == 0 else None)
                f = [n for n in nodes if n.focused]
                if not f:
                    lost += 1
                lab = UI.label_within(f[0], nodes)[:18] if f else "∅"
                path.append(f"{k.replace('KEYCODE_', '')}:{lab or '?'}")
            shot = self.ev.shot("focus_end")
            status = "PASS" if lost == 0 else ("WARN" if lost <= 2 else "FAIL")
            if self.form == "phone" and lost:
                status = "WARN"
            self.record(sid, title, status, f"no focused node after {lost}/{len(seq)} keys; path: {' '.join(path)}",
                        [shot], area)

    def s12_guide_search(self):
        @self.scenario("QA-12", "Guide shows mock programmes; search finds a channel and a programme", "guide")
        def _(sid, title, area):
            self.home_of_app()
            if self.form == "tv":
                key("KEYCODE_BACK")
            self.ui.tap_label("Guide", scrolls=0, exact=True)
            time.sleep(4)
            nodes = self.ui.dump("guide")
            guide_hits = [n.label for n in nodes if "show" in n.label or "QA " in n.label][:8]
            g_shot = self.ev.shot("guide")
            self.home_of_app()
            if self.form == "tv":
                key("KEYCODE_BACK")
                self.ui.tap_label("Search", scrolls=0, exact=True)
            else:
                n = self.ui.find("Search", exact=True)
                if n:
                    tap(*n.center)
            time.sleep(2)
            fields = [n for n in self.ui.dump() if "EditText" in n.cls]
            if fields:
                tap(*fields[0].center)
            type_text("Sport 05")
            hide_ime()
            time.sleep(3)
            s_nodes = self.ui.dump("search")
            # The query itself sits in the search field; only results count.
            s_hits = [n.label for n in s_nodes if "Sport 05" in n.label and "EditText" not in n.cls]
            s_shot = self.ev.shot("search")
            status = "PASS" if guide_hits and s_hits else ("WARN" if guide_hits or s_hits else "FAIL")
            self.record(sid, title, status, f"guide cells seen: {guide_hits}; search hits: {s_hits}",
                        [g_shot, s_shot], area)

    def s13_lifecycle(self):
        @self.scenario("QA-13", "Lifecycle: Home and back, Back to exit (sound stops), process death, rotation, PiP",
                       "lifecycle")
        def _(sid, title, area):
            cid = self.channel_id("qa.ch02")
            notes, shots, fail = [], [], False
            if cid:
                deep_link(f"shidur://channel/{cid}")
                time.sleep(8)
                # Home: the app should not keep playing sound behind the launcher unless in PiP.
                key("KEYCODE_HOME")
                time.sleep(4)
                pip = "pinned" in sh("dumpsys activity activities", timeout=30).lower() and PKG in sh(
                    "dumpsys activity activities | grep -i -A3 pinned", timeout=30)
                audio_home = audio_playing(self.uid)
                shots.append(self.ev.shot("after_home"))
                notes.append(f"Home: pip={pip} audio={audio_home}")
                if audio_home and not pip:
                    fail = True
                    notes.append("SOUND CONTINUES behind the launcher without PiP")
                launch()
                time.sleep(6)
                frac, audio, s = self.check_playing("resume_after_home", wait=2)
                shots += s
                notes.append(f"return: motion={frac if frac is None else round(frac, 3)} audio={audio}")
                # Back out of the app entirely: Back climbs the screens, then asks for a second
                # press to exit, so press until the app has really left the foreground.
                presses = 0
                while in_foreground() and presses < 8:
                    key("KEYCODE_BACK", pause=0.6)
                    presses += 1
                time.sleep(2)
                left = not in_foreground()
                audio_back = audio_playing(self.uid)
                notes.append(f"Back x{presses}: left the app={left} audio={audio_back}")
                if left and audio_back and "pinned" not in sh("dumpsys activity activities | grep -i pinned"):
                    fail = True
                    notes.append("SOUND CONTINUES after backing out")
            # Process death while in the background, then return.
            launch()
            time.sleep(3)
            key("KEYCODE_HOME")
            time.sleep(2)
            sh(f"am kill {PKG}")
            time.sleep(2)
            t = launch()
            time.sleep(4)
            alive = bool(pid())
            notes.append(f"process death -> relaunch {t} ms alive={alive}")
            fail |= not alive
            if self.form == "phone":
                sh("settings put system accelerometer_rotation 0")
                for rot in (1, 0, 1, 0):
                    sh(f"settings put system user_rotation {rot}")
                    time.sleep(2.5)
                    if rot == 1:
                        shots.append(self.ev.shot("landscape"))
                notes.append(f"rotation x4 alive={bool(pid())}")
                fail |= not pid()
            self.record(sid, title, "FAIL" if fail else "PASS", "; ".join(notes), shots, area)

    def s14_network_loss(self):
        @self.scenario("QA-14", "Network drops for 20 s during playback and comes back", "resilience")
        def _(sid, title, area):
            cid = self.channel_id("qa.ch03")
            if not cid:
                self.record(sid, title, "SKIP", "mock playlist missing", [], area)
                return
            deep_link(f"shidur://channel/{cid}")
            time.sleep(8)
            # The TV emulator is on Ethernet, which `svc wifi` does not touch (the first runs
            # "passed" this without ever losing a packet). The mock provider dropping every
            # connection for the same 20 s reaches both devices the same way.
            self.mock_admin("outage?secs=20")
            sh("svc wifi disable")
            sh("svc data disable")
            time.sleep(20)
            during = self.ev.shot("net_down")
            during_text = [t for t in self.ui.texts() if len(t) > 3][:8]
            sh("svc wifi enable")
            sh("svc data enable")
            recovered_at = None
            t0 = time.time()
            while time.time() - t0 < 45:
                frac, shots = self.ev.motion("net_up_probe", gap=2)
                if (frac and frac > 0.01) or audio_playing(self.uid):
                    recovered_at = round(time.time() - t0, 1)
                    break
                time.sleep(2)
            after = self.ev.shot("net_up")
            alive = bool(pid())
            status = "PASS" if alive and recovered_at is not None else ("WARN" if alive else "FAIL")
            self.record(sid, title, status,
                        f"alive={alive}; on-screen while down: {during_text}; picture back after {recovered_at} s "
                        f"(budget 45 s, no user action)", [during, after], area)

    def s21_suspended_refresh(self):
        @self.scenario("QA-21", "A refresh that gets an HTML error page keeps the existing channels", "sources")
        def _(sid, title, area):
            self.mock_admin("restore")
            if not self.open_add_source():
                self.record(sid, title, "FAIL", "could not reach Add a playlist", [], area)
                return
            self.ui.tap_label("M3U / M3U8 playlist", scrolls=1, exact=True)
            self.fill("Display name", "QA Switch")
            self.fill("Playlist URL", f"{self.mock}/switchable.m3u")
            self.ui.tap_label("Save", scrolls=3, exact=True)
            before = 0
            for _ in range(20):
                db = self.db()
                r = q(db, "SELECT (SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id) FROM playlists p "
                          "WHERE name='QA Switch'") if db else []
                before = r[0][0] if r and r[0][0] != "ERR" else 0
                if before:
                    break
                time.sleep(3)
            # The provider now answers like a suspended account: HTTP 200, an HTML page.
            self.mock_admin("suspend")
            # Save returns to Settings > Playlists, where "Refresh now" acts on the active playlist —
            # the one just saved.
            refreshed = self.ui.tap_label("Refresh now", scrolls=2, exact=True)
            if not refreshed and self.open_sources():
                refreshed = self.ui.tap_label("Refresh now", scrolls=3, exact=True)
            time.sleep(10)
            db = self.db()
            r = q(db, "SELECT (SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id), last_refresh_ok, "
                      "last_refresh_message FROM playlists p WHERE name='QA Switch'") if db else []
            names = q(db, "SELECT c.name, c.urls FROM channels c JOIN playlists p ON p.id=c.playlist_id "
                          "WHERE p.name='QA Switch' LIMIT 3") if db else []
            shot = self.ev.shot("after_suspended_refresh")
            self.mock_admin("restore")
            after = r[0][0] if r else None
            served = self.mock_requests("/switchable.m3u")
            detail = (f"before {before} channels; provider switched to an HTML 'Account suspended' page; "
                      f"Refresh now tapped={bool(refreshed)}; switchable.m3u requests={len(served)}; after: {r} "
                      f"sample={names}")
            if not before or not refreshed or len(served) < 2:
                self.record(sid, title, "WARN", "could not set up: " + detail, [shot], area)
            else:
                kept = after == before and r and r[0][1] == 0
                self.record(sid, title, "PASS" if kept else "FAIL", detail, [shot], area)

    def s15_deep_link_fuzz(self):
        @self.scenario("QA-15", "Malformed and hostile deep links are ignored", "security")
        def _(sid, title, area):
            links = ["shidur://channel/999999", "shidur://channel/abc", "shidur://channel/", "shidur://channel/-1",
                     "shidur://channel/1%27%20OR%201=1", "shidur://channel/" + "9" * 40, "shidur://other/1"]
            died = []
            for l in links:
                deep_link(l)
                time.sleep(2)
                if not pid():
                    died.append(l)
                    self.ensure_running()
            self.record(sid, title, "PASS" if not died else "FAIL",
                        f"{len(links)} links, crashes on: {died}", [self.ev.shot("deeplink_fuzz")], area)

    def s16_locale(self):
        @self.scenario("QA-16", "Hebrew UI is complete and right-to-left", "i18n")
        def _(sid, title, area):
            sdk = int(self.facts["device"]["sdk"] or 0)
            if sdk >= 33:
                sh(f"cmd locale set-app-locales {PKG} --locales iw")
            else:
                self.record(sid, title, "SKIP", f"per-app locale needs API 33; device is {sdk}. Covered by "
                                                "StringsParityTest/HebrewStringsTest in CI", [], area)
                return
            force_stop()
            launch()
            time.sleep(4)
            shots = []
            for dest in ("הגדרות", "מדריך"):
                if self.form == "tv":
                    key("KEYCODE_BACK")
                self.ui.tap_label(dest, scrolls=0)
                time.sleep(2)
                shots.append(self.ev.shot(f"he_{len(shots)}"))
            nodes = self.ui.dump("hebrew")
            texts = [n.label for n in nodes if n.label]
            heb = [t for t in texts if re.search(r"[\u0590-\u05FF]", t)]
            latin_ui = [t for t in texts if re.fullmatch(r"[A-Za-z][A-Za-z ,.'’…-]{3,}", t) and not t.startswith("QA")
                        and not t.startswith("XC")]
            sh(f"cmd locale set-app-locales {PKG} --locales en")
            force_stop()
            launch()
            time.sleep(3)
            status = "PASS" if heb and not latin_ui else ("WARN" if heb else "FAIL")
            self.record(sid, title, status,
                        f"{len(heb)} Hebrew labels; untranslated-looking labels: {latin_ui[:10]}", shots, area)

    def s17_scale(self):
        @self.scenario("QA-17", "Scale: a 5,000-channel playlist loads and scrolls", "performance")
        def _(sid, title, area):
            if not self.open_add_source():
                self.record(sid, title, "FAIL", "could not reach Add a playlist", [], area)
                return
            self.ui.tap_label("M3U / M3U8 playlist", scrolls=1, exact=True)
            self.fill("Display name", "QA Big")
            self.fill("Playlist URL", f"{self.mock}/big.m3u")
            self.ui.tap_label("Save", scrolls=3, exact=True)
            t0, count = time.time(), 0
            while time.time() - t0 < 120:
                db = self.db()
                if db:
                    r = q(db, "SELECT (SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id) FROM playlists p "
                              "WHERE name='QA Big'")
                    # A read that lands mid-write comes back as ("ERR", ...); count it as not yet.
                    count = r[0][0] if r and isinstance(r[0][0], int) else 0
                    if count >= 5000:
                        break
                time.sleep(4)
            secs = round(time.time() - t0, 1)
            # Scroll the channel list hard and read the frame statistics.
            sh(f"dumpsys gfxinfo {PKG} reset")
            self.home_of_app()
            if self.form == "phone":
                self.ui.tap_label("Live", scrolls=0, exact=True)
                time.sleep(2)
                for _ in range(15):
                    swipe_up()
            else:
                key("KEYCODE_DPAD_CENTER")
                time.sleep(2)
                key("KEYCODE_DPAD_DOWN", times=60, pause=0.08)
            gfx = sh(f"dumpsys gfxinfo {PKG}")
            jank = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", gfx)
            total = re.search(r"Total frames rendered: (\d+)", gfx)
            p90 = re.search(r"90th percentile: (\d+)ms", gfx)
            shot = self.ev.shot("big_list")
            self.facts["scale"] = {"channels": count, "load_s": secs, "jank": jank.group(0) if jank else None,
                                   "frames": total.group(1) if total else None, "p90": p90.group(1) if p90 else None}
            alive = bool(pid())
            # Frame statistics on a software-rendered emulator say little about a real TV box, so
            # they are recorded, not judged.
            status = "PASS" if count >= 5000 and secs < 60 and alive else ("WARN" if count >= 5000 and alive else "FAIL")
            self.record(sid, title, status,
                        f"{count} channels in {secs} s; alive={alive}; {self.facts['scale']}", [shot], area)
            # Switch back to the mock playlist so later checks see normal data.
            pl = self.facts.get("m3u_playlist_id")
            if pl:
                deep_link(f"shidur://channel/{self.channel_id('qa.ch01')}")

    def s18_monkey(self):
        @self.scenario("QA-18", "Monkey: 4,000 random events with a fixed seed", "stability")
        def _(sid, title, area):
            self.home_of_app()
            nav = "--pct-nav 30 --pct-majornav 20 --pct-touch 20 --pct-motion 5" if self.form == "tv" else \
                  "--pct-touch 45 --pct-motion 15 --pct-nav 10 --pct-majornav 10"
            out = sh(f"monkey -p {PKG} -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 2 {nav} "
                     f"--ignore-security-exceptions -v 4000", timeout=900)
            with open(os.path.join(self.out, "monkey.txt"), "w") as f:
                f.write(out)
            crashed = "// CRASH" in out
            anr = "NOT RESPONDING" in out
            done = "Monkey finished" in out
            m = re.search(r"Events injected: (\d+)", out)
            # Monkey may switch network/airplane; restore.
            sh("svc wifi enable")
            sh("svc data enable")
            shot = self.ev.shot("after_monkey")
            crash_line = next((l for l in out.splitlines() if "Long Msg" in l or "Short Msg" in l), "")
            aborted = "Monkey aborted" in out
            self.record(sid, title, "FAIL" if crashed or anr else ("PASS" if done else "WARN"),
                        f"injected {m.group(1) if m else '?'}; crash={crashed} anr={anr}"
                        f"{' (monkey itself aborted, no app crash)' if aborted and not crashed else ''} {crash_line[:200]}",
                        [shot], area)
            self.ensure_running()

    def s19_resources(self):
        @self.scenario("QA-19", "Memory and storage after the run", "performance")
        def _(sid, title, area):
            self.ensure_running()
            cid = self.channel_id("qa.ch01")
            if cid:
                deep_link(f"shidur://channel/{cid}")
                time.sleep(8)
            mem = sh(f"dumpsys meminfo {PKG}", timeout=60)
            pss = re.search(r"TOTAL PSS:\s+(\d+)", mem) or re.search(r"TOTAL\s+(\d+)", mem)
            java = re.search(r"Java Heap:\s+(\d+)", mem)
            native = re.search(r"Native Heap:\s+(\d+)", mem)
            du = sh(f"run-as {PKG} du -sk databases cache files 2>/dev/null")
            pss_mb = int(pss.group(1)) // 1024 if pss else None
            self.facts["memory"] = {"pss_mb": pss_mb, "java_kb": java.group(1) if java else None,
                                    "native_kb": native.group(1) if native else None, "storage": du.strip()}
            budget = 350 if self.form == "tv" else 450
            status = "PASS" if pss_mb and pss_mb < budget else ("WARN" if pss_mb else "FAIL")
            self.record(sid, title, status, f"PSS {pss_mb} MB while playing (budget {budget}); java "
                        f"{self.facts['memory']['java_kb']} KB native {self.facts['memory']['native_kb']} KB; "
                        f"storage: {du.strip()}", [], area)

    def s22_parental(self):
        @self.scenario("QA-22", "Parental PIN: a locked channel and Settings wait for it", "parental")
        def _(sid, title, area):
            notes, shots, fail = [], [], False
            # From a clean start: this runs after the monkey, which leaves keyboards open and
            # screens half-entered (run 38407146385: a sticker keyboard swallowed every Back).
            reached, trail = False, []
            for start in range(2):
                force_stop()
                launch()
                # The phone's cold start is ~10 s on the emulator and then opens the last channel.
                time.sleep(4 if start == 0 else 12)
                hide_ime()
                if self.form == "tv":
                    key("KEYCODE_BACK")
                opened = self.go_settings()
                found = opened and self.ui.tap_label("Parental controls", scrolls=4, exact=True)
                trail.append(f"try {start + 1}: settings={opened} parental={bool(found)} "
                             f"screen={self.ui.texts()[:12]}")
                print(f"[{self.form}]   QA-22 {trail[-1]}", flush=True)
                if found:
                    reached = True
                    break
                self.ui.dump(f"parental_unreachable_{start + 1}")
            if not reached:
                self.record(sid, title, "FAIL", "could not reach Settings > Parental controls; " + " | ".join(trail),
                            [self.ev.shot("parental_unreachable")], area)
                return
            notes.append(trail[-1].split(" screen=")[0])
            self.fill("Set a PIN", "1234", secret=True)
            self.ui.tap_label("Save", scrolls=2, exact=True)
            # "Remove PIN" appears once the hash is stored, which is off the main thread.
            saved = self.ui.wait_for("Remove PIN", timeout=20, exact=True)
            switched = self.ui.tap_label("Require the PIN to open Settings", scrolls=3, exact=True)
            time.sleep(1)
            shots.append(self.ev.shot("parental_set"))
            cid = self.channel_id("qa.ch05")
            locked = self.edit_db("UPDATE channels SET locked=1 WHERE id=?", (cid,)) if cid else 0
            notes.append(f"PIN saved={bool(saved)}; settings switch tapped={bool(switched)}; "
                         f"qa.ch05 locked rows={locked}")
            fail |= not saved or not switched

            def enter(pin):
                fields = [n for n in self.ui.dump() if "EditText" in n.cls]
                if fields:
                    tap(*fields[0].center)
                clear_field()
                type_text(pin)
                hide_ime()
                # The prompt re-centres once the keyboard goes; a tap straight after hit where OK
                # used to be and the PIN was never sent (run 38413573319).
                time.sleep(1.5)
                self.ui.tap_label("OK", scrolls=0, exact=True)

            # Something may already be playing behind the prompt (the last channel resumes on
            # start), so what counts is whether the locked channel's own stream was fetched.
            def fetched():
                return len(self.mock_requests("ch=05"))

            # A locked channel, reached the way the launcher reaches it.
            launch()
            time.sleep(6)
            before = fetched()
            deep_link(f"shidur://channel/{cid}")
            time.sleep(6)
            prompt = self.ui.find("Enter your PIN")
            leaked = fetched() - before
            shots.append(self.ev.shot("pin_prompt"))
            notes.append(f"deep link to the locked channel: prompt={bool(prompt)} "
                         f"locked stream requests behind it={leaked}")
            fail |= not prompt or leaked > 0

            enter("9999")
            wrong = self.ui.wait_for("That PIN is not correct", timeout=15)
            notes.append(f"wrong PIN refused={bool(wrong)}")
            fail |= not wrong

            enter("1234")
            end = time.time() + 20
            while time.time() < end and self.ui.find("Enter your PIN"):
                time.sleep(1)
            time.sleep(5)
            closed = not self.ui.find("Enter your PIN")
            started = fetched() - before - leaked
            plays = audio_playing(self.uid)
            shots.append(self.ev.shot("pin_accepted"))
            notes.append(f"right PIN: prompt closed={closed} locked stream requests={started} sound={plays}")
            fail |= not closed or started == 0 or not plays

            # Away and back: the next person is asked again, and Settings is behind the PIN.
            key("KEYCODE_HOME")
            time.sleep(3)
            launch()
            time.sleep(3)
            if self.form == "tv":
                key("KEYCODE_BACK")
            self.go_settings()
            time.sleep(2)
            gate = self.ui.find("Enter your PIN")
            exposed = self.ui.find("Playlists and guide", exact=True)
            shots.append(self.ev.shot("settings_gate"))
            notes.append(f"Settings after leaving the app: PIN asked={bool(gate)} settings shown={bool(exposed)}")
            fail |= not gate or bool(exposed)

            enter("1234")
            opened = self.ui.wait_for("Playlists and guide", timeout=15, exact=True)
            notes.append(f"Settings after the PIN: open={bool(opened)}")
            fail |= not opened
            self.record(sid, title, "FAIL" if fail else "PASS", "; ".join(notes), shots, area)

    def s20_audit(self):
        log = adb("logcat", "-d", "-v", "threadtime", timeout=120)
        with open(os.path.join(self.out, "logcat.txt"), "w") as f:
            f.write(log)
        app_pids = set(re.findall(r"^\S+ \S+\s+(\d+)", "\n".join(l for l in log.splitlines() if PKG in l), re.M))
        errors = {}
        for line in log.splitlines():
            m = re.match(r"^\S+ \S+\s+(\d+)\s+\d+ ([EWF]) ([^:]+):", line)
            if m and (m.group(1) in app_pids) and m.group(2) in "EF":
                tag = m.group(3).strip()
                errors[tag] = errors.get(tag, 0) + 1
        fatal = log.count("FATAL EXCEPTION")
        anr = len(re.findall(rf"ANR in {re.escape(PKG)}", log))
        strict = log.count("StrictMode")
        top = sorted(errors.items(), key=lambda kv: -kv[1])[:12]
        self.facts["log_errors_by_tag"] = top
        self.record("QA-20", "Log audit: crashes, ANRs, error volume", "FAIL" if fatal or anr else "INFO",
                    f"FATAL={fatal} ANR={anr} StrictMode={strict}; top error tags: {top}", ["logcat.txt"], "stability")
        # The database at the end, for the report.
        db = self.db()
        if db:
            summary = {
                "playlists": q(db, "SELECT id, name, type, last_refresh_ok, last_refresh_message, "
                                   "(SELECT COUNT(*) FROM channels c WHERE c.playlist_id=p.id) FROM playlists p"),
                "programmes": q(db, "SELECT COUNT(*) FROM programmes"),
                "history": q(db, "SELECT COUNT(*) FROM history"),
            }
            with open(os.path.join(self.out, "db_summary.json"), "w") as f:
                json.dump(summary, f, ensure_ascii=False, indent=1, default=str)
            self.facts["db_summary"] = summary

    def finish(self):
        counts = {}
        for r in self.results:
            counts[r["status"]] = counts.get(r["status"], 0) + 1
        report = {"form": self.form, "counts": counts, "results": self.results, "facts": self.facts}
        with open(os.path.join(self.out, "report.json"), "w") as f:
            json.dump(report, f, ensure_ascii=False, indent=1, default=str)
        lines = [f"# Shidur QA — {self.form}", "", f"Device: `{self.facts.get('device')}`", "",
                 "Counts: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())), "",
                 "| ID | Status | Scenario | Detail |", "|---|---|---|---|"]
        for r in self.results:
            d = r["detail"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {r['id']} | {r['status']} | {r['title']} | {d[:600]} |")
        lines += ["", "## Facts", "", "```json", json.dumps(self.facts, ensure_ascii=False, indent=1, default=str),
                  "```"]
        with open(os.path.join(self.out, "report.md"), "w") as f:
            f.write("\n".join(lines) + "\n")
        print("\n".join(lines[:60]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--form", choices=["tv", "phone"], required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--mock", default="http://10.0.2.2:8080")
    a = ap.parse_args()
    Runner(a.form, a.apk, a.out, a.mock).run_all()


if __name__ == "__main__":
    main()
