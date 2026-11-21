# Privacy policy — own.audio Podcasts for Android

own.audio Podcasts is a podcast player. It fetches the feeds you add, and syncs with the
servers you choose: own.audio, Audiobookshelf, or a gPodder-API server. This page says what the app keeps and what it sends. Last updated
21 November 2026.

## What the app collects

Nothing. There is no account with us, no analytics, no advertising, no crash reporting, no
tracking, and no server of ours that the app reports to. We, the makers of the app, receive no
data from it.

## What the app sends, and to whom

- **To the publishers of the shows you follow**: the app fetches their feeds, artwork and
  episodes from wherever the feed says, as every podcast app does. Those hosts see your IP
  address and what they would see from any player.
- **To Apple's podcast directory**: when you open Discover, the app asks it for its top shows in
  your country (the country and the category, nothing about you); when you search, the words you
  search for; when it shows a show from the charts, that show's id. On an own.audio server with
  a catalogue of its own, Discover asks that server instead.
- **To the servers you add**, and only to them: your sign-in, the shows you follow, the episodes
  you play, where you are in each, and what you have played. What a server does with them is up
  to whoever runs it — you, a friend, or a hosting service with its own policy (for own.audio's
  hosted service: https://www.own.audio/privacy/).
- **To Google**, only if you choose "Continue with Google" for an own.audio server (in the
  Google Play build; the F-Droid build has no Google code): Android's account picker signs you in,
  and the app passes the result to your server.

## What stays on this device

- The servers you added and how to sign in to them, in one file encrypted with a key kept in
  Android's Keystore. A gPodder password is kept there, because the protocol sends it with every
  request; own.audio and Audiobookshelf keep only sign-in tokens.
- Your settings, downloads, covers and saved answers from your servers (so the app works
  offline). Uninstalling the app removes all of it. Downloads can be removed on their own, and
  removing a server removes what the app kept from it.
- Nothing is included in Android's cloud backup: sign-ins are encrypted with a key that never
  leaves this device, and downloads are excluded.

## Children

The app has no age-specific features and collects nothing from anyone.

## Contact

Questions: privacy@own.audio, or an issue at https://github.com/own-audio/podcast-android/issues.
If this policy changes, the new version is published here, with its history in this
repository.
