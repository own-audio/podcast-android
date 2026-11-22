#!/usr/bin/env python3
"""Writes the 512 x 512 store icon from the adaptive launcher icon.

Turns app/src/main/res/drawable/ic_launcher_foreground.xml (written by tools/render-icon.py) into
an SVG on the launcher_background colour, cropped to the 72 dp the launcher shows, and renders it
to fastlane/metadata/android/en-US/images/icon.png, which Google Play and F-Droid both read.
Rendering needs Inkscape, or Node with sharp (SHARP_DIR=<path to node_modules/sharp>).
Run after render-icon.py; the output is committed.

  tools/store-icon.py
"""
import os, pathlib, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
OUT = ROOT / "fastlane/metadata/android/en-US/images/icon.png"
A = "{http://schemas.android.com/apk/res/android}"
SIZE = 512

def attr(el, name, default=None):
    return el.get(A + name, default)

def num(el, name, default):
    return float(attr(el, name, default))

def convert(el):
    if el.tag == "group":
        tx, ty = num(el, "translateX", 0), num(el, "translateY", 0)
        sx, sy = num(el, "scaleX", 1), num(el, "scaleY", 1)
        r, px, py = num(el, "rotation", 0), num(el, "pivotX", 0), num(el, "pivotY", 0)
        # VectorDrawable order: move to the pivot, scale, rotate, move back, then translate.
        transform = f"translate({tx + px} {ty + py}) rotate({r}) scale({sx} {sy}) translate({-px} {-py})"
        return f'<g transform="{transform}">' + "".join(convert(c) for c in el) + "</g>"
    if el.tag == "path":
        fill = attr(el, "fillColor", "none")
        stroke = attr(el, "strokeColor")
        parts = [f'd="{attr(el, "pathData")}"', f'fill="{fill}"']
        if stroke:
            parts += [f'stroke="{stroke}"', f'stroke-width="{attr(el, "strokeWidth", "1")}"',
                      f'stroke-linecap="{attr(el, "strokeLineCap", "butt")}"',
                      f'stroke-linejoin="{attr(el, "strokeLineJoin", "miter")}"']
        return "<path " + " ".join(parts) + "/>"
    return ""

vector = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
colors = (RES / "values/colors.xml").read_text()
background = re.search(r'name="launcher_background">([^<]+)<', colors).group(1)
body = "".join(convert(c) for c in vector)
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" viewBox="18 18 72 72">'
       f'<rect x="0" y="0" width="108" height="108" fill="{background}"/>{body}</svg>')

OUT.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    src = pathlib.Path(tmp) / "icon.svg"
    src.write_text(svg)
    if shutil.which("inkscape"):
        subprocess.run(["inkscape", str(src), "--export-type=png", f"--export-filename={OUT}",
                        f"--export-width={SIZE}", f"--export-height={SIZE}", "--export-background-opacity=1"],
                       check=True, capture_output=True)
    elif os.environ.get("SHARP_DIR") and shutil.which("node"):
        script = "require(process.env.SHARP_DIR)(process.argv[1]).png().toFile(process.argv[2])"
        subprocess.run(["node", "-e", script, str(src), str(OUT)], check=True)
    else:
        sys.exit("Needs inkscape, or node with SHARP_DIR set to a sharp install.")
print(f"written {OUT.relative_to(ROOT)}", file=sys.stderr)
