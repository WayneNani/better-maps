#!/usr/bin/env python3
"""Erzeugt alle Better-Maps-Logos aus einer Definition.

Ausgaben:
  - Android-Vektoren (Launcher-Vorder-/Hintergrund, monochrom, Benachrichtigung, Logo)
  - Launcher-PNGs für Android < 8 (mipmap-*)
  - SVG-Master und PNGs für README und Store-Einträge (docs/branding, F-Droid-Metadaten)

Aufruf aus dem Repo-Wurzelverzeichnis:  python3 tools/better-maps/branding/generate_icons.py
Benötigt rsvg-convert (librsvg) für die PNGs.
"""
import math
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
BRANDING = os.path.join(ROOT, "docs", "branding")
FDROID_GRAPHICS = os.path.join(ROOT, "android", "app", "src", "fdroid", "play", "listings", "en-US", "graphics")

# Farben
TEAL_LIGHT = "#0F766E"
TEAL_DARK = "#155E75"
AMBER = "#F59E0B"
WHITE = "#FFFFFF"
INK = "#134E4A"

# Glyphe im 64er-Raster: ein kleines "b", dessen Bauch eine Standortnadel ist.
CX, CY, R, TIP_Y = 34.0, 30.0, 11.5, 50.0
STEM_X0, STEM_X1, STEM_Y0, STEM_R = 19.5, 26.5, 12.0, 3.5
DOT_R = 4.8
GLYPH_CENTER = (32.5, 31.0)  # Mitte der Bounding-Box


def glyph_paths():
    a = math.acos(R / (TIP_Y - CY))
    lx, ly = CX - R * math.sin(a), CY + R * math.cos(a)
    rx = CX + R * math.sin(a)
    pin = f"M{lx:.3f},{ly:.3f}A{R},{R} 0 1,1 {rx:.3f},{ly:.3f}L{CX},{TIP_Y}Z"
    x0, x1, y0, y1, r = STEM_X0, STEM_X1, STEM_Y0, ly + 1.0, STEM_R
    stem = (f"M{x0 + r},{y0}H{x1 - r}A{r},{r} 0 0,1 {x1},{y0 + r}V{y1 - r:.3f}"
            f"A{r},{r} 0 0,1 {x1 - r},{y1:.3f}H{x0 + r}A{r},{r} 0 0,1 {x0},{y1 - r:.3f}V{y0 + r}"
            f"A{r},{r} 0 0,1 {x0 + r},{y0}Z")
    dot = f"M{CX - DOT_R},{CY}a{DOT_R},{DOT_R} 0 1,0 {2 * DOT_R},0a{DOT_R},{DOT_R} 0 1,0 {-2 * DOT_R},0Z"
    return pin, stem, dot


PIN, STEM, DOT = glyph_paths()
# Angedeutete Straße im Hintergrund (64er-Raster).
WAVE = "M-4,52 C12,44 22,58 36,50 S58,40 70,46"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, ROOT))


# ---------- SVG ----------

def svg_icon(bg1, bg2, fg, dot, shape="rounded", size=512, wave=True):
    clip = '<rect width="64" height="64" rx="14"/>' if shape == "rounded" else '<circle cx="32" cy="32" r="32"/>'
    wave_el = (f'<path d="{WAVE}" fill="none" stroke="#FFFFFF" stroke-opacity="0.13" stroke-width="5" '
               f'stroke-linecap="round"/>' if wave else "")
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="{size}" height="{size}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{bg1}"/><stop offset="1" stop-color="{bg2}"/></linearGradient>
    <clipPath id="c">{clip}</clipPath>
  </defs>
  <g clip-path="url(#c)">
    <rect width="64" height="64" fill="url(#bg)"/>
    {wave_el}
    <path d="{STEM}" fill="{fg}"/>
    <path d="{PIN}" fill="{fg}"/>
    <path d="{DOT}" fill="{dot}"/>
  </g>
</svg>
'''


def svg_glyph(fg, dot):
    # Nur die Glyphe, quadratisch zugeschnitten (für helle/dunkle Hintergründe).
    s = 42.0
    tx, ty = s / 2 - GLYPH_CENTER[0], s / 2 - GLYPH_CENTER[1]
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {s:g} {s:g}" width="256" height="256">
  <g transform="translate({tx:g},{ty:g})">
    <path d="{STEM}" fill="{fg}"/>
    <path d="{PIN}" fill="{fg}"/>
    <path d="{DOT}" fill="{dot}"/>
  </g>
</svg>
'''


def svg_feature_graphic():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 500" width="1024" height="500">
  <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{TEAL_LIGHT}"/><stop offset="1" stop-color="{TEAL_DARK}"/></linearGradient></defs>
  <rect width="1024" height="500" fill="url(#bg)"/>
  <path d="M-40,420 C160,330 300,480 500,400 S860,300 1080,370" fill="none" stroke="#FFFFFF" stroke-opacity="0.12" stroke-width="40" stroke-linecap="round"/>
  <g transform="translate(70,95) scale(4.6)">
    <g transform="translate({-GLYPH_CENTER[0] + 32:g},{-GLYPH_CENTER[1] + 32:g})">
      <path d="{STEM}" fill="#FFFFFF"/><path d="{PIN}" fill="#FFFFFF"/><path d="{DOT}" fill="{AMBER}"/>
    </g>
  </g>
  <text x="370" y="250" font-family="Inter, Noto Sans, DejaVu Sans, sans-serif" font-size="82" font-weight="700" fill="#FFFFFF">Better Maps</text>
  <text x="374" y="312" font-family="Inter, Noto Sans, DejaVu Sans, sans-serif" font-size="27" fill="#CCFBF1">Offline. Open. Live transit &amp; traffic.</text>
</svg>
'''


# ---------- Android-Vektoren ----------

VECTOR_HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generiert von tools/better-maps/branding/generate_icons.py, nicht von Hand ändern. -->
'''


def glyph_group(fg, dot, scale, tx, ty, monochrome=False, indent="  "):
    i = indent
    if monochrome:
        # Punkt als Loch in der Nadel (evenOdd), damit das Symbol einfarbig bleibt.
        return (f'{i}<group android:translateX="{tx:g}" android:translateY="{ty:g}" '
                f'android:scaleX="{scale:g}" android:scaleY="{scale:g}">\n'
                f'{i}  <path android:pathData="{STEM}" android:fillColor="{fg}"/>\n'
                f'{i}  <path android:pathData="{PIN}{DOT}" android:fillColor="{fg}" android:fillType="evenOdd"/>\n'
                f'{i}</group>\n')
    return (f'{i}<group android:translateX="{tx:g}" android:translateY="{ty:g}" '
            f'android:scaleX="{scale:g}" android:scaleY="{scale:g}">\n'
            f'{i}  <path android:pathData="{STEM}" android:fillColor="{fg}"/>\n'
            f'{i}  <path android:pathData="{PIN}" android:fillColor="{fg}"/>\n'
            f'{i}  <path android:pathData="{DOT}" android:fillColor="{dot}"/>\n'
            f'{i}</group>\n')


def launcher_foreground(fg, dot, monochrome=False):
    # Adaptive Icons: 108dp-Fläche, sichere Zone = Kreis mit 66dp Durchmesser um die Mitte.
    s = 1.2
    tx, ty = 54 - GLYPH_CENTER[0] * s, 54 - GLYPH_CENTER[1] * s
    return (VECTOR_HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '  android:width="108dp" android:height="108dp"\n'
            '  android:viewportWidth="108" android:viewportHeight="108">\n'
            + glyph_group(fg, dot, s, tx, ty, monochrome) + '</vector>\n')


def launcher_background():
    s = 108 / 64
    return (VECTOR_HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '  xmlns:aapt="http://schemas.android.com/aapt"\n'
            '  android:width="108dp" android:height="108dp"\n'
            '  android:viewportWidth="108" android:viewportHeight="108">\n'
            '  <path android:pathData="M0,0h108v108h-108z">\n'
            '    <aapt:attr name="android:fillColor">\n'
            '      <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">\n'
            f'        <item android:offset="0" android:color="{TEAL_LIGHT}"/>\n'
            f'        <item android:offset="1" android:color="{TEAL_DARK}"/>\n'
            '      </gradient>\n'
            '    </aapt:attr>\n'
            '  </path>\n'
            f'  <group android:scaleX="{s:.4f}" android:scaleY="{s:.4f}">\n'
            f'    <path android:pathData="{WAVE}" android:strokeColor="#21FFFFFF" android:strokeWidth="5" '
            'android:strokeLineCap="round"/>\n'
            '  </group>\n'
            '</vector>\n')


def glyph_vector(size_dp, fg, dot, monochrome=False, tint=None):
    s = 42
    tx, ty = s / 2 - GLYPH_CENTER[0], s / 2 - GLYPH_CENTER[1]
    tint_attr = f'\n  android:tint="{tint}"' if tint else ""
    return (VECTOR_HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'  android:width="{size_dp}dp" android:height="{size_dp}dp"{tint_attr}\n'
            f'  android:viewportWidth="{s}" android:viewportHeight="{s}">\n'
            + glyph_group(fg, dot, 1, tx, ty, monochrome) + '</vector>\n')


def logo_vector(size_dp):
    # Vollständiges Icon (abgerundetes Quadrat) als Vektor, z. B. für Begrüßungsbildschirme.
    return (VECTOR_HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '  xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'  android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            '  android:viewportWidth="64" android:viewportHeight="64">\n'
            '  <path android:pathData="M14,0H50A14,14 0,0 1,64 14V50A14,14 0,0 1,50 64H14A14,14 0,0 1,0 50V14A14,14 0,0 1,14 0Z">\n'
            '    <aapt:attr name="android:fillColor">\n'
            '      <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="64" android:endY="64">\n'
            f'        <item android:offset="0" android:color="{TEAL_LIGHT}"/>\n'
            f'        <item android:offset="1" android:color="{TEAL_DARK}"/>\n'
            '      </gradient>\n'
            '    </aapt:attr>\n'
            '  </path>\n'
            + glyph_group(WHITE, AMBER, 1, 0, 0) + '</vector>\n')


def render_png(svg_text, out_path, px):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), "-o", out_path], input=svg_text.encode(), check=True)
    print("wrote", os.path.relpath(out_path, ROOT))


def main():
    # Master-SVGs
    write(os.path.join(BRANDING, "better-maps-icon.svg"), svg_icon(TEAL_LIGHT, TEAL_DARK, WHITE, AMBER))
    write(os.path.join(BRANDING, "better-maps-icon-round.svg"), svg_icon(TEAL_LIGHT, TEAL_DARK, WHITE, AMBER, "round"))
    write(os.path.join(BRANDING, "better-maps-glyph.svg"), svg_glyph(TEAL_LIGHT, AMBER))
    write(os.path.join(BRANDING, "better-maps-glyph-white.svg"), svg_glyph(WHITE, AMBER))
    feature = svg_feature_graphic()
    write(os.path.join(BRANDING, "better-maps-feature-graphic.svg"), feature)

    # Android: adaptive Icons
    d = os.path.join(RES, "drawable")
    write(os.path.join(d, "ic_launcher_foreground.xml"), launcher_foreground(WHITE, AMBER))
    write(os.path.join(d, "ic_launcher_background.xml"), launcher_background())
    write(os.path.join(d, "ic_launcher_foreground_monochrome.xml"), launcher_foreground("#FFFFFFFF", None, True))
    write(os.path.join(d, "ic_launcher_foreground_debug.xml"), launcher_foreground(INK, WHITE))
    write(os.path.join(d, "ic_launcher_foreground_test.xml"), launcher_foreground(WHITE, "#5EEAD4"))
    # Logos in der App
    write(os.path.join(d, "ic_logo.xml"), logo_vector(64))
    write(os.path.join(d, "ic_logo_small.xml"), glyph_vector(24, "#FFFFFFFF", None, True))
    write(os.path.join(d, "ic_logo_monochrome.xml"), glyph_vector(64, "#FFFFFFFF", None, True))
    write(os.path.join(d, "ic_logo_monochrome_settings.xml"), glyph_vector(22, "#FFFFFFFF", None, True, "?iconTint"))

    if "--no-png" in sys.argv:
        return
    # Launcher-PNGs für Android < 8
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        render_png(svg_icon(TEAL_LIGHT, TEAL_DARK, WHITE, AMBER, size=px), os.path.join(RES, f"mipmap-{density}", "ic_launcher.png"), px)
    # README und Store
    render_png(svg_icon(TEAL_LIGHT, TEAL_DARK, WHITE, AMBER), os.path.join(BRANDING, "better-maps-icon-512.png"), 512)
    render_png(svg_icon(TEAL_LIGHT, TEAL_DARK, WHITE, AMBER), os.path.join(FDROID_GRAPHICS, "icon", "logo.png"), 512)
    subprocess.run(["rsvg-convert", "-w", "1024", "-h", "500", "-o",
                    os.path.join(BRANDING, "better-maps-feature-graphic.png")], input=feature.encode(), check=True)
    print("wrote docs/branding/better-maps-feature-graphic.png")


if __name__ == "__main__":
    main()
