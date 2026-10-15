#!/usr/bin/env python3
"""Generates the Design Components artboards for the Shidur design-system canvas.

SUPERSEDED: the design language is Studio (docs/design/studio/, ADR-027). This script and
the artboards it wrote describe the system before Studio and are kept for history only.

Every value here is lifted verbatim from the app's own source:

  app/src/main/java/com/tivicopy/player/ui/theme/Theme.kt      colours, shapes
  app/src/main/java/com/tivicopy/player/ui/theme/Type.kt       the type ramp
  app/src/main/java/com/tivicopy/player/ui/tv/TvSurfaces.kt    panel and card
  app/src/main/java/com/tivicopy/player/ui/common/Motion.kt    durations, spring
  app/src/main/java/com/tivicopy/player/ui/common/TvMetrics.kt ten-foot metrics
  app/src/main/java/com/tivicopy/player/ui/common/Components.kt shared widgets

Run it from this directory; it writes the .dc.html artboards and canvas.json
next to itself. Those files are what the canvas is seeded from.
"""

import json
import os

# --- the sheet's own palette, taken from the app's dark scheme -----------------

GROUND = "#171C24"        # surfaceDim
PLATE = "#232A35"         # surface
PLATE_HI = "#2D3542"      # surfaceContainerHigh
INK = "#E9EDF3"           # onSurface
INK_DIM = "#BEC7D4"       # onSurfaceVariant
INK_FAINT = "#6B7482"     # outline
ACCENT = "#4C8DFF"        # primary
ACCENT_SOFT = "#9CC2FF"   # secondary
HAIRLINE = "rgba(233,237,243,0.12)"

FONT_LINK = ('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
             'family=Rubik:wght@400;500;600;700&display=swap">')

BASE_CSS = """
    body {
      margin: 0;
      background: #171C24;
      color: #E9EDF3;
      font-family: 'Rubik', 'Segoe UI', system-ui, sans-serif;
      direction: rtl;
      -webkit-font-smoothing: antialiased;
    }
    a { color: #4C8DFF; text-decoration: none; }
    a:hover { color: #9CC2FF; }
    .mono {
      font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
      direction: ltr;
      unicode-bidi: isolate;
    }
"""


def page(title_comment, css, body, script=None):
    parts = [
        "<!doctype html>",
        "<html>",
        "<head>",
        '  <meta charset="utf-8">',
        '  <script src="./support.js"></script>',
        "</head>",
        "<body>",
        "<x-dc>",
        "<helmet>",
        "  " + FONT_LINK,
        "  <style>" + BASE_CSS + css + "  </style>",
        "</helmet>",
        "<!-- " + title_comment + " -->",
        body,
        "</x-dc>",
    ]
    if script:
        parts.append(script)
    parts += ["</body>", "</html>", ""]
    return "\n".join(parts)


def root(width, body, pad=48, gap=40):
    return ('<div style="width: ' + str(width) + 'px; box-sizing: border-box; padding: '
            + str(pad) + 'px; display: flex; flex-direction: column; gap: ' + str(gap)
            + 'px; background: ' + GROUND + ';">\n' + body + '\n</div>')


def header(eyebrow, title, lede):
    return (
        '<div style="display: flex; flex-direction: column; gap: 10px;">\n'
        '  <div class="mono" style="font-size: 12px; letter-spacing: 0.09em; text-align: right; '
        'text-transform: uppercase; color: ' + INK_FAINT + ';">' + eyebrow + '</div>\n'
        '  <div style="font-size: 34px; line-height: 42px; font-weight: 700;">' + title + '</div>\n'
        '  <div style="font-size: 16px; line-height: 24px; color: ' + INK_DIM
        + '; max-width: 74ch; text-wrap: pretty;">' + lede + '</div>\n'
        '</div>'
    )


def section(label):
    return (
        '<div style="display: flex; align-items: center; gap: 14px;">\n'
        '  <div style="font-size: 15px; line-height: 20px; font-weight: 500; color: '
        + INK_DIM + '; white-space: nowrap;">' + label + '</div>\n'
        '  <div style="flex-grow: 1; height: 1px; background: ' + HAIRLINE + ';"></div>\n'
        '</div>'
    )


def note(text):
    return ('<div style="font-size: 14px; line-height: 21px; color: ' + INK_DIM
            + '; max-width: 82ch; text-wrap: pretty;">' + text + '</div>')


def card(body, pad=24, gap=16, bg=PLATE):
    return ('<div style="background: ' + bg + '; border: 1px solid ' + HAIRLINE
            + '; border-radius: 14px; padding: ' + str(pad) + 'px; display: flex; '
            'flex-direction: column; gap: ' + str(gap) + 'px;">\n' + body + '\n</div>')


def write(name, text):
    with open(name, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("wrote", name, len(text), "bytes")


# =============================================================================
# Main.dc.html — colour
# =============================================================================

# ui/theme/Theme.kt. Order follows the declaration order in DarkScheme.
ROLES = [
    ("primary",                 "#4C8DFF", "#4C8DFF", "#1F5FD0"),
    ("onPrimary",               "#00122E", "#00122E", "#FFFFFF"),
    ("primaryContainer",        "#1F5FD0", "#1F5FD0", "#D8E4FF"),
    ("onPrimaryContainer",      "#E7F0FF", "#E7F0FF", "#001A41"),
    ("secondary",               "#9CC2FF", "#9CC2FF", "#3C5C8A"),
    ("background",              "#1B212B", "#000000", "#F7F9FC"),
    ("onBackground",            "#E9EDF3", "#E9EDF3", "#11151B"),
    ("surface",                 "#232A35", "#000000", "#FFFFFF"),
    ("onSurface",               "#E9EDF3", "#E9EDF3", "#11151B"),
    ("surfaceVariant",          "#323A47", "#14161A", "#E1E6EE"),
    ("onSurfaceVariant",        "#BEC7D4", "#BEC7D4", "#43495A"),
    ("outline",                 "#6B7482", "#6B7482", "#757C8A"),
    ("error",                   "#FF6B6B", "#FF6B6B", "#B3261E"),
    ("onError",                 "#2B0000", "#2B0000", "#FFFFFF"),
    ("surfaceDim",              "#171C24", "#000000", "#E4E8EF"),
    ("surfaceContainer",        "#272F3B", "#0A0C0F", "#EEF1F6"),
    ("surfaceContainerHigh",    "#2D3542", "#101318", "#E8ECF2"),
    ("surfaceContainerHighest", "#333C4A", "#161A20", "#E1E6EE"),
]


def swatch_row(role, hex_value, overridden):
    tag = ""
    if overridden:
        tag = ('<span class="mono" style="font-size: 10px; font-weight: 500; color: '
               + ACCENT + '; border: 1px solid ' + ACCENT + '; border-radius: 4px; '
               'padding: 1px 5px;">override</span>')
    return (
        '  <div style="display: flex; align-items: center; gap: 12px;">\n'
        '    <div style="width: 30px; height: 30px; flex: none; border-radius: 6px; '
        'background: ' + hex_value + '; border: 1px solid ' + HAIRLINE + ';"></div>\n'
        '    <div style="display: flex; flex-direction: column; gap: 1px; min-width: 0;">\n'
        '      <div style="display: flex; align-items: center; gap: 6px;">'
        '<span class="mono" style="font-size: 12px; font-weight: 500; color: ' + INK
        + ';">' + role + '</span>' + tag + '</div>\n'
        '      <div class="mono" style="font-size: 11px; color: ' + INK_FAINT + ';">'
        + hex_value + '</div>\n'
        '    </div>\n'
        '  </div>'
    )


def scheme_column(name, code, subtitle, index, mark_overrides):
    rows = []
    for role, dark, amoled, light in ROLES:
        value = (dark, amoled, light)[index]
        overridden = mark_overrides and value != dark
        rows.append(swatch_row(role, value, overridden))
    return (
        '<div style="display: flex; flex-direction: column; gap: 8px; flex-grow: 1;">\n'
        '  <div style="display: flex; flex-direction: column; gap: 2px; padding-bottom: 10px; '
        'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
        '    <div style="font-size: 17px; line-height: 24px; font-weight: 500;">' + name + '</div>\n'
        '    <div style="font-size: 11px; color: ' + INK_FAINT + ';">'
        '<span class="mono">' + code + '</span>'
        + (' &middot; ' + subtitle if subtitle else '') + '</div>\n'
        '  </div>\n' + "\n".join(rows) + '\n</div>'
    )


def build_colour():
    columns = (
        '<div style="display: flex; gap: 40px; align-items: flex-start;">\n'
        + scheme_column("כהה", "DarkScheme", "ברירת המחדל", 0, False) + "\n"
        + scheme_column("AMOLED", "DarkScheme.copy(...)", "שבע עקיפות", 1, True) + "\n"
        + scheme_column("בהיר", "LightScheme", "", 2, False) + "\n"
        '</div>'
    )

    live_plate = (
        '<div style="display: flex; align-items: center; gap: 8px; background: rgba(0,0,0,0.72); '
        'border-radius: 16px; padding: 6px 14px; align-self: flex-start;">\n'
        '  <div style="width: 8px; height: 8px; border-radius: 50%; background: #FF6B6B;"></div>\n'
        '  <div style="font-size: 13px; line-height: 17px; font-weight: 500; color: #FFFFFF;">שידור חי</div>\n'
        '</div>'
    )

    # What monogram() and monogramColour() actually return for these names: the
    # mark is the initial of each of the first two words, so a Hebrew channel name
    # carrying a number comes out as a letter and a digit; the hue is
    # `(name.hashCode() and 0x7FFFFFFF) % 360`, which is not a designed sequence
    # and is not meant to read as one.
    hues = [
        ("כאן 11", "כ1", 350),
        ("רשת 13", "ר1", 41),
        ("קשת 12", "ק1", 249),
        ("גלי צהל", "גצ", 333),
    ]
    discs = []
    for label, mark, hue in hues:
        discs.append(
            '  <div style="display: flex; flex-direction: column; align-items: center; gap: 6px; width: 62px;">\n'
            '    <div style="width: 46px; height: 46px; border-radius: 50%; background: hsl(' + str(hue)
            + ', 42%, 32%); display: flex; align-items: center; justify-content: center; font-size: 15px; '
            'font-weight: 500; color: #FFFFFF;">' + mark + '</div>\n'
            '    <div style="font-size: 11px; line-height: 14px; color: ' + INK_FAINT
            + '; text-align: center;">' + label + '</div>\n'
            '  </div>'
        )

    alphas = [
        ("hairline של פאנל", "onSurface", "0.12", "rgba(233,237,243,0.12)"),
        ("קלף במנוחה", "surface", "0.65", "rgba(35,42,53,0.65)"),
        ("טבעת של מתנגן", "primary", "0.45", "rgba(76,141,255,0.45)"),
        ("מילוי שורה בפוקוס", "primary", "0.22", "rgba(76,141,255,0.22)"),
    ]
    alpha_rows = []
    for label, base, alpha, colour in alphas:
        alpha_rows.append(
            '  <div style="display: flex; align-items: center; gap: 12px;">\n'
            '    <div style="width: 56px; height: 26px; flex: none; border-radius: 6px; background: '
            + colour + '; border: 1px solid ' + HAIRLINE + ';"></div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK + ';">' + label + '</div>\n'
            '    <div class="mono" style="font-size: 11px; color: ' + INK_FAINT + '; margin-inline-start: auto;">'
            + base + ' @ ' + alpha + '</div>\n'
            '  </div>'
        )

    outside = (
        '<div style="display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px;">\n'
        + card(
            '<div style="font-size: 15px; line-height: 20px; font-weight: 500;">הפלטה של LiveBadge</div>\n'
            + live_plate + "\n"
            + note('שחור ב&#8209;72% עם טקסט לבן, לא צבע מהסכימה: התג צריך להיקרא מעל וידאו חי, '
                   'מעל רקע שנבנה מגוון התחנה, ומעל פאנל בערכת נושא בהירה. הנקודה היא '
                   '<span class="mono">error</span>.'))
        + "\n" + card(
            '<div style="font-size: 15px; line-height: 20px; font-weight: 500;">צבע מונוגרם</div>\n'
            '<div style="display: flex; gap: 8px; flex-wrap: wrap;">\n' + "\n".join(discs) + '\n</div>\n'
            + note('<span class="mono">Color.hsl(hash(name) % 360, 0.42f, 0.32f)</span> — '
                   'רוויה ובהירות קבועות, הגוון נגזר מה&#8209;hashCode של השם. הסימן הוא האות '
                   'הראשונה של כל אחת משתי המילים הראשונות, ולכן שם ערוץ עם מספר יוצא אות וספרה. '
                   'הערכים כאן הם מה שהפונקציות מחזירות בפועל, לא מבחר.'))
        + "\n" + card(
            '<div style="font-size: 15px; line-height: 20px; font-weight: 500;">שקיפויות קבועות</div>\n'
            '<div style="display: flex; flex-direction: column; gap: 10px;">\n' + "\n".join(alpha_rows) + '\n</div>\n'
            + note('לעולם לא <span class="mono">Color.Transparent</span> למצב מנוחה — שקוף הוא '
                   '<em>שחור</em> שקוף, וטבעת בהירה שמתכנסת אליו מחשיכה בדרך החוצה.'))
        + '\n</div>'
    )

    body = root(1280, "\n".join([
        header("ui/theme/Theme.kt",
               "צבע",
               "שלוש ערכות, משפחה אחת של אפור&#8209;כחלחל. הרמפה יושבת דרגה מעל שחור&#8209;כמעט "
               "(<span class=\"mono\">#0E1116</span>) — כהה מספיק כדי למסגר תמונה בהירה, בהירה מספיק "
               "שהתפריטים לא ייקראו כשחור. AMOLED הוא הבחירה המפורשת למי שרוצה שחור אמיתי."),
        section("תפקידי הסכימה"),
        columns,
        section("מה שלא בא מהסכימה"),
        outside,
    ]))
    return page("Colour — ui/theme/Theme.kt", "", body)


# =============================================================================
# Typography.dc.html
# =============================================================================

# ui/theme/Type.kt, at fontScale = 1.0.
RAMP = [
    ("displaySmall",   34, 700, 42, "מדריך השידורים"),
    ("headlineMedium", 26, 700, 34, "המשך לצפות"),
    ("headlineSmall",  22, 600, 30, "ערוצים מועדפים"),
    ("titleLarge",     20, 600, 27, "חדשות הערב"),
    ("titleMedium",    17, 500, 24, "כאן 11 &middot; תאגיד השידור"),
    ("titleSmall",     15, 500, 21, "עכשיו: מהדורת חדשות"),
    ("bodyLarge",      16, 400, 23, "המהדורה המרכזית עם דנה וייס, ישירות מהאולפן בתל אביב."),
    ("bodyMedium",     14, 400, 20, "הקלטה תתחיל אוטומטית חמש דקות לפני תחילת התוכנית."),
    ("bodySmall",      13, 400, 18, "מקור: EPG של il-epg.idanck.com, עודכן לפני 12 דקות."),
    ("labelLarge",     15, 500, 20, "שמור בהגדרות"),
    ("labelMedium",    13, 500, 17, "שידור חי"),
    ("labelSmall",     12, 500, 16, "HD &middot; 1080p"),
]

WEIGHT_NAMES = {400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold"}


def build_typography():
    weights = []
    for weight in (400, 500, 600, 700):
        weights.append(
            '  <div style="display: flex; flex-direction: column; gap: 6px; flex-grow: 1;">\n'
            '    <div style="font-size: 30px; line-height: 38px; font-weight: ' + str(weight)
            + '; color: ' + INK + ';">אבגד Shidur</div>\n'
            '    <div class="mono" style="font-size: 11px; color: ' + INK_FAINT + ';">'
            + str(weight) + ' &middot; ' + WEIGHT_NAMES[weight] + '</div>\n'
            '  </div>'
        )

    rows = []
    for name, size, weight, line, sample in RAMP:
        rows.append(
            '  <div style="display: flex; align-items: baseline; gap: 28px; padding: 12px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div style="width: 210px; flex: none; display: flex; flex-direction: column; gap: 2px;">\n'
            '      <div class="mono" style="font-size: 12px; font-weight: 500; color: ' + INK + ';">'
            + name + '</div>\n'
            '      <div class="mono" style="font-size: 11px; color: ' + INK_FAINT + ';">'
            + str(size) + '/' + str(line) + ' &middot; ' + WEIGHT_NAMES[weight] + '</div>\n'
            '    </div>\n'
            '    <div style="font-size: ' + str(size) + 'px; line-height: ' + str(line)
            + 'px; font-weight: ' + str(weight) + '; color: ' + INK + '; min-width: 0;">'
            + sample + '</div>\n'
            '  </div>'
        )

    scale_stops = []
    for stop, label in ((0.8, "מינימום"), (1.0, "ברירת מחדל"), (1.6, "מקסימום")):
        smallest = round(12 * stop, 1)
        scale_stops.append(
            '  <div style="display: flex; flex-direction: column; gap: 6px; flex-grow: 1; '
            'background: ' + PLATE_HI + '; border-radius: 10px; padding: 16px;">\n'
            '    <div style="font-size: 11px; color: ' + INK_FAINT + ';">'
            '<span class="mono">&times;' + str(stop) + '</span> &middot; ' + label + '</div>\n'
            '    <div style="font-size: ' + str(round(20 * stop, 1)) + 'px; line-height: '
            + str(round(27 * stop, 1)) + 'px; font-weight: 600;">חדשות הערב</div>\n'
            '    <div class="mono" style="font-size: 11px; color: ' + INK_DIM + ';">labelSmall &rarr; '
            + str(smallest) + 'sp</div>\n'
            '  </div>'
        )

    body = root(1060, "\n".join([
        header("ui/theme/Type.kt &middot; ADR-019",
               "טיפוגרפיה",
               "האפליקציה נושאת גופן משלה. <strong>Rubik</strong> — גיאומטרי עברי&#8209;לטיני של Hubert &amp; "
               "Fischer ומאיר סדן, ברישיון SIL OFL 1.1 — נבחר כי הוא המשפחה החופשית היחידה עם טווח משקלים "
               "עובד לעברית, ולטינית ועברית מגיעות בו מאותו מעצב. ארבעה קבצים סטטיים ולא הגופן המשתנה, "
               "כי רצפת ה&#8209;API של האפליקציה היא 23 ו&#8209;<span class=\"mono\">FontVariation</span> "
               "עובד רק מ&#8209;26."),
        section("משקלים &middot; ארבעה קבצים, כ&#8209;93KB כל אחד"),
        '<div style="display: flex; gap: 24px;">\n' + "\n".join(weights) + '\n</div>',
        section("הרמפה &middot; fontScale = 1.0"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(rows) + '\n</div>',
        section("קנה מידה של הגופן &middot; FR-UI-19"),
        '<div style="display: flex; gap: 20px;">\n' + "\n".join(scale_stops) + '\n</div>',
        note("הרמפה עצמה נעצרת ב&#8209;12sp — הרצפה שההנחיה לעשר רגליים ב&#8209;"
             "<span class=\"mono\">docs/03</span> קובעת. שום סגנון לא דק מ&#8209;Regular: טקסט קטן "
             "באפליקציה יושב מעל וידאו חי, וקווים דקים מפסידים ל&#8209;NFR&#8209;UX&#8209;03."),
        note("<strong style=\"color: #FF6B6B;\">להכרעה:</strong> ההערה ב&#8209;"
             "<span class=\"mono\">Type.kt</span> אומרת ששום דבר לא נופל מתחת ל&#8209;12sp בקנה "
             "המידה הקטן ביותר. זה נכון לרמפה כפי שהוכרזה, אבל לא לערך המחושב: "
             "<span class=\"mono\">(size * scale).sp</span> אינו חסום מלמטה, ולכן ב&#8209;&times;0.8 "
             "<span class=\"mono\">labelSmall</span> יוצא 9.6sp ו&#8209;<span class=\"mono\">bodySmall</span> "
             "10.4sp. או שחוסמים רצפה, או שמתקנים את ההערה."),
    ]))
    return page("Typography — ui/theme/Type.kt", "", body)


# =============================================================================
# Shape.dc.html — corners, spacing, borders
# =============================================================================

SHAPES = [
    ("extraSmall", 6,  "לוגו ערוץ בשורה"),
    ("small",      10, "לא בשימוש היום"),
    ("medium",     14, "קלף, שורה בטלוויזיה"),
    ("large",      20, "שבב היום במדריך"),
    ("extraLarge", 28, "פאנל צף"),
]

RADII_IN_USE = [
    ("28dp", "TV_PANEL_CORNER", "פס הניגון, סרט הערוצים, קפסולת הניווט, תפריט הניגון"),
    ("16dp", "LIVE_BADGE_CORNER", "תג &quot;שידור חי&quot;"),
    ("14dp", "TV_CARD_CORNER", "אריח בית, אריח בסרט, שורת רשימה בטלוויזיה"),
    ("8dp",  "TvMetrics.Phone.rowCorner", "שורת רשימה בטלפון"),
    ("6dp",  "ChannelLogo", "לוגו ריבועי"),
    ("4dp",  "Badge", "תגית טקסט קטנה"),
    ("50%",  "CircleShape", "לוחית תחנת רדיו — רק כשהיצירה היא הסמל של התחנה עצמה — והנקודה בתג השידור החי"),
]

# Counted over app/src/main/java/com/tivicopy/player/ui with a grep for `N.dp`.
SPACING = [
    (2, 23), (4, 21), (6, 30), (8, 59), (10, 16),
    (12, 72), (16, 21), (20, 8), (24, 9), (32, 5),
]


def build_shape():
    corners = []
    for name, dp, use in SHAPES:
        corners.append(
            '  <div style="display: flex; flex-direction: column; align-items: center; gap: 10px; flex-grow: 1;">\n'
            '    <div style="width: 100%; height: 96px; border-radius: ' + str(dp) + 'px; background: '
            + PLATE_HI + '; border: 1px solid ' + HAIRLINE + ';"></div>\n'
            '    <div style="display: flex; flex-direction: column; align-items: center; gap: 2px;">\n'
            '      <div class="mono" style="font-size: 12px; font-weight: 500; color: ' + INK + ';">'
            + name + '</div>\n'
            '      <div class="mono" style="font-size: 11px; color: ' + ACCENT + ';">' + str(dp) + 'dp</div>\n'
            '      <div style="font-size: 11px; line-height: 15px; color: ' + INK_FAINT
            + '; text-align: center;">' + use + '</div>\n'
            '    </div>\n'
            '  </div>'
        )

    usage = []
    for value, token, where in RADII_IN_USE:
        usage.append(
            '  <div style="display: flex; align-items: center; gap: 16px; padding: 9px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div class="mono" style="width: 56px; flex: none; font-size: 13px; font-weight: 500; color: '
            + ACCENT + ';">' + value + '</div>\n'
            '    <div class="mono" style="width: 250px; flex: none; font-size: 12px; color: ' + INK + ';">'
            + token + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">' + where + '</div>\n'
            '  </div>'
        )

    bars = []
    peak = max(count for _, count in SPACING)
    for value, count in SPACING:
        width = int(round(300 * count / peak)) + 12
        bars.append(
            '  <div style="display: flex; align-items: center; gap: 14px;">\n'
            '    <div class="mono" style="width: 44px; flex: none; font-size: 12px; font-weight: 500; color: '
            + INK + ';">' + str(value) + 'dp</div>\n'
            '    <div style="width: ' + str(width) + 'px; height: 14px; border-radius: 7px; background: '
            + ACCENT + '; opacity: 0.85;"></div>\n'
            '    <div style="font-size: 11px; color: ' + INK_FAINT + ';">'
            '<span class="mono">' + str(count) + '</span> שימושים</div>\n'
            '  </div>'
        )

    strokes = []
    for width, where in ((1, "hairline של פאנל, טבעת במנוחה"),
                         (2, "טבעת פוקוס בטלפון"),
                         (3, "טבעת פוקוס בטלוויזיה")):
        strokes.append(
            '  <div style="display: flex; flex-direction: column; gap: 10px; flex-grow: 1;">\n'
            '    <div style="height: 72px; border-radius: 14px; background: ' + PLATE
            + '; border: ' + str(width) + 'px solid ' + ACCENT + ';"></div>\n'
            '    <div class="mono" style="font-size: 12px; font-weight: 500; text-align: right; color: '
            + INK + ';">' + str(width) + 'dp</div>\n'
            '    <div style="font-size: 12px; line-height: 16px; color: ' + INK_FAINT + ';">' + where + '</div>\n'
            '  </div>'
        )

    body = root(1060, "\n".join([
        header("ui/theme/Theme.kt &middot; ui/tv/TvSurfaces.kt",
               "צורה, מרווח וקו",
               "רמפת פינות אחת לכל האפליקציה, כדי שהמשטחים המורמים — פס הניגון, הקלפים, המיני&#8209;נגן — "
               "יהיו משפחה אחת במקום שכל אחד יבחר לעצמו רדיוס."),
        section("רמפת הפינות &middot; Shapes"),
        '<div style="display: flex; gap: 20px; align-items: flex-start;">\n' + "\n".join(corners) + '\n</div>',
        section("איפה כל רדיוס מופיע בפועל"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(usage) + '\n</div>',
        section("סולם המרווחים &middot; כפי שהוא בשימוש היום"),
        '<div style="display: flex; gap: 44px; align-items: flex-start;">\n'
        '  <div style="display: flex; flex-direction: column; gap: 10px; flex: none;">\n'
        + "\n".join(bars) + '\n  </div>\n'
        '  <div style="display: flex; flex-direction: column; gap: 12px; min-width: 0;">\n'
        + note("זה תיאור, לא הצהרה: אין בקוד קובץ טוקנים למרווחים — כל מספר הוא "
               "<span class=\"mono\">N.dp</span> באתר השימוש. הספירה מראה סולם ברור בכל זאת, עם "
               "<strong>12</strong> ו&#8209;<strong>8</strong> כברירות המחדל האמיתיות, ו&#8209;6 "
               "למרווחים צמודים.")
        + "\n" + note("<span class=\"mono\">2dp</span> נראה כמו טבעת פוקוס אבל הוא בעיקר משהו אחר: "
                      "עשרה מתוך 23 השימושים הם <span class=\"mono\">Arrangement.spacedBy(2.dp)</span> "
                      "בין שורות ברשימה — הרשימות, התפריטים, המדריך והחיפוש — וזה המרווח הצפוף ביותר "
                      "שהאפליקציה משתמשת בו.")
        + "\n" + note("הרמפה למעלה כמעט ואינה נקראת: <span class=\"mono\">MaterialTheme.shapes</span> "
                      "מופיע פעם אחת בכל האפליקציה, וכל שאר הפינות כתובות כמספר באתר השימוש. הרמפה "
                      "מתארת נכון את מה שמצויר — היא פשוט לא מה שמצייר אותו.")
        + '\n  </div>\n</div>',
        section("עוביי קו"),
        '<div style="display: flex; gap: 24px;">\n' + "\n".join(strokes) + '\n</div>',
    ]))
    return page("Shape — corners, spacing, strokes", "", body)


# =============================================================================
# Motion.dc.html
# =============================================================================

MOTION_CSS = """
    .demo { cursor: default; }
    .swatch-fast, .swatch-standard, .swatch-entrance {
      background: #2D3542;
      border: 1px solid rgba(233,237,243,0.12);
    }
    .swatch-fast {
      transition: background-color 120ms cubic-bezier(0.4, 0.0, 0.2, 1),
                  border-color 120ms cubic-bezier(0.4, 0.0, 0.2, 1);
    }
    .demo:hover .swatch-fast {
      background: #4C8DFF;
      border-color: #4C8DFF;
    }
    .swatch-standard {
      transition: transform 200ms cubic-bezier(0.4, 0.0, 0.2, 1),
                  background-color 200ms cubic-bezier(0.4, 0.0, 0.2, 1);
    }
    .demo:hover .swatch-standard {
      transform: translateX(-56px);
      background: #323A47;
    }
    .swatch-entrance {
      opacity: 1;
      transform: translateY(0);
    }
    .demo:hover .swatch-entrance {
      animation: dc-entrance 260ms cubic-bezier(0.4, 0.0, 0.2, 1);
    }
    @keyframes dc-entrance {
      from { opacity: 0.06; transform: translateY(12px); }
      to { opacity: 1; transform: translateY(0); }
    }
    .lift {
      transition: transform 260ms cubic-bezier(0.20, 0.80, 0.25, 1.0),
                  border-color 120ms linear,
                  background-color 120ms linear;
    }
    .demo:hover .lift {
      transform: scale(1.06);
      background: #323A47;
      border-color: #4C8DFF;
    }
"""


def build_motion():
    demos = [
        ("FAST", 120, "swatch-fast",
         "מעבר צבע, תווית שמופיעה",
         "קצר מספיק כדי להרגיש מיידי. כל טבעת פוקוס וכל מילוי במערכת חוצים בו במקום לקפוץ."),
        ("STANDARD", 200, "swatch-standard",
         "ברירת המחדל לכל דבר עם גודל או מיקום",
         "קפסולת הניווט שנפתחת, סרט שמחליק, פאנל שמשנה גובה."),
        ("ENTRANCE", 260, "swatch-entrance",
         "משטח שלם שמגיע מעל הווידאו",
         "עמעום עם עלייה של 12dp מתחתיו. מוחלף לפי מפתח, כך שמסך שמחליף מסך נקרא כמעבר ולא כחיתוך — "
         "בלי להרכיב שני מסכים בו&#8209;זמנית."),
    ]
    blocks = []
    for name, ms, klass, headline, text in demos:
        blocks.append(
            '  <div class="demo" style="display: flex; flex-direction: column; gap: 16px; flex-grow: 1; '
            'background: ' + PLATE + '; border: 1px solid ' + HAIRLINE + '; border-radius: 14px; padding: 24px;">\n'
            '    <div style="display: flex; align-items: baseline; gap: 10px;">\n'
            '      <div class="mono" style="font-size: 13px; font-weight: 500; color: ' + INK + ';">'
            + name + '</div>\n'
            '      <div class="mono" style="font-size: 22px; line-height: 28px; font-weight: 700; color: '
            + ACCENT + ';">' + str(ms) + '<span style="font-size: 13px; font-weight: 400;">ms</span></div>\n'
            '    </div>\n'
            '    <div style="height: 84px; display: flex; align-items: center;">\n'
            '      <div class="' + klass + '" style="width: 84px; height: 60px; border-radius: 14px;"></div>\n'
            '    </div>\n'
            '    <div style="font-size: 14px; line-height: 20px; font-weight: 500; color: ' + INK + ';">'
            + headline + '</div>\n'
            '    <div style="font-size: 13px; line-height: 19px; color: ' + INK_DIM + ';">' + text + '</div>\n'
            '  </div>'
        )

    lift = (
        '<div style="display: flex; gap: 32px; align-items: stretch;">\n'
        '  <div class="demo" style="flex: none; width: 320px; background: ' + PLATE + '; border: 1px solid '
        + HAIRLINE + '; border-radius: 14px; padding: 24px; display: flex; align-items: center; '
        'justify-content: center;">\n'
        '    <div class="lift" style="width: 208px; height: 118px; border-radius: 14px; background: '
        + PLATE_HI + '; border: 3px solid rgba(76,141,255,0); display: flex; align-items: flex-end; '
        'padding: 14px; box-sizing: border-box;">\n'
        '      <div style="font-size: 15px; line-height: 20px; font-weight: 500; color: ' + INK + ';">כאן 11</div>\n'
        '    </div>\n'
        '  </div>\n'
        '  <div style="display: flex; flex-direction: column; gap: 14px; justify-content: center; min-width: 0;">\n'
        '    <div style="font-size: 20px; line-height: 27px; font-weight: 600;">'
        'הרמת הפוקוס &middot; <span class="mono" style="font-size: 16px; color: ' + ACCENT + ';">FOCUS_SCALE 1.06</span></div>\n'
        + note("קפיץ ולא משך: פוקוס ב&#8209;D&#8209;pad זז בפרצים — צופה שמחזיק את המקש חוצה חמישה "
               "אריחים בחצי שנייה — וקפיץ שנקטע באמצע נושא את המהירות שלו לאריח הבא במקום להתחיל מחדש. "
               "<span class=\"mono\">dampingRatio = 0.78</span>, "
               "<span class=\"mono\">stiffness = MediumLow</span>, מרוסן בדיוק לפני חריגה נראית — "
               "אריח שמתנדנד נקרא כצעצוע.")
        + "\n" + note("הגידול עובר ב&#8209;<span class=\"mono\">graphicsLayer</span>, כך שהוא נקרא בשלב "
                      "הציור: אין recomposition ואין מדידה מחדש, ושום דבר סביב האריח לא זז. זה מה שמאפשר "
                      "לשים את ההרמה על כל אריח ב&#8209;<span class=\"mono\">LazyRow</span>.")
        + '\n  </div>\n</div>'
    )

    rules = []
    for title, text in (
        ("מנפישים רק את מה שה&#8209;GPU עושה לבד",
         "גודל ושקיפות עוברים ב&#8209;<span class=\"mono\">graphicsLayer</span>, שהלמבדה שלו נקראת "
         "בשלב הציור. שינוי ערך מצייר מחדש את הצומת ולא מרכיב אותו מחדש."),
        ("אוצר מילים אחד",
         "פוקוס מתרומם בקפיץ, צבעים חוצים ב&#8209;FAST, משטח שמגיע עולה ומתעמעם ב&#8209;ENTRANCE. "
         "מסך שממציא תזמון משלו נקרא כאפליקציה אחרת."),
        ("שום דבר לא לולאה",
         "כשלא קורה כלום האפליקציה מציירת אפס פריימים. ממשק עשר&#8209;רגליים שמצייר את עצמו לנצח "
         "עולה סוללה בממיר ופריימים בכל השאר. היוצא היחיד הוא שומר המסך "
         "(FR&#8209;UI&#8209;31, ADR&#8209;024), שכל קיומו הוא שמסך שאיש לא יושב מולו "
         "<em>לא</em> יעמוד במקום — והוא מכבד את הכלל בכל זאת: שנים&#8209;עשר צעדים בשנייה ולא "
         "שישים, וכל ערך מונפש נקרא בתוך <span class=\"mono\">graphicsLayer</span> או "
         "<span class=\"mono\">drawBehind</span>, כך שצעד מצייר מחדש שני צמתים ולא מרכיב כלום."),
    ):
        rules.append(card(
            '<div style="font-size: 16px; line-height: 22px; font-weight: 500;">' + title + '</div>\n'
            + '<div style="font-size: 13px; line-height: 19px; color: ' + INK_DIM + ';">' + text + '</div>',
            pad=20, gap=10, bg=PLATE_HI))

    body = root(1180, "\n".join([
        header("ui/common/Motion.kt &middot; ADR-021",
               "תנועה",
               "עד שהקובץ הזה נוצר לא הייתה תנועה בכלל — לא קריאה אחת ל&#8209;"
               "<span class=\"mono\">animate*AsState</span> באפליקציה. כל טבעת פוקוס, כל סרט שנפתח וכל "
               "שכבה שהחליפה אחרת היו חיתוך של פריים בודד. במדידה על הטלוויזיה זה צייר שלושה עד ארבעה "
               "פריימים ללחיצה על השלט, במקום חמישה&#8209;עשר עד עשרים. <em>העבירו את הסמן מעל כל אחד "
               "כדי לראות כמה זמן זה לוקח.</em>"),
        section("משכים"),
        '<div style="display: flex; gap: 24px; align-items: stretch;">\n' + "\n".join(blocks) + '\n</div>',
        section("קפיץ"),
        lift,
        section("שלושת הכללים"),
        '<div style="display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px;">\n'
        + "\n".join(rules) + '\n</div>',
    ]))
    return page("Motion — ui/common/Motion.kt", MOTION_CSS, body)


# =============================================================================
# Surfaces.dc.html — the two shapes every ten-foot surface is drawn from
# =============================================================================

SURFACES_CSS = """
    .video {
      background:
        radial-gradient(120% 90% at 18% 12%, #6E7FA8 0%, rgba(110,127,168,0) 55%),
        radial-gradient(90% 80% at 82% 78%, #B9895A 0%, rgba(185,137,90,0) 58%),
        linear-gradient(115deg, #2A3140 0%, #4A4038 55%, #1C222C 100%);
    }
    .video-caption {
      position: absolute;
      inset-block-start: 10px;
      inset-inline-start: 12px;
      font-size: 11px;
      letter-spacing: 0.04em;
      color: rgba(255,255,255,0.62);
      background: rgba(0,0,0,0.42);
      border-radius: 6px;
      padding: 3px 8px;
    }
"""

SURFACES_SCRIPT = """<script data-dc-script data-props='{
  "opacity": {"editor": "range", "default": 0.85, "min": 0.3, "max": 1, "step": 0.05, "unit": "", "section": "Overlay"},
  "$preview": {"width": 1280, "height": 1640}
}'>
class Component extends DCLogic {
  renderVals() {
    const raw = this.props.opacity;
    const opacity = typeof raw === 'number' ? raw : 0.85;
    return {
      panelFill: 'rgba(35, 42, 53, ' + opacity + ')',
      opacityLabel: opacity.toFixed(2),
    };
  }
}
</script>"""


def video_frame(inner, height=210, caption="‏[תחליף לווידאו חי]"):
    return (
        '<div class="video" style="position: relative; height: ' + str(height)
        + 'px; border-radius: 14px; overflow: hidden; display: flex; align-items: center; '
        'justify-content: center; padding: 20px; box-sizing: border-box;">\n'
        '  <div class="video-caption">' + caption + '</div>\n'
        + inner + '\n</div>'
    )


def build_surfaces():
    panel_inner = ('  <div style="width: 100%; height: 100%; border-radius: 28px; background: {{panelFill}}; '
                   'border: 1px solid rgba(233,237,243,0.12); display: flex; align-items: center; '
                   'justify-content: center; gap: 20px;">\n'
                   '    <div style="font-size: 20px; line-height: 27px; font-weight: 600; color: ' + INK
                   + ';">פאנל צף</div>\n'
                   '    <div class="mono" style="font-size: 13px; color: ' + ACCENT
                   + ';">opacity {{opacityLabel}}</div>\n'
                   '  </div>')

    fixed = []
    for value, label in (("0.30", "מינימום ההגדרה"), ("0.85", "ברירת המחדל"), ("1.00", "אטום")):
        fixed.append(
            '  <div style="display: flex; flex-direction: column; gap: 10px; flex-grow: 1;">\n'
            + video_frame(
                '  <div style="width: 100%; height: 100%; border-radius: 28px; background: rgba(35,42,53,'
                + value + '); border: 1px solid rgba(233,237,243,0.12);"></div>',
                height=132, caption="")
            + '\n    <div style="font-size: 12px; color: ' + INK_DIM + ';">'
            '<span class="mono">' + value + '</span> &middot; ' + label + '</div>\n'
            '  </div>'
        )

    states = [
        ("מנוחה", "rgba(35,42,53,0.65)", "rgba(76,141,255,0)", 3, "1.0",
         "<span class=\"mono\">surface @ 0.65</span> &middot; טבעת ב&#8209;alpha 0"),
        ("מתנגן", "#232A35", "rgba(76,141,255,0.45)", 3, "1.0",
         "<span class=\"mono\">surface</span> &middot; טבעת <span class=\"mono\">primary @ 0.45</span>"),
        ("בפוקוס", "#323A47", "#4C8DFF", 3, "1.06",
         "<span class=\"mono\">surfaceVariant</span> &middot; טבעת מלאה &middot; הרמה 1.06"),
    ]
    state_cards = []
    for label, fill, ring, border, scale, spec in states:
        state_cards.append(
            '  <div style="display: flex; flex-direction: column; gap: 12px; flex-grow: 1;">\n'
            '    <div style="height: 132px; display: flex; align-items: center; justify-content: center;">\n'
            '      <div style="width: 180px; height: 106px; border-radius: 14px; background: ' + fill
            + '; border: ' + str(border) + 'px solid ' + ring + '; transform: scale(' + scale
            + '); display: flex; align-items: flex-end; padding: 12px; box-sizing: border-box;">\n'
            '        <div style="font-size: 15px; line-height: 20px; font-weight: 500; color: ' + INK
            + ';">רשת 13</div>\n'
            '      </div>\n'
            '    </div>\n'
            '    <div style="font-size: 15px; line-height: 20px; font-weight: 500;">' + label + '</div>\n'
            '    <div style="font-size: 12px; line-height: 17px; color: ' + INK_DIM + ';">' + spec + '</div>\n'
            '  </div>'
        )

    hairline_demo = (
        '<div style="display: flex; gap: 24px;">\n'
        '  <div style="display: flex; flex-direction: column; gap: 10px; flex-grow: 1;">\n'
        '    <div style="height: 118px; border-radius: 14px; overflow: hidden; background: '
        'linear-gradient(120deg, #E8E2D4, #F4F1E8); display: flex; align-items: center; '
        'justify-content: center; padding: 18px; box-sizing: border-box;">\n'
        '      <div style="width: 100%; height: 100%; border-radius: 28px; background: rgba(35,42,53,0.7);"></div>\n'
        '    </div>\n'
        '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">בלי hairline, מעל סצנה בהירה — הפאנל מדמם לתמונה</div>\n'
        '  </div>\n'
        '  <div style="display: flex; flex-direction: column; gap: 10px; flex-grow: 1;">\n'
        '    <div style="height: 118px; border-radius: 14px; overflow: hidden; background: '
        'linear-gradient(120deg, #E8E2D4, #F4F1E8); display: flex; align-items: center; '
        'justify-content: center; padding: 18px; box-sizing: border-box;">\n'
        '      <div style="width: 100%; height: 100%; border-radius: 28px; background: rgba(35,42,53,0.7); '
        'border: 1px solid rgba(233,237,243,0.12);"></div>\n'
        '    </div>\n'
        '    <div style="font-size: 13px; line-height: 18px; color: ' + INK + ';">עם hairline — '
        '<span class="mono">onSurface @ 0.12</span></div>\n'
        '  </div>\n</div>'
    )

    body = root(1280, "\n".join([
        header("ui/tv/TvSurfaces.kt &middot; ADR-023",
               "משטחים",
               "שתי צורות לכל הקליפה של עשר הרגליים, ואין שלישית. <strong>פאנל</strong> — לוח צף "
               "שהממשק יושב עליו: מוסט מהקצוות, מעוגל ב&#8209;28dp, שקוף&#8209;למחצה לפי הגדרת הצופה "
               "(FR&#8209;UI&#8209;18), ומוקף בקו שיער. <strong>קלף</strong> — דבר אחד בר&#8209;מיקוד על פאנל: "
               "מעוגל ב&#8209;14dp ונושא שלושה מצבים שצריך להבחין ביניהם מעבר לחדר. שניהם "
               "<span class=\"mono\">surface</span> ולא <span class=\"mono\">background</span>: לוח שצף "
               "מעל וידאו הוא מורם."),
        section("פאנל &middot; שקיפות חיה"),
        video_frame(panel_inner),
        section("שלוש נקודות בסולם ההגדרה"),
        '<div style="display: flex; gap: 20px; align-items: flex-start;">\n' + "\n".join(fixed) + '\n</div>',
        note("שלושה משטחים שומרים רצפה מתחת להגדרה במקום לציית לה במלואה: תפריט הניגון "
             "(<span class=\"mono\">0.92</span>), שהוא כמה שורות של שמות רצועות שנקראות אחת&#8209;אחת; "
             "כרטיס המידע (<span class=\"mono\">0.8</span>), המשטח הנצפה ביותר באפליקציה; וקפסולת "
             "הניווט של מסך הבית (<span class=\"mono\">0.9</span>), אבל רק בזמן שהפוקוס בתוכה. "
             "במינימום של 0.3 כולם היו בלתי קריאים מעל וידאו נע, והנמכת שכבות אינה בקשה לזה."),
        section("קו השיער אינו קישוט"),
        hairline_demo,
        section("קלף &middot; שלושת המצבים"),
        '<div style="display: flex; gap: 24px; align-items: flex-start;">\n' + "\n".join(state_cards) + '\n</div>',
        note("צבע המנוחה הוא אותו צבע ב&#8209;alpha אפס, לעולם לא <span class=\"mono\">Color.Transparent</span>. "
             "שני הצבעים חוצים ולא נחתכים. <strong>עובי אחד לשלושת המצבים</strong> (ADR&#8209;025): "
             "הטבעת נהגה להתעבות בפוקוס, מה שמזיז את תוכן הקלף בתוך החיתוך ברגע הלחיצה — עכשיו רק "
             "ה&#8209;alpha משתנה. ההרמה עצמה אינה חלק מ&#8209;<span class=\"mono\">tvCard</span>: "
             "היא <span class=\"mono\">focusLift</span>, ומוחלת לפניו כדי שהגידול יקרה מחוץ לחיתוך."),
    ]))
    return page("Surfaces — ui/tv/TvSurfaces.kt", SURFACES_CSS, body, SURFACES_SCRIPT)


# =============================================================================
# Focus.dc.html
# =============================================================================

FOCUS_CSS = """
    .tile {
      appearance: none;
      -webkit-appearance: none;
      font: inherit;
      color: inherit;
      text-align: start;
      cursor: pointer;
      box-sizing: border-box;
      background: rgba(35,42,53,0.65);
      border: 3px solid rgba(76,141,255,0);
      border-radius: 14px;
      padding: 12px;
      width: 178px;
      height: 108px;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      gap: 4px;
      outline: none;
      transition: background-color 120ms cubic-bezier(0.4,0,0.2,1),
                  border-color 120ms cubic-bezier(0.4,0,0.2,1),
                  transform 260ms cubic-bezier(0.20,0.80,0.25,1.0);
    }
    .tile:hover, .tile:focus {
      background: #323A47;
      border-color: #4C8DFF;
      transform: scale(1.06);
      z-index: 1;
    }
    .tile.playing {
      background: #232A35;
      border-color: rgba(76,141,255,0.45);
    }
    .row {
      appearance: none;
      -webkit-appearance: none;
      font: inherit;
      color: inherit;
      text-align: start;
      cursor: pointer;
      width: 100%;
      box-sizing: border-box;
      background: rgba(50,58,71,0);
      display: flex;
      align-items: center;
      gap: 12px;
      outline: none;
      transition: background-color 120ms cubic-bezier(0.4,0,0.2,1),
                  border-color 120ms cubic-bezier(0.4,0,0.2,1);
    }
    .row-tv {
      border: 3px solid rgba(76,141,255,0);
      border-radius: 14px;
      padding: 7px 9px;
    }
    .row-tv:hover, .row-tv:focus {
      background: rgba(76,141,255,0.22);
      border-color: #4C8DFF;
    }
    .row-tv.selected { background: #323A47; border-color: rgba(76,141,255,0.45); }
    .row-phone {
      border: 2px solid rgba(76,141,255,0);
      border-radius: 8px;
      padding: 8px 10px;
    }
    .row-phone:hover, .row-phone:focus {
      background: rgba(76,141,255,0.22);
      border-color: #4C8DFF;
    }
    .row-phone.selected { background: #323A47; border-color: rgba(76,141,255,0.45); }
"""


# monogram(name) / monogramColour(name), evaluated. See MONOGRAM note on the
# colour sheet: the mark is one initial per word for the first two words, and
# the hue is the name's hashCode modulo 360.
MARKS = {
    "כאן 11": ("כ1", 350),
    "רשת 13": ("ר1", 41),
    "קשת 12": ("ק1", 249),
    "ספורט 5": ("ס5", 303),
    "גלי צהל": ("גצ", 333),
}


def logo_box(text, size, radius, colour):
    return ('<div style="width: ' + str(size) + 'px; height: ' + str(size) + 'px; flex: none; '
            'border-radius: ' + radius + '; background: ' + colour + '; display: flex; '
            'align-items: center; justify-content: center; font-size: ' + str(int(size * 0.36))
            + 'px; font-weight: 500; color: #FFFFFF;">' + text + '</div>')


def build_focus():
    tiles = []
    for name, sub, playing in (("כאן 11", "מהדורת חדשות", False),
                               ("רשת 13", "הזירה", True),
                               ("קשת 12", "ארץ נהדרת", False),
                               ("ספורט 5", "ליגת העל", False)):
        klass = "tile playing" if playing else "tile"
        tiles.append(
            '    <button type="button" class="' + klass + '">\n'
            '      <span style="font-size: 15px; line-height: 20px; font-weight: 500;">' + name + '</span>\n'
            '      <span style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">' + sub + '</span>\n'
            '    </button>'
        )

    def row_list(klass, logo_size, entries):
        items = []
        for name, sub, selected in entries:
            cls = klass + (" selected" if selected else "")
            mark, hue = MARKS[name]
            items.append(
                '    <button type="button" class="row ' + cls + '">\n'
                '      ' + logo_box(mark, logo_size, "6px", "hsl(" + str(hue) + ", 42%, 32%)") + '\n'
                '      <span style="display: flex; flex-direction: column; gap: 1px; min-width: 0;">\n'
                '        <span style="font-size: 15px; line-height: 20px; font-weight: 500;">' + name + '</span>\n'
                '        <span style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">' + sub + '</span>\n'
                '      </span>\n'
                '    </button>'
            )
        return '\n'.join(items)

    entries = [("כאן 11", "עכשיו: מהדורת חדשות", False),
               ("רשת 13", "עכשיו: הזירה", True),
               ("קשת 12", "עכשיו: ארץ נהדרת", False)]

    lists = (
        '<div style="display: flex; gap: 32px; align-items: flex-start;">\n'
        '  <div style="display: flex; flex-direction: column; gap: 14px; flex-grow: 1;">\n'
        '    <div style="display: flex; align-items: baseline; gap: 10px;">\n'
        '      <div style="font-size: 17px; line-height: 24px; font-weight: 500;">טלוויזיה</div>\n'
        '      <div class="mono" style="font-size: 12px; color: ' + INK_FAINT
        + ';">rowCorner 14dp &middot; ring 3dp</div>\n'
        '    </div>\n'
        '    <div style="display: flex; flex-direction: column; gap: 4px; background: ' + PLATE
        + '; border: 1px solid ' + HAIRLINE + '; border-radius: 14px; padding: 12px;">\n'
        + row_list("row-tv", 46, entries) + '\n    </div>\n  </div>\n'
        '  <div style="display: flex; flex-direction: column; gap: 14px; flex-grow: 1;">\n'
        '    <div style="display: flex; align-items: baseline; gap: 10px;">\n'
        '      <div style="font-size: 17px; line-height: 24px; font-weight: 500;">טלפון</div>\n'
        '      <div class="mono" style="font-size: 12px; color: ' + INK_FAINT
        + ';">rowCorner 8dp &middot; ring 2dp</div>\n'
        '    </div>\n'
        '    <div style="display: flex; flex-direction: column; gap: 4px; background: ' + PLATE
        + '; border: 1px solid ' + HAIRLINE + '; border-radius: 14px; padding: 12px;">\n'
        + row_list("row-phone", 36, entries) + '\n    </div>\n  </div>\n</div>'
    )

    matrix = [
        ("מנוחה", "surfaceVariant @ 0", "primary @ 0", "כל שאר השורות ברשימה"),
        ("נבחר", "surfaceVariant", "primary @ 0.45", "הערוץ שמתנגן, בלי פוקוס"),
        ("בפוקוס", "primary @ 0.22", "primary", "מה שה&#8209;D&#8209;pad עומד עליו"),
    ]

    fills = [
        ("TINT", "primary @ 0.22", "surfaceVariant", "שקוף",
         "שורות, שבבים, תאי מדריך, קפסולת הניווט"),
        ("RAISE", "surfaceVariant", "surface", "surface @ 0.65",
         "קלפים שצפים מעל וידאו"),
        ("NONE", "&mdash;", "&mdash;", "&mdash;",
         "אריחים שהיצירה שלהם היא התוכן"),
        ("FILLED", "primary", "primaryContainer", "surfaceVariant",
         "כפתורים — והמילוי היחיד בלי טבעת"),
    ]
    fill_head = [("fill", 78), ("בפוקוס", 132), ("נבחר", 132), ("מנוחה", 132)]
    fill_rows = [
        '  <div style="display: flex; align-items: center; gap: 14px; padding-bottom: 10px; '
        'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
        + "".join(
            '    <div style="width: ' + str(w) + 'px; flex: none; font-size: 13px; '
            'font-weight: 500; color: ' + INK_DIM + ';">' + label + '</div>\n'
            for label, w in fill_head
        )
        + '    <div style="font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">למי</div>\n'
        '  </div>'
    ]
    for name, f_focus, f_sel, f_rest, who in fills:
        fill_rows.append(
            '  <div style="display: flex; align-items: center; gap: 14px; padding: 10px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div class="mono" style="width: 78px; flex: none; font-size: 12px; '
            'font-weight: 500; color: ' + INK + ';">' + name + '</div>\n'
            '    <div class="mono" style="width: 132px; flex: none; font-size: 12px; color: '
            + ACCENT + ';">' + f_focus + '</div>\n'
            '    <div class="mono" style="width: 132px; flex: none; font-size: 12px; color: '
            + ACCENT_SOFT + ';">' + f_sel + '</div>\n'
            '    <div class="mono" style="width: 132px; flex: none; font-size: 12px; color: '
            + INK_FAINT + ';">' + f_rest + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">'
            + who + '</div>\n'
            '  </div>'
        )

    matrix_rows = [
        '  <div style="display: flex; align-items: center; gap: 16px; padding-bottom: 10px; '
        'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
        '    <div style="width: 90px; flex: none; font-size: 13px; font-weight: 500; color: '
        + INK_DIM + ';">מצב</div>\n'
        '    <div style="width: 150px; flex: none; font-size: 13px; font-weight: 500; color: '
        + INK_DIM + ';">מילוי</div>\n'
        '    <div style="width: 150px; flex: none; font-size: 13px; font-weight: 500; color: '
        + INK_DIM + ';">טבעת</div>\n'
        '    <div style="font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">מתי</div>\n'
        '  </div>'
    ]
    for state, fill, ring, text in matrix:
        matrix_rows.append(
            '  <div style="display: flex; align-items: center; gap: 16px; padding: 10px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div style="width: 90px; flex: none; font-size: 14px; line-height: 19px; font-weight: 500;">'
            + state + '</div>\n'
            '    <div class="mono" style="width: 150px; flex: none; font-size: 12px; color: ' + ACCENT_SOFT + ';">'
            + fill + '</div>\n'
            '    <div class="mono" style="width: 150px; flex: none; font-size: 12px; color: ' + ACCENT + ';">'
            + ring + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">' + text + '</div>\n'
            '  </div>'
        )

    body = root(1180, "\n".join([
        header("ui/common/Components.kt &middot; ui/common/TvMetrics.kt &middot; NFR-UX-02",
               "פוקוס",
               "כל רשימה באפליקציה בנויה מ&#8209;<span class=\"mono\">FocusableRow</span> אחד, ולכן "
               "הקריאה הזו היא רוב תחושת הפוקוס בממשק. שתי מערכות מספרים יוצאות ממנה: טלפון "
               "נקרא ממרחק זרוע, טלוויזיה משלושה מטרים — שם פינה של 8dp וטבעת של 2dp הן מלבן עם קו "
               "חיוור. <em>לחצו או השתמשו ב&#8209;Tab כדי להזיז את הפוקוס.</em>"),
        note("<strong style=\"color: #FF6B6B;\">ADR&#8209;025:</strong> עד שהטיפול המשותף נכתב "
             "היו באפליקציה <strong>שבעה</strong> טיפולי פוקוס — חמישה מילויים שונים ושלושה עוביי "
             "טבעת על אותה טלוויזיה באותו רגע. שניים מהם גם הרחיבו את הטבעת בפוקוס, מה שמזיז את "
             "התוכן בתוך החיתוך בדיוק ברגע הלחיצה, ונחו על <span class=\"mono\">Color.Transparent</span>. "
             "הכול עובר עכשיו דרך <span class=\"mono\">Modifier.focusSurface</span> אחד."),
        section("קלפים &middot; tvCard + focusLift"),
        '<div style="background: ' + PLATE + '; border: 1px solid ' + HAIRLINE + '; border-radius: 28px; '
        'padding: 28px;">\n  <div style="display: flex; gap: 18px;">\n' + "\n".join(tiles)
        + '\n  </div>\n</div>',
        section("שורות &middot; FocusableRow בשתי הקליפות"),
        lists,
        section("ארבעת המילויים &middot; FocusFill"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(fill_rows) + '\n</div>',
        note("הטבעת זהה בשלושה מהם — אותו עובי מ&#8209;<span class=\"mono\">LocalTvMetrics</span>, "
             "אותם צבעים, וחצייה במקום חיתוך — וזה מה שעושה מהם אוצר מילים אחד ולא ארבעה. "
             "<span class=\"mono\">FILLED</span> הוא היחיד בלי טבעת: כפתור אומר שהוא בפוקוס בכך "
             "שהוא <em>הופך</em> לצבע ההדגשה, וטבעת סביב זה היא תשובה שנייה לשאלה שכבר נענתה."),
        section("מטריצת המצבים של FocusableRow"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(matrix_rows) + '\n</div>',
        note("<span class=\"mono\">onFocusChanged</span> קורא <span class=\"mono\">hasFocus</span> ולא "
             "<span class=\"mono\">isFocused</span>: שורות נושאות פקדים משלהן — מתג המועדף — וצעד "
             "D&#8209;pad על אחד מהם לא אמור להיראות כיציאה מהשורה. הפינה והטבעת <em>לא</em> נגזרות מקנה המידה, "
             "וטלוויזיית 4K רוצה את אותה פינה ואת אותה טבעת כמו 1080p, כי הספה לא זזה."),
    ]))
    return page("Focus — FocusableRow and tvCard states", FOCUS_CSS, body)


# =============================================================================
# Components.dc.html
# =============================================================================

def spec(text):
    return ('<div class="mono" style="font-size: 11px; line-height: 16px; color: ' + INK_FAINT
            + '; direction: ltr; text-align: right;">' + text + '</div>')


def component_card(title, demo, spec_text, prose):
    return card(
        '<div style="font-size: 16px; line-height: 22px; font-weight: 500;">' + title + '</div>\n'
        '<div style="min-height: 84px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap;">\n'
        + demo + '\n</div>\n' + spec(spec_text) + '\n'
        '<div style="font-size: 13px; line-height: 19px; color: ' + INK_DIM + ';">' + prose + '</div>',
        pad=22, gap=14)


def build_components():
    live = ('<div style="display: flex; align-items: center; gap: 8px; background: rgba(0,0,0,0.72); '
            'border-radius: 16px; padding: 6px 14px;">'
            '<div style="width: 8px; height: 8px; border-radius: 50%; background: #FF6B6B;"></div>'
            '<div style="font-size: 13px; line-height: 17px; font-weight: 500; color: #FFFFFF;">שידור חי</div>'
            '</div>')

    logos = (logo_box("כ1", 56, "6px", "hsl(350, 42%, 32%)") + "\n"
             + '<div style="width: 56px; height: 56px; flex: none; border-radius: 50%; background: '
               'hsl(333, 42%, 32%); display: flex; align-items: center; justify-content: center;">'
               '<div style="font-size: 20px; font-weight: 500; color: #FFFFFF; padding: 0 6.7px;">גצ</div></div>' + "\n"
             + logo_box("ר1", 36, "6px", "hsl(41, 42%, 32%)"))

    badges = ('<div style="display: flex; gap: 10px; align-items: center;">'
              '<div style="border-radius: 4px; background: #4C8DFF; color: #00122E; font-size: 12px; '
              'line-height: 16px; font-weight: 500; padding: 2px 6px;">HD</div>'
              '<div style="border-radius: 4px; background: #FF6B6B; color: #00122E; font-size: 12px; '
              'line-height: 16px; font-weight: 500; padding: 2px 6px;">הקלטה</div>'
              '<div style="border-radius: 4px; background: #9CC2FF; color: #00122E; font-size: 12px; '
              'line-height: 16px; font-weight: 500; padding: 2px 6px;">4K</div>'
              '</div>')

    header_demo = ('<div style="width: 100%; display: flex; flex-direction: column;">'
                   '<div style="height: 1px; background: rgba(233,237,243,0.12); margin-top: 12px;"></div>'
                   '<div style="font-size: 15px; line-height: 20px; font-weight: 500; color: ' + INK_DIM
                   + '; padding: 8px 12px;">ערוצי חדשות</div>'
                   '</div>')

    empty = ('<div style="width: 100%; padding: 32px; box-sizing: border-box; text-align: center; '
             'font-size: 16px; line-height: 23px; color: ' + INK_DIM + ';">אין ערוצים ברשימה הזו</div>')

    progress = ('<div style="width: 100%; display: flex; flex-direction: column; gap: 8px;">'
                '<div style="height: 4px; border-radius: 2px; background: #323A47; overflow: hidden;">'
                '<div style="width: 62%; height: 100%; background: #4C8DFF;"></div></div>'
                '<div style="display: flex; justify-content: space-between; font-size: 13px; '
                'line-height: 18px; color: ' + INK_DIM + ';"><span>20:00</span><span>20:45</span></div>'
                '</div>')

    two_line = ('<div style="width: 100%; display: flex; align-items: center; gap: 12px;">'
                + logo_box("כ1", 36, "6px", "hsl(350, 42%, 32%)")
                + '<div style="display: flex; flex-direction: column; gap: 1px; min-width: 0;">'
                  '<div style="font-size: 15px; line-height: 20px; font-weight: 500; color: ' + INK + ';">כאן 11</div>'
                  '<div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">עכשיו: מהדורת חדשות</div>'
                  '</div></div>')

    clock = ('<div style="font-size: 17px; line-height: 24px; font-weight: 500; color: ' + INK
             + ';">21:47</div>')

    cards = [
        component_card(
            "LiveBadge", live,
            "corner 16dp &middot; plate #000 @ 0.72 &middot; padding 14/6 &middot; dot 8dp error &middot; labelMedium",
            "תג אחד, ומאחוריו כלל אחד: חי <em>וגם</em> לא מושהה. שלושת המשטחים שנושאים אותו — הרקע של "
            "התחנה, כרטיס המידע ופס הניגון — כולם שורדים השהיה, ולכן הם לא יכולים לענות על השאלה כל אחד "
            "בנפרד."),
        component_card(
            "ChannelLogo", logos,
            "square corner 6dp &middot; circle inset 12% &middot; default 44dp &middot; 32/36/40/44/48/56",
            "עגול רק כשהיצירה היא הסמל של התחנה עצמה — יצירה של פיד או אייקון תוכנית בתוך דיסקה היא "
            "תמונה עם הקצוות חתוכים. ההסטה של 12% קטנה בכוונה מהחיתוך המלא, שהיה עולה כ&#8209;15% מכל צד: מונוגרם ממורכז יושב שם "
            "שהדיסקה עדיין רחבה."),
        component_card(
            "Badge", badges,
            "corner 4dp &middot; padding 6/2 &middot; labelSmall &middot; background from caller",
            "תגית טקסט קצרה בתוך שורה. הרקע מגיע מאתר הקריאה, כי היא מסמנת דברים שונים — איכות, מצב "
            "הקלטה, שפה. <strong style=\"color: #FF6B6B;\">להכרעה:</strong> צבע הטקסט קבוע ל&#8209;"
            "<span class=\"mono\">onPrimary</span> ואינו נגזר מהרקע, כך שתגית על "
            "<span class=\"mono\">error</span> מקבלת טקסט כחול&#8209;כהה. כאן מצויר מה שהקוד עושה."),
        component_card(
            "SectionHeader", header_demo,
            "divider outlineVariant &middot; padding 12/8 &middot; labelLarge &middot; onSurfaceVariant",
            "מראה אחד לכותרת קטגוריה בכל רשימה ובכל תפריט. גם ב&#8209;Settings — "
            "<span class=\"mono\">SettingsSectionTitle</span> הוא עטיפה דקה מעל זה ולא הגדרה שנייה."),
        component_card(
            "ProgrammeProgress", progress,
            "LinearProgressIndicator &middot; M3 defaults",
            "עכשיו/הבא לכרטיס המידע ולשורות הערוצים, נגזר מזמני ההתחלה והסיום ב&#8209;UTC. הרכיב לא "
            "בוחר גובה ולא בוחר צבעים — הוא מעביר שבר ל&#8209;<span class=\"mono\">LinearProgressIndicator</span> "
            "ומקבל את ברירות המחדל של Material 3."),
        component_card(
            "TwoLine", two_line,
            "Column &middot; titleSmall + bodySmall",
            "שתי השורות עצמן, ותו לא: הלוגו והמרווח של 12dp שמסביבן הם של "
            "<span class=\"mono\">FocusableRow</span> ושל אתר הקריאה. זה הגוף של כמעט כל שורה "
            "ברשימות — שם, ומה שמתנגן עליו עכשיו."),
        component_card(
            "ClockText", clock,
            "titleMedium &middot; onSurface &middot; tick 20s",
            "מתעורר כל עשרים שניות, הרבה מתחת לדקה שהוא מציג, כך שהדקה על המסך אף פעם לא בת יותר "
            "מרגע — ונדיר מכדי לעלות משהו. הזמן עצמו נעטף ב&#8209;<span class=\"mono\">TimeModel.ltr</span> "
            "כדי שהנקודתיים לא יתהפכו בתוך שורה בעברית."),
        component_card(
            "EmptyState", empty,
            "padding 32dp &middot; bodyLarge &middot; onSurfaceVariant &middot; centred",
            "הריפוד הגדול ביותר שמשטח באפליקציה נותן לטקסט. מסך ריק זה מה שהוא, וריפוד קטן יותר "
            "גורם לו להיראות כתקלה."),
    ]

    body = root(1180, "\n".join([
        header("ui/common/Components.kt",
               "רכיבים משותפים",
               "מה ששתי הקליפות — טלוויזיה וטלפון — מציירות מאותו קובץ. כל אחד מהם הופיע כאן בדיוק ברגע "
               "שהוא נכתב פעמיים בשני מקומות."),
        '<div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px;">\n'
        + "\n".join(cards) + '\n</div>',
    ]))
    return page("Components — ui/common/Components.kt", "", body)


# =============================================================================
# Metrics.dc.html
# =============================================================================

METRICS = [
    ("guideHourWidth", "240dp", "204&ndash;384dp", "רוחב שעה אחת בציר הזמן של המדריך"),
    ("guideRowHeight", "64dp", "56&ndash;112dp", "גובה שורה במדריך — נגזר מהגובה, לא מהרוחב"),
    ("guideChannelColumnWidth", "190dp", "161.5&ndash;304dp", "עמודת הערוצים שמימין לרשת"),
    ("groupColumnWidth", "220dp", "187&ndash;352dp", "עמודת הקבוצות ברשימת הערוצים"),
    ("nowNextPaneWidth", "280dp", "238&ndash;448dp", "חלונית עכשיו/הבא"),
    ("rowLogoSize", "36", "30&ndash;57", "צלע הלוגו בשורת רשימה"),
    ("homeNavRailWidth", "168dp", "142.8&ndash;268.8dp", "רוחב פס הניווט כשהוא מציג תוויות"),
    ("homeNavRailCollapsedWidth", "64dp", "54.4&ndash;102.4dp", "אותו פס מכווץ לאייקונים"),
    ("homeHeroHeight", "320dp", "160&ndash;560dp", "גובה הבאנר לפני השורה הראשונה"),
    ("homeCardWidth", "180dp", "153&ndash;288dp", "רוחב קלף בשורת הבית"),
    ("rowCorner", "8dp", "14dp", "פינת שורה ממוקדת — <strong>קבוע, לא נגזר מהסקאלה</strong>"),
    ("focusRingWidth", "2dp", "3dp", "עובי טבעת הפוקוס — <strong>קבוע, לא נגזר מהסקאלה</strong>"),
]


def build_metrics():
    rows = []
    for token, phone, tv, what in METRICS:
        rows.append(
            '  <div style="display: flex; align-items: center; gap: 16px; padding: 10px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div class="mono" style="width: 262px; flex: none; font-size: 12px; font-weight: 500; color: '
            + INK + ';">' + token + '</div>\n'
            '    <div class="mono" style="width: 84px; flex: none; font-size: 12px; color: ' + ACCENT_SOFT + ';">'
            + phone + '</div>\n'
            '    <div class="mono" style="width: 132px; flex: none; font-size: 12px; color: ' + ACCENT + ';">'
            + tv + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">' + what + '</div>\n'
            '  </div>'
        )

    rules = []
    for title, value, text in (
        ("סקאלת הרוחב", "&times;0.85 &ndash; &times;1.6",
         "<span class=\"mono\">widthDp / 960</span>, חסום משני הצדדים. 960dp הוא מה שטלוויזיית 1080p "
         "מדווחת, ולכן פאנל 1080p יוצא ללא שינוי. חסום ולא פרופורציונלי בלי גבול: פאנל 4K שמדווח "
         "1920dp היה מכפיל כל מידה, והצופה לא יושב פי שניים רחוק — מעבר לנקודה מסוימת הפיקסלים "
         "הנוספים צריכים לקנות יותר תוכן, לא תוכן גדול יותר."),
        ("שורות המדריך", "7",
         "גובה השורה נגזר מהגובה הזמין חלקי שבע, ולא מהסקאלה. טלוויזיה שמכניסה שתים&#8209;עשרה שורות "
         "של 64dp מציגה טקסט שאיש בחדר לא קורא. זה גם מה שגורם לחלון השאילתה של המדריך לבקש בדיוק את "
         "השורות שנמצאות על המסך, ולא יותר."),
        ("גובה הבאנר", "58%",
         "חלק מהגובה, ואז חסום פעמיים: לעולם לא כל כך נמוך שהבאנר מפסיק להיות המסך, ולעולם לא כל כך "
         "גבוה ששורת הקלפים הראשונה יורדת כולה מתחת לקו — מסך בית ששורותיו בלתי נראות עד שלוחצים "
         "למטה הוא מסך שאיש לא גולל. התקרה השנייה היא בדיוק זה: הגובה פחות 150dp, שהם ההצצה "
         "לשורה הראשונה שחייבת להישאר על המסך."),
    ):
        rules.append(card(
            '<div style="display: flex; align-items: baseline; gap: 10px;">\n'
            '  <div style="font-size: 16px; line-height: 22px; font-weight: 500;">' + title + '</div>\n'
            '  <div class="mono" style="font-size: 15px; font-weight: 600; color: ' + ACCENT + ';">'
            + value + '</div>\n</div>\n'
            '<div style="font-size: 13px; line-height: 19px; color: ' + INK_DIM + ';">' + text + '</div>',
            pad=22, gap=12, bg=PLATE_HI))

    head = (
        '  <div style="display: flex; align-items: center; gap: 16px; padding-bottom: 10px; '
        'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
        '    <div style="width: 262px; flex: none; font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">טוקן</div>\n'
        '    <div style="width: 84px; flex: none; font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">טלפון</div>\n'
        '    <div style="width: 132px; flex: none; font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">טלוויזיה</div>\n'
        '    <div style="font-size: 13px; font-weight: 500; color: ' + INK_DIM + ';">מה זה</div>\n'
        '  </div>'
    )

    body = root(1080, "\n".join([
        header("ui/common/TvMetrics.kt",
               "מידות עשר הרגליים",
               "כל אחת מהמידות האלה הייתה קבועה בזמן קומפילציה, משותפת לשני מכשירי הקצה, וזו הסיבה שקליפת עשר "
               "הרגליים נראתה אותו דבר על מסך 24 אינץ' ועל פאנל 65 אינץ'. עכשיו הן נגזרות מהחלון, ולכן "
               "המדריך מקבל שעות רחבות יותר ושורות גבוהות יותר על מסך גדול — במקום עוד שורות באותו גודל. הטלפון לא נגע בזה: "
               "<span class=\"mono\">LocalTvMetrics</span> מוגדר כברירת מחדל ל&#8209;"
               "<span class=\"mono\">TvMetrics.Phone</span>, שהוא בדיוק הקבועים הישנים."),
        section("הטבלה &middot; טלפון קבוע, טלוויזיה בטווח החסום"),
        '<div style="display: flex; flex-direction: column;">\n' + head + "\n" + "\n".join(rows) + '\n</div>',
        section("שלוש נגזרות"),
        '<div style="display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px;">\n'
        + "\n".join(rules) + '\n</div>',
    ]))
    return page("Ten-foot metrics — ui/common/TvMetrics.kt", "", body)


# =============================================================================
# Menus.dc.html — the plate every menu is drawn on
# =============================================================================

MENUS_CSS = """
    .stage {
      background:
        radial-gradient(120% 90% at 20% 15%, #6E7FA8 0%, rgba(110,127,168,0) 55%),
        radial-gradient(90% 80% at 80% 80%, #B9895A 0%, rgba(185,137,90,0) 58%),
        linear-gradient(115deg, #2A3140 0%, #4A4038 55%, #1C222C 100%);
    }
"""


def menu_rows(entries):
    out = []
    for label, detail, selected in entries:
        tick = "&#10003;" if selected else "&nbsp;"
        fill = "rgba(50,58,71,1)" if selected else "transparent"
        ring = "rgba(76,141,255,0.45)" if selected else "rgba(76,141,255,0)"
        out.append(
            '    <div style="display: flex; align-items: center; gap: 12px; border-radius: 14px; '
            'background: ' + fill + '; border: 3px solid ' + ring + '; padding: 7px 9px;">\n'
            '      <div class="mono" style="width: 20px; flex: none; font-size: 15px; color: '
            + ACCENT + ';">' + tick + '</div>\n'
            '      <div style="font-size: 15px; line-height: 20px; font-weight: 500; color: ' + INK
            + '; flex-grow: 1;">' + label + '</div>\n'
            '      <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">'
            + detail + '</div>\n'
            '    </div>'
        )
    return "\n".join(out)


def dialog_plate(body, opaque):
    """The menu plate, drawn as it is now and as Material would have drawn it."""
    if opaque:
        plate = ('background: #2D3542; border-radius: 28px; border: 0;')
    else:
        plate = ('background: rgba(35,42,53,0.92); border-radius: 28px; '
                 'border: 1px solid ' + HAIRLINE + ';')
    return ('  <div style="width: 340px; ' + plate + ' padding: 22px; display: flex; '
            'flex-direction: column; gap: 14px;">\n' + body + '\n  </div>')


def build_menus():
    entries = [
        ("צפה", "", False),
        ("הוסף למועדפים", "", False),
        ("ערוך ערוץ", "", False),
        ("הסתר", "", False),
    ]
    dialog_body = (
        '    <div style="font-size: 22px; line-height: 30px; font-weight: 600; color: ' + INK
        + ';">כאן 11</div>\n'
        '    <div style="display: flex; flex-direction: column; gap: 4px;">\n'
        + menu_rows(entries) + '\n    </div>\n'
        '    <div style="display: flex; justify-content: flex-start;">\n'
        '      <div style="font-size: 15px; line-height: 20px; font-weight: 500; color: '
        + ACCENT + '; padding: 8px 12px;">סגור</div>\n'
        '    </div>'
    )

    comparison = (
        '<div style="display: flex; gap: 28px; align-items: flex-start;">\n'
        '  <div style="display: flex; flex-direction: column; gap: 12px;">\n'
        '    <div class="stage" style="border-radius: 14px; padding: 26px; display: flex; '
        'justify-content: center;">\n' + dialog_plate(dialog_body, opaque=False) + '\n    </div>\n'
        + '    <div style="font-size: 14px; line-height: 20px; font-weight: 500; color: ' + INK
        + ';">AppDialog — הפאנל של האפליקציה</div>\n'
        + note("פינה 28dp מהרמפה, <span class=\"mono\">surface</span> בשקיפות של הצופה עם רצפה של "
               "<span class=\"mono\">0.92</span>, וקו שיער. אותו לוח בדיוק שפס הניגון ותפריט הניגון "
               "יושבים עליו.")
        + '\n  </div>\n'
        '  <div style="display: flex; flex-direction: column; gap: 12px;">\n'
        '    <div class="stage" style="border-radius: 14px; padding: 26px; display: flex; '
        'justify-content: center;">\n' + dialog_plate(dialog_body, opaque=True) + '\n    </div>\n'
        + '    <div style="font-size: 14px; line-height: 20px; font-weight: 500; color: ' + INK_FAINT
        + ';">AlertDialog — מה שהיה עד ADR&#8209;025</div>\n'
        + note("<span class=\"mono\">surfaceContainerHigh</span> אטום, בלי קצה, ובלי שום קשר להגדרת "
               "השקיפות. זו הייתה התלונה שבה ADR&#8209;023 נפתח על תפריט הניגון — והיא נשארה עומדת "
               "בשנים&#8209;עשר המקומות שבהם הוא לא הסתכל.")
        + '\n  </div>\n</div>'
    )

    swapped = [
        ("ChannelMenu", "7", "תפריט הערוץ, עריכה, מתקדם, בחירת EPG, קבוצה, ניהול קבוצות, שם"),
        ("ProgrammeDetails", "1", "פרטי תוכנית, הקלטה וצפייה בארכיון"),
        ("UpdatePrompt", "4", "הצעת עדכון, התקדמות, שגיאה, חסם התקנה"),
    ]
    swap_rows = []
    for name, count, what in swapped:
        swap_rows.append(
            '  <div style="display: flex; align-items: center; gap: 16px; padding: 10px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div class="mono" style="width: 180px; flex: none; font-size: 12px; '
            'font-weight: 500; color: ' + INK + ';">' + name + '</div>\n'
            '    <div class="mono" style="width: 34px; flex: none; font-size: 13px; '
            'font-weight: 600; color: ' + ACCENT + ';">' + count + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">'
            + what + '</div>\n'
            '  </div>'
        )

    floors = [
        ("תפריט הניגון", "0.92", "כמה שורות של שמות רצועות שנקראות אחת&#8209;אחת"),
        ("כל תפריט אחר", "0.92", "אותה רצפה, אותו טעם — הן אותו דבר"),
        ("כרטיס המידע", "0.80", "המשטח הנצפה ביותר באפליקציה"),
        ("קפסולת הניווט", "0.90", "רק בזמן שהפוקוס בתוכה"),
    ]
    floor_rows = []
    for what, value, why in floors:
        floor_rows.append(
            '  <div style="display: flex; align-items: center; gap: 16px; padding: 10px 0; '
            'border-bottom: 1px solid ' + HAIRLINE + ';">\n'
            '    <div style="width: 160px; flex: none; font-size: 14px; line-height: 19px; '
            'font-weight: 500; color: ' + INK + ';">' + what + '</div>\n'
            '    <div class="mono" style="width: 54px; flex: none; font-size: 13px; color: '
            + ACCENT + ';">' + value + '</div>\n'
            '    <div style="font-size: 13px; line-height: 18px; color: ' + INK_DIM + ';">'
            + why + '</div>\n'
            '  </div>'
        )

    body = root(1180, "\n".join([
        header("ui/common/Panel.kt &middot; ADR-025",
               "תפריטים",
               "שנים&#8209;עשר תפריטים באפליקציה היו <span class=\"mono\">AlertDialog</span> סטנדרטי "
               "של Material. צופה שלוחץ לחיצה ארוכה על ערוץ וצופה שלוחץ OK על פס הניגון נמצאים "
               "לחיצה אחת זה מזה — והם ראו שתי אפליקציות שונות. הלוח הצף עבר מ&#8209;"
               "<span class=\"mono\">ui/tv</span> ל&#8209;<span class=\"mono\">ui/common</span>, "
               "ששם שתי הקליפות מגיעות אליו."),
        section("אותו תוכן, שני לוחות"),
        comparison,
        section("מה הוחלף"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(swap_rows) + '\n</div>',
        section("רצפות שקיפות"),
        '<div style="display: flex; flex-direction: column;">\n' + "\n".join(floor_rows) + '\n</div>',
        note("הרצפה של התפריטים הייתה קבוע פרטי בתוך <span class=\"mono\">PlaybackControls.kt</span>. "
             "עכשיו היא <span class=\"mono\">MENU_MIN_OPACITY</span> אחד, ותפריט הניגון קורא אותו "
             "מאותו מקום שכל תפריט אחר קורא."),
    ]))
    return page("Menus — ui/common/Panel.kt", MENUS_CSS, body)



# =============================================================================
# canvas.json and the entry point
# =============================================================================

CANVAS = {
    "artboards": [
        {"file": "Main.dc.html",        "x": 0,    "y": 0,    "w": 1280, "h": 1600,
         "title": "צבע", "page": "page-1"},
        {"file": "Typography.dc.html",  "x": 1380, "y": 0,    "w": 1060, "h": 1750,
         "title": "טיפוגרפיה", "page": "page-1"},
        {"file": "Shape.dc.html",       "x": 0,    "y": 1890, "w": 1060, "h": 1470,
         "title": "צורה ומרווח", "page": "page-1"},
        {"file": "Motion.dc.html",      "x": 1160, "y": 1890, "w": 1180, "h": 1350,
         "title": "תנועה", "page": "page-1"},

        {"file": "Surfaces.dc.html",    "x": 0,    "y": 0,    "w": 1280, "h": 1720,
         "title": "משטחים", "page": "page-2", "is_interactive": True},
        {"file": "Focus.dc.html",       "x": 1380, "y": 0,    "w": 1180, "h": 1810,
         "title": "פוקוס", "page": "page-2", "is_interactive": True},
        {"file": "Components.dc.html",  "x": 0,    "y": 1950, "w": 1180, "h": 1500,
         "title": "רכיבים", "page": "page-2"},
        {"file": "Metrics.dc.html",     "x": 1280, "y": 1950, "w": 1080, "h": 1240,
         "title": "מידות", "page": "page-2"},
        {"file": "Menus.dc.html",       "x": 0,    "y": 3590, "w": 1180, "h": 1450,
         "title": "תפריטים", "page": "page-2"},
    ],
    "annotations": [
        {"id": "about", "x": 0, "y": -280, "w": 760, "page": "page-1",
         "text": "מערכת העיצוב של Shidur.\n\n"
                 "כל ערך כאן הוא מה שבקוד, לא הצעה: הצבעים מ-Theme.kt, הרמפה מ-Type.kt, "
                 "שתי הצורות מ-TvSurfaces.kt, המשכים מ-Motion.kt והמידות מ-TvMetrics.kt.\n\n"
                 "מקור האמת נשאר הקוד. אם משנים ערך כאן, זו הצעה עד שהיא נוחתת שם."},
        {"id": "shells", "x": 0, "y": -280, "w": 760, "page": "page-2",
         "text": "שתי קליפות, מערכת אחת.\n\n"
                 "TvRoot ו-MobileRoot חולקות AppViewModel אחד ואת אותם רכיבים; מה שמפריד ביניהן "
                 "הוא LocalTvMetrics, שברירת המחדל שלו היא TvMetrics.Phone.\n\n"
                 "שינוי במשהו משותף צריך בדיקה מול שתיהן."},
    ],
    "pages": [
        {"id": "page-1", "name": "יסודות"},
        {"id": "page-2", "name": "רכיבים ומצבים"},
    ],
    "launch": {"view": "canvas", "page": "page-1"},
}


def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    write("Main.dc.html", build_colour())
    write("Typography.dc.html", build_typography())
    write("Shape.dc.html", build_shape())
    write("Motion.dc.html", build_motion())
    write("Surfaces.dc.html", build_surfaces())
    write("Focus.dc.html", build_focus())
    write("Components.dc.html", build_components())
    write("Metrics.dc.html", build_metrics())
    write("Menus.dc.html", build_menus())
    with open("canvas.json", "w", encoding="utf-8") as handle:
        json.dump(CANVAS, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("wrote canvas.json")


if __name__ == "__main__":
    main()
