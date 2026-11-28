#!/usr/bin/env python3
"""Takes the Google Play screenshots on an emulator, in English and Czech, from the public demo library.

Drives the play debug build with adb: clears its data, signs in with Try the Demo Library, puts an
episode of two shows in Up Next and downloads two, plays the newest Software Freedom Podcast and
captures New Episodes, a show, the player, Up Next, Downloads, Discover and (phone) the widgets on
the home screen. Buttons are found by their text, read from the app's own strings, so the same
steps work in both languages. The device language is switched with `cmd locale` and put back
afterwards; the status bar is in demo mode (9:41, full battery, Wi-Fi) while it runs.

Raw captures go to build/store-screenshots/<locale>/<device>/; tools/store-graphics.py frames
them into fastlane/metadata/android/<locale>/images/.

Before the first phone run, put the own.podcast Now Playing, Up Next and Player widgets on a
home screen page of their own by hand (long-press the home screen, Widgets) and set WIDGET_PAGE:
they stay there as long as the emulator keeps its data.

  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ANDROID_SERIAL=emulator-5560 ./gradlew :app:installPlayDebug
  tools/store-screenshots.py emulator-5560 phone            # also: seven, ten
  tools/store-screenshots.py emulator-5560 phone cs-CZ      # one language only
"""
import pathlib, re, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = "audio.own.podcastplayer"
# The demo library's shows: the one that plays, the one whose page is shown, one more for Up Next.
PLAYING, SHOW, OTHER = "Software Freedom Podcast", "Hacker Public Radio", "Linux Inlaws"
STRINGS = [ROOT / "app/src/main/res", ROOT / "core/strings/src/main/res"]
WIDGET_PAGE = 3

if len(sys.argv) < 3 or sys.argv[2] not in ("phone", "seven", "ten"):
    sys.exit(__doc__)
SERIAL, DEVICE = sys.argv[1], sys.argv[2]
LOCALES = sys.argv[3:] or ["en-US", "cs-CZ"]


def adb(*args, check=True):
    # A slow emulator can leave uiautomator hanging; a stuck call is retried once.
    for attempt in (1, 2):
        try:
            return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, check=check, timeout=60).stdout
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise


def shell(command):
    return adb("shell", command).decode()


def string(name, locale):
    folder = "values" if locale.startswith("en") else f"values-{locale.split('-')[0]}"
    for res in STRINGS:
        for path in sorted((res / folder).glob("strings*.xml")):
            for element in ET.parse(path).getroot().iter("string"):
                if element.get("name") == name:
                    return (element.text or "").replace("\\'", "'")
    sys.exit(f"no string {name} for {locale}")


def nodes():
    shell("uiautomator dump /sdcard/store-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/store-ui.xml").decode()
    found = []
    for node in re.findall(r"<node [^>]*>", xml):
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, bounds.groups())
        for key in ("text", "content-desc"):
            value = re.search(key + r'="([^"]*)"', node)
            if value and value.group(1):
                text = value.group(1).replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", '"')
                found.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def size():
    width, height = re.search(r"(\d+)x(\d+)", shell("wm size").splitlines()[-1]).groups()
    return int(width), int(height)


def find(texts, index=0, scroll=False, wait=20):
    texts = [texts] if isinstance(texts, str) else texts
    deadline = time.time() + wait
    while time.time() < deadline:
        on_screen = nodes()
        for text in texts:
            hits = [(x, y) for value, x, y in on_screen if value == text]
            if len(hits) > index:
                return hits[index]
        if scroll:
            width, height = size()
            shell(f"input swipe {width // 2} {height * 2 // 3} {width // 2} {height // 3} 400")
        time.sleep(1)
    sys.exit(f"not on screen: {texts}")


def tap(texts, index=0, scroll=False, wait=20):
    x, y = find(texts, index, scroll, wait)
    shell(f"input tap {x} {y}")
    time.sleep(1.5)


def scroll_to_top():
    width, height = size()
    for _ in range(3):
        shell(f"input swipe {width // 2} {height // 3} {width // 2} {height * 5 // 6} 200")
    time.sleep(1)


def capture(folder, name):
    time.sleep(1.5)
    (folder / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    print(f"  {name}", flush=True)


def demo_mode(on):
    def demo(*args):
        shell("am broadcast -a com.android.systemui.demo -e command " + " ".join(args))
    if not on:
        demo("exit")
        return
    shell("settings put global sysui_demo_allowed 1")
    demo("enter")
    demo("clock", "-e", "hhmm", "0941")
    demo("battery", "-e", "level", "100", "-e", "plugged", "false")
    demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    demo("network", "-e", "mobile", "hide")
    demo("notifications", "-e", "visible", "false")


def episode_rows(s):
    """The centre of each episode row on a show's page, from its Play button."""
    width, _ = size()
    rows = sorted(y for value, x, y in nodes() if value in (s("play"), s("pause")) and x > width * 0.6)
    return [(width // 2, y) for y in rows]


def episode_menu(s, row, item):
    x, y = episode_rows(s)[row]
    shell(f"input swipe {x} {y} {x} {y} 1000")  # touch and hold
    time.sleep(1.5)
    tap(s(item))


def open_show(s, show):
    tap(s("tab_shows"))
    tap(show, scroll=True)
    find(s("play"), wait=30)
    time.sleep(2)


def back():
    shell("input keyevent 4")
    time.sleep(1.5)


def run(locale):
    s = lambda name: string(name, locale)
    folder = ROOT / "build/store-screenshots" / locale / DEVICE
    folder.mkdir(parents=True, exist_ok=True)
    print(f"== {DEVICE}, {locale}")
    shell(f"cmd locale set-device-locale {locale}")
    time.sleep(3)
    demo_mode(True)
    shell(f"pm clear {PACKAGE}")
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    shell("cmd uimode night no")
    shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")
    tap(s("try_the_demo"), scroll=True)
    find(s("tab_discover"), wait=60)
    time.sleep(4)  # the pictures

    # Up Next and Downloads get an episode of two shows each; the newest Software Freedom
    # Podcast plays, so the mini player shows on every page.
    open_show(s, OTHER)
    episode_menu(s, 0, "play_later")
    episode_menu(s, 0, "download")
    back()
    open_show(s, PLAYING)
    x, y = find(s("play"), index=0)
    shell(f"input tap {x} {y}")
    shell("cmd media_session volume --stream 3 --set 0")
    time.sleep(4)
    back()
    open_show(s, SHOW)
    episode_menu(s, 2, "play_later")
    episode_menu(s, 3, "download")
    time.sleep(6)  # the download, so its row shows it done
    scroll_to_top()
    capture(folder, "show")
    back()

    tap(s("tab_new_episodes"))
    scroll_to_top()
    capture(folder, "new-episodes")
    open_player(s)
    capture(folder, "player")
    tap(s("up_next"), scroll=True)  # below the controls on a landscape tablet
    find(s("clear_up_next"))
    capture(folder, "up-next")
    back()
    tap(s("close"))
    tap(s("tab_downloads"))
    time.sleep(6)
    capture(folder, "downloads")
    tap(s("tab_discover"))
    find(s("browse"))
    time.sleep(3)  # the category tiles
    capture(folder, "discover")
    if DEVICE == "phone":
        widgets(folder)


def open_player(s):
    # The mini player: its Pause button is the lowest one on the page.
    width, _ = size()
    pauses = [(x, y) for value, x, y in nodes() if value == s("pause")]
    _, y = max(pauses, key=lambda p: p[1])
    shell(f"input tap {width // 3} {y}")
    time.sleep(3)


def widgets(folder):
    shell("input keyevent 3")
    time.sleep(2)
    shell("input keyevent 3")  # back to the first page
    time.sleep(2)
    width, height = size()
    for _ in range(WIDGET_PAGE - 1):
        shell(f"input swipe {width * 5 // 6} {height // 2} {width // 6} {height // 2} 300")
        time.sleep(1.5)
    time.sleep(4)
    capture(folder, "widgets")
    shell("input keyevent 3")


previous = shell("cmd locale get-device-locale").strip()
try:
    for locale in LOCALES:
        run(locale)
finally:
    demo_mode(False)
    shell(f"cmd locale set-device-locale {previous or 'en-US'}")
print(f"raw screenshots in {ROOT / 'build/store-screenshots'}")
