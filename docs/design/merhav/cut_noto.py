"""Cut static Noto Sans Hebrew weights from Google's variable font (ADR-019's method, ADR-046).

Usage: python3 -I cut_noto.py <NotoSansHebrew[wdth,wght].ttf> <out_dir>
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

SRC, OUT = sys.argv[1], sys.argv[2]
WEIGHTS = {400: "regular", 500: "medium", 600: "semibold", 700: "bold", 800: "extrabold"}

# Latin (Basic, Latin-1, Extended-A, the few Extended Additional letters the font has),
# combining marks, Hebrew (with points and cantillation) and its presentation forms,
# general punctuation, the shekel and euro signs, trade mark, minus, and the dotted circle
# that shapers draw a lone mark on.
UNICODES = (
    list(range(0x0020, 0x007F))
    + list(range(0x00A0, 0x0180))
    + list(range(0x0218, 0x021C)) + [0x0237]
    + list(range(0x02C6, 0x02DE))
    + list(range(0x0300, 0x0370))
    + list(range(0x0590, 0x0600))
    + list(range(0x1E80, 0x1E86)) + [0x1E9E, 0x1EF2, 0x1EF3]
    + list(range(0x2000, 0x2070))
    + [0x20AA, 0x20AC, 0x2122, 0x2212, 0x25CC]
    + list(range(0xFB1D, 0xFB50))
)

for weight, name in WEIGHTS.items():
    font = TTFont(SRC)
    static = instancer.instantiateVariableFont(font, {"wght": weight, "wdth": 100}, updateFontNames=True)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.glyph_names = False
    options.hinting = True
    options.drop_tables += ["STAT"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(static)
    static["OS/2"].usWeightClass = weight
    path = f"{OUT}/noto_sans_hebrew_{name}.ttf"
    static.save(path)
    check = TTFont(path)
    print(path, check["OS/2"].usWeightClass, check["name"].getDebugName(4), "fvar" in check, len(check.getGlyphOrder()))
