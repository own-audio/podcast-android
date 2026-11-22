# Changelog

All notable changes to own.audio Podcasts for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

- A book or episode loaded to be shown paused no longer starts playing by itself after the previous one played (the shared spoken player).
- P12: TalkBack headings and labels, layouts that stack at the largest text, tablets in landscape with a navigation rail, keyboard keys (Space, Ctrl+←/→, Esc), misuse tests against a local feed server. The player shows "N min saved" again, takes chapters that arrive after Play, and switches to a finished download in place. An episode loaded after Stop no longer starts by itself.
- The spoken player takes a part's real length from the file when the stated one is missing or wrong, accepts chapters that arrive after loading, and switches to a downloaded copy at the next pause or seek without losing the place.
- P10 and P11: Summary and Ask on own.audio episodes with a transcript (hosted servers); Android Auto (New Episodes, Downloads, Shows, a Chapters folder, a Speed button, search); voice ("continue listening", "play ‹show›"); the Up Next widget; a Continue Listening shortcut; ownpodcast://play links; an hourly refresh in the background.
- `:kit:ownaudio` from music-android: narration starts can carry an Idempotency-Key.
- P8: downloads — your own at once, those of a show's rules (Download New Episodes, Delete When Played, Keep at Most) when Wi-Fi and the battery allow; the Downloads tab; the storage limit; downloads play first and offline lists grey out the rest. The player is now the shared spoken player. A paused episode's position is no longer sent again every time the app goes to the background.
- Settings: skip intervals, speed, smart rewind, Play New Episodes After Up Next, On Mobile Data and Keep Ahead (P9). Audiobookshelf podcast libraries and gPodder sync servers (podfetch, gpodder.net, own.audio) can be added, with their own icons, a switch per library and Sync Now. Each server's source is made once; own.audio episode links are renewed before they expire. Share with the Family for your own shows. Titles escaped twice by a server ("&amp;#128640;") now show their characters.
- `:core:downloads` and `:core:sync` (download workers and index, outboxes, background refresh, live updates), copied from music-android.
- `:player:spoken`'s Media3 player copied from book-android; players wait through long signal gaps instead of failing after ten minutes.
- P6: the podcast screens and player — New Episodes, Shows, a show and its settings, an episode with chapters and show notes, Add a Show (also from feed:// and podcast:// links), Discover (own.audio's directory, or Apple's charts and search), the mini player and the player with Speed, Sleep Timer, Chapters and Up Next; follows feeds with no account and own.audio shows; the device's feeds kept in Room; plays in the background with skip buttons on the notification; the last episode comes back paused after a relaunch.
- The app shell and the own.audio part of P7, ported from music-android: welcome (no account needed), own.audio account, Try the Demo Library, Add a Server with kind detection, Settings with status dots, the server page, Family, Devices, Listening and Logs. A privacy policy (PRIVACY.md).
- `:player:core` with the Media3 playback pieces (link resolver, data source chain, listening cache, session callback, skip commands), copied from music-android.
- P3 and P4: Audiobookshelf podcast libraries (with the feed's other episodes merged in) and own.audio on v2 — the catalogue from the sync feed, New Episodes without a request per show, follow, unfollow and refresh on the server, positions where the newer one wins, and the directory for Discover.
- The podcast library's logic (P2, P5), ported from the iOS app with its tests: shows, episodes and sourced ids, the New Episodes rules, the device's own feeds with the refresh merge, Up Next and playing on, positions waiting to be sent, per-show download rules, the storage limit, and the gPodder sync algorithm.
- `:player:spoken`, the spoken engine's logic, copied from book-android.
- `:player:core` (sleep timer, resume store, storage route, payload check), copied from music-android.
- `:core:servers`, copied from music-android (F3).
- `:kit:ownaudio`, the own.audio /api/v2 client, copied from music-android (F2).
- F1's look from music-android with the podcast blue, the podcast words and the microphone icon.
- `:kit:feed` (P1): the RSS, Atom and Podcasting 2.0 parser and fetcher, ported from the iOS app.
- `:kit:gpodder`: the gPodder API client.
- `:kit:audiobookshelf`, copied from book-android.
- The build skeleton from music-android's F0: both flavours, the module layout, four empty tabs (New Episodes, Shows, Downloads, Discover) in English and Czech, CI, the drift check.
- Implementation plan and the shared Android foundation document.
