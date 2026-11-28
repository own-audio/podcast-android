# own.audio Podcasts for Android

A native, open-source podcast player for Android that plays from plain podcast feeds with no account, and optionally own.audio, Audiobookshelf and gPodder sync servers.
The Android sibling of [own.audio Podcasts for iPhone and iPad](https://github.com/own-audio/podcast-ios).

**Status: in testing.** Version 1.0 is built for phones and tablets and is in a closed test on Google Play.
Signed APKs are on the [Releases page](https://github.com/own-audio/podcast-android/releases/latest); a GitHub
install and a Google Play install can't update each other, so uninstall before switching. How to join the
Google Play test is on [own.audio/apps](https://www.own.audio/apps/#android). F-Droid is planned.
What changed is in [CHANGELOG.md](CHANGELOG.md); the parts shared with the other own.audio Android apps are in
[docs/android-foundation.md](docs/android-foundation.md).

- Kotlin, Jetpack Compose, Media3; Android 9 or later.
- Talks only to the servers and feeds you add. No analytics, no ads, no tracking.
- own.audio servers through their own API (v2); needs own.audio server 1.0.4 or later.
- Free and open source under the [Mozilla Public License 2.0](LICENSE).

own.audio and the own.audio logo are trademarks; forks should use their own name and icon.
