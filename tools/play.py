#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Google Play Developer API without extra packages (standard library and openssl only).

    python3 tools/play.py check
    python3 tools/play.py listing                 # texts, contact, icon, feature graphic, phone and tablet screenshots
    python3 tools/play.py upload internal         # app-release.aab to a track, with release notes
    python3 tools/play.py upload alpha --draft    # closed testing ("alpha"), left as a draft
    python3 tools/play.py promote internal alpha  # the newest release of one track onto another
    ... --no-review                                 # commit, but send for review from Play Console

The service account key is read from PLAY_KEY or ~/.android-keys/play-service-account.json; the
account needs access to the app in Play Console › Users and permissions. Texts and pictures come
from fastlane/metadata/android/<locale>/, release notes from changelogs/<versionCode>.txt.
"""
import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
# Same file in music-, book- and podcast-android: the app's id and version come from gradle.properties.
PROPERTIES = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
PACKAGE = PROPERTIES["app.id"].strip()
METADATA = ROOT / "fastlane" / "metadata" / "android"
BUNDLE = ROOT / "app" / "build" / "outputs" / "bundle" / "playRelease" / "app-play-release.aab"
KEY = pathlib.Path(os.environ.get("PLAY_KEY", pathlib.Path.home() / ".android-keys" / "play-service-account.json"))
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
CONTACT_EMAIL = "hello@own.audio"
WEBSITE = {"audio.own.musicplayer": "https://www.own.audio/apps/music/"}.get(PACKAGE, "https://www.own.audio/")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def token() -> str:
    """OAuth access token for the service account (JWT signed with openssl)."""
    account = json.loads(KEY.read_text())
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": account["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600,
    }).encode())
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as pem:
        pem.write(account["private_key"])
    try:
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", pem.name],
                                   input=f"{header}.{claims}".encode(), capture_output=True, check=True).stdout
    finally:
        os.unlink(pem.name)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": f"{header}.{claims}.{b64url(signature)}",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", body)) as response:
        return json.load(response)["access_token"]


_token = None


def call(method: str, url: str, body=None, data: bytes = None, content_type: str = "application/json"):
    global _token
    _token = _token or token()
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(url, payload, method=method)
    request.add_header("Authorization", f"Bearer {_token}")
    if payload is not None:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            text = response.read()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {url.split(PACKAGE)[-1]} -> HTTP {error.code}: {error.read().decode()[:500]}")


def edit():
    return call("POST", f"{API}/edits", {})["id"]


def commit(edit_id: str):
    # --no-review: leave the changes for "Send for review" in Play Console (needed while an app has
    # rejected or unsent changes, which the API cannot send for review itself)
    query = "?changesNotSentForReview=true" if "--no-review" in sys.argv else ""
    call("POST", f"{API}/edits/{edit_id}:commit{query}")


def locales():
    return sorted(p.name for p in METADATA.iterdir() if p.is_dir())


def read(locale: str, name: str) -> str:
    path = METADATA / locale / name
    return path.read_text().strip() if path.exists() else ""


def cmd_check():
    edit_id = edit()
    details = call("GET", f"{API}/edits/{edit_id}/details")
    tracks = call("GET", f"{API}/edits/{edit_id}/tracks").get("tracks", [])
    print("details:", details)
    for track in tracks:
        print("track", track["track"], [r.get("versionCodes") for r in track.get("releases", [])])
    call("DELETE", f"{API}/edits/{edit_id}")


def cmd_listing():
    edit_id = edit()
    call("PUT", f"{API}/edits/{edit_id}/details",
         {"defaultLanguage": "en-US", "contactEmail": CONTACT_EMAIL, "contactWebsite": WEBSITE})
    for locale in locales():
        call("PUT", f"{API}/edits/{edit_id}/listings/{locale}", {
            "language": locale, "title": read(locale, "title.txt"),
            "shortDescription": read(locale, "short_description.txt"), "fullDescription": read(locale, "full_description.txt"),
        })
        images = METADATA / locale / "images"
        for kind, files in (("icon", [images / "icon.png"]), ("featureGraphic", [images / "featureGraphic.png"]),
                            ("phoneScreenshots", sorted((images / "phoneScreenshots").glob("*.png"))),
                            ("sevenInchScreenshots", sorted((images / "sevenInchScreenshots").glob("*.png"))),
                            ("tenInchScreenshots", sorted((images / "tenInchScreenshots").glob("*.png")))):
            files = [f for f in files if f.exists()]
            if not files:
                continue
            call("DELETE", f"{API}/edits/{edit_id}/listings/{locale}/{kind}")
            for f in files:
                call("POST", f"{UPLOAD}/edits/{edit_id}/listings/{locale}/{kind}?uploadType=media",
                     data=f.read_bytes(), content_type="image/png")
        print(f"{locale}: texts and pictures")
    commit(edit_id)
    print("listing committed")


def cmd_upload():
    if len(sys.argv) < 3:
        sys.exit("usage: play.py upload <internal|alpha|beta|production> [--draft]")
    track, draft = sys.argv[2], "--draft" in sys.argv
    if not BUNDLE.exists():
        sys.exit(f"no bundle at {BUNDLE}: ./gradlew :app:bundlePlayRelease -Papp.versionCode=$(git rev-list --count HEAD)")
    edit_id = edit()
    bundle = call("POST", f"{UPLOAD}/edits/{edit_id}/bundles?uploadType=media",
                  data=BUNDLE.read_bytes(), content_type="application/octet-stream")
    code = bundle["versionCode"]
    notes = [{"language": locale, "text": read(locale, f"changelogs/{code}.txt")}
             for locale in locales() if read(locale, f"changelogs/{code}.txt")]
    name = PROPERTIES["app.versionName"].strip()
    call("PUT", f"{API}/edits/{edit_id}/tracks/{track}", {"track": track, "releases": [{
        "name": f"{code} ({name})", "versionCodes": [str(code)], "status": "draft" if draft else "completed",
        "releaseNotes": notes,
    }]})
    commit(edit_id)
    print(f"version {code} ({name}) on {track}{' as a draft' if draft else ''}")


def cmd_promote():
    if len(sys.argv) < 4:
        sys.exit("usage: play.py promote <from track> <to track> [--draft]")
    source, target, draft = sys.argv[2], sys.argv[3], "--draft" in sys.argv
    edit_id = edit()
    releases = call("GET", f"{API}/edits/{edit_id}/tracks/{source}").get("releases", [])
    if not releases:
        sys.exit(f"nothing on {source}")
    release = dict(releases[0])
    release["status"] = "draft" if draft else "completed"
    release.pop("userFraction", None)
    call("PUT", f"{API}/edits/{edit_id}/tracks/{target}", {"track": target, "releases": [release]})
    commit(edit_id)
    print(f"{release.get('name')} from {source} on {target}{' as a draft' if draft else ''}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    {"check": cmd_check, "listing": cmd_listing, "upload": cmd_upload, "promote": cmd_promote}.get(command, lambda: sys.exit(__doc__))()
