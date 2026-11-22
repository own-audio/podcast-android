---
title: Privacy
nav_order: 14
description: "The app collects nothing. There is no account with us, no analytics, no ads and no tracking. The full policy is in PRIVACY.md."
---

# Privacy

The app collects nothing. There is no account with us, no analytics, no ads, no crash reporting
and no tracking. The full policy is in
[PRIVACY.md](https://github.com/own-audio/podcast-android/blob/main/PRIVACY.md).

## What the app talks to

- **The shows you follow**: their feeds, pictures and episodes, from wherever each feed says.
  Those hosts see your IP address, as with any podcast player.
- **Apple's podcast directory**, when you use Discover without an own.audio catalog: the
  categories' top shows in your country, and the words you search for. With an own.audio
  catalog, Discover asks your server instead.
- **The servers you add**, and only those: your sign-in, your shows, what you play, your
  positions and played marks.
- **Google**, only if you choose **Continue with Google** for an own.audio server. Only the
  Google Play build has it; the F-Droid build has no Google code.

## What stays on your phone

Your shows, episodes, positions, Up Next, downloads and pictures. Your servers and their
sign-ins are kept in one file encrypted with a key in Android's Keystore. own.audio and
Audiobookshelf keep only a sign-in token; a gPodder password is kept, because the protocol
sends it with every request. Nothing goes into Android's cloud backup. Uninstalling the app
removes all of it.

The [log](troubleshooting.md#the-log) stays on the phone unless you share it.

Questions: privacy@own.audio, or [an issue on GitHub](https://github.com/own-audio/podcast-android/issues).
