# own.audio Podcasts for Android

A native, open-source podcast player for Android that plays from plain podcast feeds with no account, and optionally own.audio, Audiobookshelf and gPodder sync servers.
The Android sibling of [own.audio Podcasts for iPhone and iPad](https://github.com/own-audio/podcast-ios).

**Status: in testing.** Version 1.0, for phones and tablets, is on GitHub and in a closed test on Google Play.
What changed is in [CHANGELOG.md](CHANGELOG.md); the parts shared with the other own.audio Android apps are in
[docs/android-foundation.md](docs/android-foundation.md).

- Kotlin, Jetpack Compose, Media3; Android 9 or later.
- Talks only to the servers and feeds you add. No analytics, no ads, no tracking.
- own.audio servers through their own API (v2); needs own.audio server 1.0.4 or later.
- Free and open source under the [Mozilla Public License 2.0](LICENSE).

## Install

- **GitHub:** a signed APK with every [release](https://github.com/own-audio/podcast-android/releases/latest).
- **Obtainium** keeps it updated from those releases:
  [<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/own-audio/podcast-android)
- **F-Droid client** (F-Droid, Droid-ify, Neo Store): add the own.audio repository —
  [own-audio.github.io/fdroid](https://own-audio.github.io/fdroid/) has the link and QR code. Updates arrive
  within a few hours of each release.
- **Google Play:** closed test — how to join is on [own.audio/apps](https://www.own.audio/apps/#android).

The GitHub build has no Google code in it (no Continue with Google). A GitHub install and a Google Play
install can't update each other; uninstall before switching.

## How this app is made

Most of the code was written by an AI coding assistant (Anthropic's Claude, working in Claude Code),
directed and reviewed by the maintainer. We say so because people deserve to know, and because some
app stores and projects don't accept AI-written code.

What we do so that it holds up anyway: every change comes with tests (unit, Robolectric and screenshot
tests run on every push), the app is tested by hand against real servers on emulators and phones
before a release (reports in [docs/tests/](docs/tests/)), and the parts shared with the other own.audio
apps are kept identical by a drift check. Bugs and questions are welcome as GitHub issues.

own.audio and the own.audio logo are trademarks; forks should use their own name and icon.
