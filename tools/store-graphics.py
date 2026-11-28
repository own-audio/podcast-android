#!/usr/bin/env python3
"""Writes the Google Play feature graphic and the store screenshots, in English and Czech.

- featureGraphic.png: 1024 x 500, the store icon and the app's name and line on the own.audio
  background (the website's waves, as in the iOS App Store pictures).
- phoneScreenshots/01-07.png: 1440 x 2560 (9:16), each phone capture from
  tools/store-screenshots.py under a headline and a line, the iOS app's captions where the two
  apps show the same thing.
- sevenInchScreenshots/ and tenInchScreenshots/01-04.png: the tablet captures as they are
  (1080 x 1920 and 2560 x 1440, 9:16 and 16:9), without text, as Google asks for large screens.

All without alpha, as Google Play requires. Needs Pillow and the macOS system font (SF):

  python3 -m venv build/venv && build/venv/bin/pip install pillow
  build/venv/bin/python tools/store-graphics.py
"""
import math, pathlib, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / "build/store-screenshots"
METADATA = ROOT / "fastlane/metadata/android"
ICON = METADATA / "en-US/images/icon.png"
FONT = "/System/Library/Fonts/SFNS.ttf"

FEATURE = {
    "en-US": ("own.audio Podcasts", "Podcasts, no account needed"),
    "cs-CZ": ("own.audio Podcasts", "Podcasty bez registrace"),
}
# (capture, headline, line) per language, in store order: the iOS app's captions; "widgets" has
# no iOS original.
PHONE = {
    "en-US": [
        ("new-episodes", "What's new, first", "The unplayed episodes across your shows, newest first"),
        ("player", "Made for listening", "Speed with the voice kept, chapters, sleep timer"),
        ("show", "Every show, every episode", "Notes with working links; unplayed and downloaded at a tap"),
        ("up-next", "Up Next", "Episodes from any show, in the order you choose"),
        ("discover", "Find your next show", "Browse by category, or add any feed by its address"),
        ("downloads", "Offline, by your rules", "Keep the newest episodes, delete them once played"),
        ("widgets", "Right on your home screen", "Player, Now Playing and Up Next widgets"),
    ],
    "cs-CZ": [
        ("new-episodes", "Nejdřív to nové", "Nepřehrané epizody ze všech vašich pořadů, od nejnovějších"),
        ("player", "Stvořený pro poslech", "Rychlost se zachovaným hlasem, kapitoly, časovač spánku"),
        ("show", "Každý pořad, každá epizoda", "Poznámky s funkčními odkazy; nepřehrané i stažené jedním klepnutím"),
        ("up-next", "Na řadě", "Epizody z kteréhokoli pořadu, v pořadí, jaké chcete"),
        ("discover", "Najděte další pořad", "Procházejte podle kategorií, nebo přidejte kanál podle adresy"),
        ("downloads", "Offline, podle vašich pravidel", "Nejnovější epizody stažené, přehrané zase smazané"),
        ("widgets", "Přímo na ploše", "Widgety Přehrávač, Právě hraje a Na řadě"),
    ],
}
TABLET = ["discover", "show", "player", "up-next"]
PHONE_SIZE = (1440, 2560)
ACCENT = (0x29, 0xAB, 0xE2)  # the app's dark-mode accent


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def hex_rgba(value, alpha=1.0):
    return ((value >> 16) & 255, (value >> 8) & 255, value & 255, round(alpha * 255))


class Random:
    """The iOS generator's LCG, so a picture is the same every time it is made."""
    def __init__(self, seed):
        self.state = seed & 0xFFFFFFFF

    def next(self):
        self.state = (self.state * 1664525 + 1013904223) & 0xFFFFFFFF
        return self.state / 0xFFFFFFFF


def background(size, seed, band_top, band_height, scale_base=1200):
    """Sine waves (purple strong, gold and teal fine) and dots in the family's colours on
    near-black; drawn twice as large and scaled down for smooth lines."""
    k = 2
    w, h = size[0] * k, size[1] * k
    image = Image.new("RGB", (w, h))
    top, bottom = (0x0B, 0x0B, 0x10), (0x17, 0x11, 0x2B)
    pixels = ImageDraw.Draw(image)
    for y in range(h):
        t = y / (h - 1)
        pixels.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    random = Random(seed)
    scale = w / scale_base

    def wave(amplitude, length, phase, y):
        points, x = [], -20 * k
        while x <= w + 20 * k:
            points.append((x, y + amplitude * math.sin(x / length * 2 * math.pi + phase)))
            x += 6 * k
        return points

    band = band_top * h
    for i in range(10):
        points = wave((80 + random.next() * 180) * scale, (500 + random.next() * 700) * scale,
                      random.next() * 6.3, band + random.next() * band_height * h)
        draw.line(points, fill=hex_rgba(0xC9A227 if i % 2 == 0 else 0x2A9D9A, 0.35), width=round(2 * scale), joint="curve")
    for _ in range(2):
        points = wave((100 + random.next() * 140) * scale, (600 + random.next() * 500) * scale,
                      random.next() * 6.3, band + random.next() * band_height * 0.75 * h)
        draw.line(points, fill=hex_rgba(0x6E44FF, 0.8), width=round(6 * scale), joint="curve")
    family = [0xFABB05, 0x29ABE2, 0xFF3B30, 0xFFCC00, 0x34C759, 0x6E44FF]
    for i in range(40):
        r = (3 + random.next() * 8) * scale
        x, y = random.next() * w, (band_top - 0.1) * h + random.next() * (band_height + 0.12) * h
        draw.ellipse([x - r, y - r, x + r, y + r], fill=hex_rgba(family[i % len(family)], 0.5 + random.next() * 0.4))
    image = Image.alpha_composite(image.convert("RGBA"), layer).convert("RGB")
    return image.resize(size, Image.LANCZOS)


def seed_of(key):
    value = 7
    for ch in key:
        value = (value * 31 + ord(ch)) & 0xFFFFFFFF
    return value


def centred(draw, text, f, y, width, fill):
    left, top, right, bottom = draw.textbbox((0, 0), text, font=f)
    draw.text(((width - (right - left)) / 2 - left, y - top), text, font=f, fill=fill)
    return bottom - top


def wrap(draw, text, f, width):
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if draw.textlength(candidate, font=f) <= width or not line:
            line = candidate
        else:
            lines.append(line)
            line = word
    return lines + [line]


def rounded(image, radius):
    mask = Image.new("L", (image.width * 2, image.height * 2), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, mask.width - 1, mask.height - 1], radius=radius * 2, fill=255)
    return mask.resize(image.size, Image.LANCZOS)


def phone_shot(shot, headline, line, key):
    W, H = PHONE_SIZE
    canvas = background(PHONE_SIZE, seed_of(key), band_top=0.02, band_height=0.2)
    draw = ImageDraw.Draw(canvas)
    margin = W * 0.07
    size = round(W * 0.074)
    while draw.textlength(headline, font=font(size, "Heavy")) > W - 2 * margin and size > W * 0.045:
        size -= 2
    y = H * 0.035
    y += centred(draw, headline, font(size, "Heavy"), y, W, (255, 255, 255)) + H * 0.018
    line_font = font(round(W * 0.036), "Medium")
    for part in wrap(draw, line, line_font, W - 2 * margin):
        y += centred(draw, part, line_font, y, W, (0xC9, 0xC9, 0xD0)) + H * 0.008
    top = y + H * 0.025

    # The screenshot, rounded like the screen it came from, with a soft shadow; never wider than 80 %.
    available = H - top - H * 0.03
    width = min(W * 0.8, available * shot.width / shot.height)
    height = width * shot.height / shot.width
    shot = shot.convert("RGB").resize((round(width), round(height)), Image.LANCZOS)
    x, y = round((W - width) / 2), round(top)
    radius = round(width * 0.06)
    shadow = Image.new("L", PHONE_SIZE, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([x, y + W * 0.01, x + shot.width, y + shot.height + W * 0.01], radius=radius, fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(W * 0.025))
    canvas.paste((0, 0, 0), (0, 0), shadow)
    canvas.paste(shot, (x, y), rounded(shot, radius))
    outline = Image.new("RGBA", PHONE_SIZE, (0, 0, 0, 0))
    ImageDraw.Draw(outline).rounded_rectangle([x, y, x + shot.width - 1, y + shot.height - 1], radius=radius,
                                              outline=(255, 255, 255, 31), width=max(2, round(W * 0.003)))
    return Image.alpha_composite(canvas.convert("RGBA"), outline).convert("RGB")


def feature_graphic(title, line):
    W, H = 1024, 500
    canvas = background((W, H), seed_of("feature"), band_top=0.15, band_height=0.7, scale_base=1600)
    # The icon as a launcher would show it: rounded, with a soft shadow.
    size = 220
    icon = Image.open(ICON).convert("RGB").resize((size, size), Image.LANCZOS)
    x, y = 112, (H - size) // 2
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle([x, y + 8, x + size, y + size + 8], radius=size // 4, fill=170)
    canvas.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(18)))
    canvas.paste(icon, (x, y), rounded(icon, size // 4))
    # A soft shade behind the words, so the waves never cross them at full strength.
    shade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shade).rounded_rectangle([x + size + 30, H * 0.3, W - 50, H * 0.7], radius=60, fill=150)
    canvas.paste((0x0B, 0x0B, 0x10), (0, 0), shade.filter(ImageFilter.GaussianBlur(30)))
    draw = ImageDraw.Draw(canvas)
    left, right = x + size + 56, W - 80
    title_size = 74
    while draw.textlength(title, font=font(title_size, "Heavy")) > right - left:
        title_size -= 2
    line_size = 40
    while draw.textlength(line, font=font(line_size, "Medium")) > right - left:
        line_size -= 1
    title_box = draw.textbbox((0, 0), title, font=font(title_size, "Heavy"))
    line_box = draw.textbbox((0, 0), line, font=font(line_size, "Medium"))
    gap = 22
    block = (title_box[3] - title_box[1]) + gap + (line_box[3] - line_box[1])
    top = (H - block) / 2
    # "own.audio" in white with the dot in the app's accent, then the app's own word.
    head, _, rest = title.partition(".")
    f = font(title_size, "Heavy")
    cursor = left
    for part, colour in ((head, (255, 255, 255)), (".", ACCENT), (rest, (255, 255, 255))):
        draw.text((cursor, top - title_box[1]), part, font=f, fill=colour)
        cursor += draw.textlength(part, font=f)
    draw.text((left, top + (title_box[3] - title_box[1]) + gap - line_box[1]), line,
              font=font(line_size, "Medium"), fill=(0xC9, 0xC9, 0xD0))
    return canvas


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(path, optimize=True)
    print(f"  {path.relative_to(ROOT)}  {image.width}x{image.height}")


def main():
    for locale in FEATURE:
        images = METADATA / locale / "images"
        print(f"== {locale}")
        save(feature_graphic(*FEATURE[locale]), images / "featureGraphic.png")
        for kind, device, names in (("phoneScreenshots", "phone", None),
                                    ("sevenInchScreenshots", "seven", TABLET),
                                    ("tenInchScreenshots", "ten", TABLET)):
            folder = RAW / locale / device
            if not folder.exists():
                print(f"  no captures in {folder.relative_to(ROOT)}: run tools/store-screenshots.py")
                continue
            for old in (images / kind).glob("*.png"):
                old.unlink()
            if names is None:
                for number, (name, headline, line) in enumerate(PHONE[locale], 1):
                    shot = Image.open(folder / f"{name}.png")
                    save(phone_shot(shot, headline, line, name), images / kind / f"{number:02d}.png")
            else:
                for number, name in enumerate(names, 1):
                    save(Image.open(folder / f"{name}.png"), images / kind / f"{number:02d}.png")


if __name__ == "__main__":
    sys.exit(main())
