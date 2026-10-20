#!/usr/bin/env python3
# Usage: scripts/android-glyphs.py <NotoSans[wdth,wght].ttf> android/app/src/main/res   (needs: pip install fonttools)
# Writes the launcher icon, TV banner and splash mark as vector drawables from Noto Sans Black outlines.
import sys, os
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

font_path, res = sys.argv[1], sys.argv[2]
f = instantiateVariableFont(TTFont(font_path), {"wght": 900, "wdth": 100})
gs, cmap, upm = f.getGlyphSet(), f.getBestCmap(), f['head'].unitsPerEm

def text_path(text, size, x0, baseline, tracking=0.0):
    """SVG path for text at font size `size` (dp), pen starting at x0 with the given baseline (y down)."""
    pen = SVGPathPen(gs); x = x0; s = size / upm
    for ch in text:
        g = cmap[ord(ch)]
        tp = TransformPen(pen, (s, 0, 0, -s, x, baseline))
        gs[g].draw(tp)
        x += gs[g].width * s + tracking * size
    return pen.getCommands(), x - tracking * size

def bounds(ch, size):
    bp = BoundsPen(gs); gs[cmap[ord(ch)]].draw(bp); xmin, ymin, xmax, ymax = bp.bounds; s = size / upm
    return xmin * s, ymin * s, xmax * s, ymax * s

def centered_glyph(ch, size, cx, cy):
    xmin, ymin, xmax, ymax = bounds(ch, size)
    x0 = cx - (xmin + xmax) / 2; baseline = cy + (ymin + ymax) / 2
    return text_path(ch, size, x0, baseline)[0]

def write(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write(body)

def vector(w, h, vw, vh, paths):
    items = "\n".join(f'    <path android:fillColor="{c}" android:pathData="{d}" />' for c, d in paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from Noto Sans Black (scripts/android-glyphs.py). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{vw}" android:viewportHeight="{vh}">
{items}
</vector>
'''

def rrect(x, y, w, h, r):
    return f"M{x+r},{y} H{x+w-r} A{r},{r} 0 0 1 {x+w},{y+r} V{y+h-r} A{r},{r} 0 0 1 {x+w-r},{y+h} H{x+r} A{r},{r} 0 0 1 {x},{y+h-r} V{y+r} A{r},{r} 0 0 1 {x+r},{y} Z"

INK, MARK, BG, TEXT = "#1E1E1E", "#F0F0F0", "#0E0E0E", "#FFFFFF"

# Adaptive launcher icon: light background, dark S inside the 66dp safe zone (boards: 30px mark, 16px S → ~53% of the mark).
write(f"{res}/drawable/ic_launcher_foreground.xml", vector(108, 108, 108, 108, [(INK, centered_glyph("S", 58, 54, 54))]))
write(f"{res}/drawable/ic_launcher_monochrome.xml", vector(108, 108, 108, 108, [("#FFFFFF", centered_glyph("S", 58, 54, 54))]))
write(f"{res}/mipmap-anydpi/ic_launcher.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/seedy_inverse" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')

# Splash: the mark (rounded square + S) inside the 160dp splash circle, kept within its inner 2/3.
write(f"{res}/drawable/splash_mark.xml", vector(160, 160, 160, 160, [(MARK, rrect(50, 50, 60, 60, 18)), (INK, centered_glyph("S", 33, 80, 80))]))

# TV banner 320x180dp: dark background, mark + "Seedy" wordmark as on the TV boards (mark 40, gap 14, wordmark 900).
mark, gap, size = 56, 18, 40
word_d, word_end = text_path("Seedy", size, 0, 0, tracking=-0.01)
word_w = word_end
total = mark + gap + word_w
x0 = (320 - total) / 2; y0 = (180 - mark) / 2
xmin, ymin, xmax, ymax = bounds("S", size)  # cap height for vertical centring of the wordmark
baseline = 90 + (ymax - 0) / 2
word_d, _ = text_path("Seedy", size, x0 + mark + gap, baseline, tracking=-0.01)
write(f"{res}/drawable/banner.xml", vector(320, 180, 320, 180, [
    (BG, "M0,0 H320 V180 H0 Z"),
    (MARK, rrect(x0, y0, mark, mark, 17)),
    (INK, centered_glyph("S", 30, x0 + mark / 2, 90)),
    (TEXT, word_d),
]))
print("ok")
