# Implementation plan: own.audio Podcasts for Android

**own.audio Podcasts** for Android: a native, open-source (MPL-2.0) podcast player. It works with
**plain feeds and no account at all**, and optionally with **own.audio** servers, **Audiobookshelf**
podcast libraries and **gPodder sync** servers (podfetch, gpodder.net, own.audio's gPodder API).
Kotlin, Jetpack Compose, Media3, Android 9+ (API 28). applicationId `audio.own.podcastplayer`,
home-screen name **own.podcast**.

It is a port of [own.audio Podcasts for iOS](https://github.com/own-audio/podcast-ios) (1.0.1): the
same functions, screens, words and look. own.audio servers are spoken to on **`/api/v2` only**.

Read [`docs/android-foundation.md`](docs/android-foundation.md) first. The foundation is built in
[music-android](https://github.com/own-audio/music-android), and the spoken-word engine and the
Audiobookshelf client in [book-android](https://github.com/own-audio/book-android); this app copies
both (P0).

## Progress

| Phase | What | State |
|---|---|---|
| P0 | Copy the foundation (music-android) and the spoken engine + ABS kit (book-android) | ◐ skeleton 2026-11-21 |
| P1 | `:kit:feed`: RSS / Atom / Podcasting 2.0 parser and fetcher | ☑ 2026-11-21 |
| P2 | Podcast library layer, the device's own feeds (`FeedLibrary`), Up Next, pending progress | ◐ domain logic done 2026-11-21; Room and wiring next |
| P3 | Audiobookshelf podcast libraries | ☑ 2026-11-21 (source; screens in P6) |
| P4 | own.audio on v2, follow and unfollow included | ◐ source built 2026-11-21, live read test passes; the follow/play/unfollow round trip on a real account comes with the screens |
| P5 | gPodder sync (`:kit:gpodder`) | ◐ client and sync algorithm done 2026-11-21; live sync with the app |
| P6 | Screens: New Episodes, Shows, show, episode, Discover, search, player, Up Next | ☐ |
| P7 | Welcome, add a source with detection, server page (Podcasts specifics) | ☐ |
| P8 | Downloads, per-show rules, offline, refresh | ☐ |
| P9 | Settings | ☐ |
| P10 | Summaries and Ask (hosted own.audio) | ☐ |
| P11 | Android Auto, widget, deep links, voice, background refresh | ☐ |
| P12 | Localization, accessibility, tablets, misuse tests | ☐ |
| P13 | Release | ☐ |
| P14 | After 1.0: OPML, transcripts, translation | ☐ |

**Where we stopped:** P0 skeleton and F1's look copied from music-android 2026-11-21, and `:kit:audiobookshelf` from book-android. P1 done the same day (`:kit:feed`, every iOS parser test ported, 20 real feeds parse; malformed feeds are retried and HTML entities written into the XML are understood). The gPodder client of P5 is done and tested live against podfetch; the sync algorithm (`GPodderSync`) comes with P5 proper.

## Rules for whoever executes this plan

The same rules as music-android's plan: one phase at a time; read the named iOS files first (the
Swift code is the specification); CHANGELOG and guide with every change; port the Swift tests with the
code; **no `/api/v1`**; no secrets or internal hosts in the repo; the gate is
`./gradlew testDebugUnitTest lintDebug verifyRoborazziDebug` plus `tools/sibling-drift.sh`.

From the iOS app:

- **No server needed.** Everything works with feeds alone. Servers are optional *sources*.
- **Never lose a position.** Written to disk before it is sent.
- **Hidden, not broken**: per-source `Capabilities`.
- Glossary: feed = kanál, show = pořad, episode = epizoda, follow = odebírat, unfollow = Zrušit odběr.

---

## Scope

Everything in podcast-ios 1.0.1 (`music-ios/docs/ios-apps-feature-parity.md`, Podcasts column), plus
what own.audio v2 now allows that iOS hides:

| Kept the same | Different on Android, on purpose |
|---|---|
| Four tabs: New Episodes, Shows, Downloads, Discover | Settings a full-screen page from the gear |
| Sources: This Device (feeds), own.audio, Audiobookshelf podcast libraries, gPodder sync | **own.audio shows can be followed, unfollowed and refreshed** (`POST /podcasts`, `DELETE /podcasts/{id}`, `POST /podcasts/{id}/refresh`); iOS hides these because v2 didn't have them when it was written. Port back to iOS afterwards. |
| The inbox rules, per-show settings (inbox, auto Up Next, skip intro, speed, auto download, delete when played, keep at most) | own.audio's New Episodes come from the change feed plus `GET /me/podcasts/unplayed`, not one request per show |
| Up Next across sources, Play New Episodes After Up Next | Discover asks own.audio's v2 directory (`/podcasts/directory/*`), else Apple's public charts and search |
| Chapters (feed JSON, inline PSC, server, file), speed per show, smart rewind, sleep to end of chapter/episode | No Apple sign-in in 1.0 |
| Summaries and Ask (hosted own.audio) | `pcast://` links handled too (Android has no store rule against it) |
| Family, Devices, Listening, Logs, Accent Color | Language row in Settings |
| Widget "Up Next", `ownpodcast://`, `feed://`, `podcast://` | Glance widget |

After 1.0 (P14): OPML import/export, a transcript viewer, episode translation (hosted). None of these
is in iOS 1.0.1 either.

---

## P0 — Copy the foundation

- From `music-android` (named commit): build, tools, `docs/android-foundation.md`, `:core:*`,
  `:player:core`, `:kit:ownaudio`, the shared screens.
- From `book-android` (named commit): `:player:spoken`, `:kit:audiobookshelf`.
- Make it own.podcast: `app.id=audio.own.podcastplayer`, `app.name=own.podcast`; accent `#0A6E9C` /
  `#29ABE2`; radii card 12, cover 12, mini player 16, sheet top 28; crossfade 200 ms; the podcast words
  (`audio, podcast, family, episode, show, feed, talk, morning`); icon from podcast-ios
  `Design/AppIcon.svg` (family ring + a blue microphone); hero from podcast-ios.
- Tabs: **New Episodes** (`Inbox`), **Shows** (`GridView`), **Downloads** (`DownloadForOffline`),
  **Discover** (`Search`).
- `ServerKind.OWN_AUDIO, AUDIOBOOKSHELF, GPODDER`.

**Done when:** builds with empty tabs; drift check reports only the allowed differences.

## P1 — `:kit:feed`

Port `Packages/FeedKit`. Pure Kotlin, parsing with the `XmlPullParser` API: Android's built-in one
(`Xml.newPullParser()`) in the app, `kxml2` as a test-only dependency for the JVM tests. No feed library.

- Roots: `rss` / `RDF` → RSS; `feed` (Atom namespace) → Atom; HTML or OPML root → `NotAFeed`. Malformed
  XML: escape bare `&` not starting an entity and parse once more. A feed that breaks off after the show
  still yields its episodes.
- Show: title, `itunes:author` (fallbacks owner name / author / managingEditor), description
  (`itunes:summary` / description / subtitle → plain text), link, image (`itunes:image`, RSS image, Atom
  icon/logo), language, categories, explicit, `itunes:type` serial, `itunes:new-feed-url` (followed),
  `itunes:complete`, `podcast:guid`, `podcast:funding`, lastBuildDate.
- Episode id: `guid` → enclosure URL → link → `title|pubDate`. Fields: title, description
  (`content:encoded` > description > summary, HTML → text with paragraphs and bullets, entities decoded,
  Mojibake repaired), dates (`pubDate`, `published`, `dc:date`, `updated`), `itunes:duration` ("1:02:03",
  "62:03", "3723", "3723.5"; null for 0 or more than 7 days), enclosure or `media:content` (url, type,
  length; 0 → null), link, image, season, episode, episodeType, explicit, `podcast:chapters` URL, inline
  Podlove Simple Chapters, `podcast:transcript` (url, type, language), `podcast:person`. Items without an
  enclosure are kept, as the iOS code and its tests do; the library layer hides what can't play.
- Dates: ISO 8601 (with or without fractions, date only) and RFC 822 variants with named zones (UT, Z,
  EDT, EST, CDT, CST, MDT, MST, PDT, PST, CEST, CET), parsed with a fixed locale.
- URLs trimmed, spaces → `%20`, protocol-relative → https, http/https only.
- Chapters JSON (`application/json+chapters`): startTime/endTime/title/img/url/toc, sorted, each ends at
  the next start, `toc:false` kept but not listed; milliseconds.
- **Fetcher**: User-Agent `own.audio Podcasts/<version> (Android …)`; Accept `application/rss+xml,
  application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5`; `If-None-Match` /
  `If-Modified-Since`; no cache; 30 s timeout; **25 MB cap**; report the final URL after redirects (stored
  as the feed URL); the bare OkHttp client (no tokens to publishers).
- Tests: port `FeedParserTests`, `FeedFetcherTests` and the fixtures (`itunes.xml`, `podcasting20.xml`,
  `atom.xml`, `messy.xml`, `notafeed.html`, `opml-not-feed.xml`, `chapters.json`).

**Done when:** all ported parser and fetcher tests pass on the JVM; twenty real feeds (a list in
`kit/feed/src/test/resources/real-feeds.txt`, fetched only by a manual task) parse without error.

## P2 — Podcast library layer, device feeds, Up Next, pending progress

Port `Packages/PodcastLibrary`:

- Models: `Show`, `ShowSettings` (`inInbox` true, `autoQueue` false, `skipIntroSecs` 0 (no skip), `speed` null,
  `autoDownload` 0, `deleteWhenPlayed` false, `keepAtMost` null), `Episode`, `EpisodeKey`, `Chapter`,
  `Transcript`, `ListeningProgress` (`reachedEnd` = the last 30 s or 98 %). Ids: `SourcedEpisode.id =
  "<source>/<showId>|<episodeId>"`, `SourceRef` = `Phone`, `Server(uuid)`, `Library(uuid, libraryId)`.
- `PodcastSource` interface: shows, episodes(showId), episode(key), follow(feedUrl), unfollow,
  updateSettings, refresh(showId, force), progress, allProgress, saveProgress (returns what the source
  holds; newer wins), markPlayed, inbox(limit), dismissFromInbox, playback(key) → `Playback{url,
  isServerFile, isHls, isTranscode}`.
- `Capabilities`: `ADD_BY_URL`, `UNFOLLOW` (new on Android), `REFRESH_ON_SERVER` (new), `SEARCH`,
  `SERVER_DOWNLOADS`, `FAMILY_SHARING`, `SYNCS_PROGRESS`.
- **`FeedLibrary`** (the device's own source): every show with its episodes, validators and
  `latestAtFollow`; state (progress, dismissed); every change written before returning. Store in Room
  (iOS uses one JSON file per show; Room keeps large back catalogues out of memory).
- **Inbox rule**: unplayed, not dismissed, the show has `inInbox`, and first seen after the follow — plus
  the single episode that was newest at the follow (so a new follow isn't empty; not for serial shows).
  Newest first by `publishedAt ?: firstSeenAt`, limit 200.
- **Refresh** of device feeds: 4 at a time; a failing feed stays as it was; known episodes keep
  first-seen; episodes the feed dropped are kept only if half-heard or queued; auto-queue shows append new
  ones.
- **`ListeningStore`** (per server): show first-seen, episode first-seen, dismissals, per-show settings,
  positions (newer wins; played leaves Up Next) — the same inbox rules for server sources. Per-show
  settings stay on the device for every source; they are never sent to a server.
- **Up Next** (`UpNext.swift`): one app-wide queue of entries holding the whole episode + show (lists and
  plays offline). Play Next = insert first; Play Later = append if absent; reorder, remove, clear. On
  finish: the next playable entry; else, with **Play New Episodes After Up Next** (default on), the newest
  inbox episode that can play now and isn't played. Auto-queue shows add inbox episodes first seen since
  the last look (the first look only records the time). Not synced with any server.
- **Pending progress** (`PendingProgress.swift`): one entry per episode (newer replaces), recorded before
  sending, removed when the source has it (unless a newer one was recorded since); sent oldest first at
  launch, when the network returns, when Offline Mode goes off, on every refresh; a failing source is
  skipped for the round; marking played/unplayed goes the same way.
- **Last played** (`last-played.json`: source, episode, show, positionMs, at) written on every 10-s tick;
  restored paused into the mini player at launch; its newer position is stored if the store missed it.

Read: podcast-ios `Packages/PodcastLibrary/Sources/PodcastLibrary/*.swift` and tests.

**Done when:** following three real feeds on the device fills an inbox by the rules; Up Next and pending
progress tests pass.

## P3 — Audiobookshelf podcast libraries

Extend `:kit:audiobookshelf` (copied from Books) with podcasts (port podcast-ios
`AudiobookshelfKit/Podcasts.swift`, `PodcastLibrary/AudiobookshelfPodcastSource.swift`):

- Shows: `GET /api/libraries` (`mediaType == "podcast"`) → `GET /api/libraries/{id}/items?minified=1&
  sort=media.metadata.title&limit=200&page=` (all pages).
- Show detail `GET /api/items/{id}?expanded=1` → `media.episodes` (only what the server holds). For a
  feed-backed show **also fetch the feed** and add its other episodes (matched by GUID, enclosure, title)
  with ids prefixed `feed:`; those play from the publisher and keep positions on the device only. Feed
  cache per show (validators, 10-min reuse on automatic refresh).
- New Episodes: `GET /api/libraries/{id}/recent-episodes?limit=100&page=0` per library + the feed-only
  cache, filtered by the inbox rule.
- Progress `GET /api/me/progress/{itemId}/{episodeId}`, all from `GET /api/me`, save `PATCH
  /api/me/progress/{itemId}/{episodeId} {currentTime, duration, isFinished}` (seconds). Newer wins.
- Playback: the file with `?token=`; ExoPlayer plays Opus/Ogg/WebM itself, so the iOS HLS transcode path
  (`POST /api/items/{id}/play/{episodeId}`, polling the playlist up to 20 s) is needed only for formats
  the device can't decode (check with `MediaCodecList`).
- Subscribe (when the account has `permissions.upload`): `POST /api/podcasts {path: folder.fullPath + "/"
  + sanitizedTitle, folderId, libraryId, media: {metadata: {title, feedUrl}, autoDownloadEpisodes}}`.
  **Unfollow hidden** (it would delete the show for everyone on that server).
- Each ABS library can be shown or hidden on the server page; each is its own entry in the source switch
  when there is more than one.

**Done when:** an ABS test server's podcast library lists, merges feed-only episodes, saves progress.

## P4 — own.audio on v2

`OwnAudioPodcastSource` (port podcast-ios `OwnAudioPodcastSource.swift`, then change the reading to the
feed):

- Catalogue in Room from `/sync/snapshot?kinds=podcast,episode,progress` then `/sync/changes`
  (foundation §9.1). `/events` `library` events → a changes sync.
- **New Episodes**: episodes whose `published_at` is after the show's first sighting on this device
  (`ListeningStore`), from the feed, plus `GET /me/podcasts/unplayed` for the counts on the Shows grid. No
  request per show.
- **Follow** `POST /podcasts {feed_url, visibility?}` with `Idempotency-Key` (a show already followed or
  visible answers 200 without fetching; outbound bucket 30/min — throttle bulk follows); **Unfollow**
  `DELETE /podcasts/{id}` (owner, or household admin for a shared show; "Unfollow ‹title›?" warns when the
  show is shared with the family); **Refresh** `POST /podcasts/{id}/refresh` and `POST /podcasts/refresh`
  (202; new episodes arrive through the change feed).
- Progress `PUT /me/progress/episode/{id} {position_ms, finished, at}` → `{applied, progress}`; offline
  batches through `/sync/push`.
- Playback: `POST /playback-info {kind: "episode", id, capabilities}` → the signed own.audio link for a
  stored episode, or **the publisher's URL** (`reasons: publisher_file`): fetched with the bare client,
  never with the token. Don't store episodes on play (`PUT /episodes/{id}/stored` is a deliberate user
  action, not in 1.0).
- Duration: `duration_ms` is often missing — use the file's length when the player knows it.
- Share with the Family per show: `PUT /podcasts/{id}/visibility` (owner, with a household).
- Discovery catalogue (P6): `GET /podcasts/directory/categories`, `/podcasts/directory?category=&language=`
  (cursor), `/podcasts/directory/search?q=` (results carry `podcast_id` when already followed; `501`
  module off; `502 directory_unavailable`), `/podcasts/directory/similar?podcast_id=|feed_url=`,
  `/podcasts/directory/preview?feed_url=`. Shown when `modules.podcast_search` / `podcast_discovery`.
- Titles: HTML entities decoded; description HTML → text.

**Done when:** against own.audio: follow a feed, see its episodes through the feed, play one from the
publisher and one stored on the server, unfollow, and the inbox fills without per-show requests.

## P5 — gPodder sync (`:kit:gpodder`)

Port `Packages/GPodderKit`, `PodcastLibrary/GPodderSync.swift`:

- Basic auth on **every** call, **no cookies** (podfetch honours a session cookie before credentials, so a
  wrong password would pass after a right one).
- `POST /api/2/auth/{user}/login.json` (podfetch answers wrong credentials with 403); `POST
  /api/2/devices/{user}/{device}.json {caption, type: "mobile"}`, device id `own-audio-podcasts-<8 chars
  of the install id>`.
- Subscriptions: try account-wide `GET /api/2/subscriptions/{user}.json?since=` (404/400/405/undecodable
  ⇒ unsupported), else per device `GET /api/2/subscriptions/{user}/{device}.json?since=`; upload `POST
  …/{device}.json {add, remove}` → `{timestamp, update_urls}` (apply the URL rewrites).
- Episode actions `GET /api/2/episodes/{user}.json?since=`; `POST` in batches of 200, canonical JSON,
  timestamps `yyyy-MM-dd'T'HH:mm:ss` UTC without a zone; actions `new` (unplayed, position 0) or `play`
  with `started: 0`, `position`, `total` (total = position when the length is unknown; played ⇒ position
  = total). Episodes identified by enclosure URL, with `guid` too.
- Sync state `gpodder-<device>.json` {subscriptionsSince, actionsSince, syncedFeeds, sent[episode]}.
  First sync merges and removes nothing; later syncs carry removals both ways; **local changes are
  computed before remote ones are applied** (a server repeating an old "add" must not bring back a show
  just unfollowed); a feed that fails to follow doesn't stop the rest. Pulled actions under this device's
  name are skipped unless clearly newer (≥ 1 s) than held (podfetch updates one row per episode in place
  under the device that created it). Applied ones are marked sent (no echo). 403 on upload = a read-only
  account: still syncs down.
- When: every refresh, ~5 s after follow / unfollow / pause / stop / finish of a device episode, **Sync
  Now**, after adding the account.
- Errors: podfetch's admin account → HTTP 409 → "On podfetch the admin account can't sync: add a separate
  user there and sign in with it." The Nextcloud gPodder Sync app speaks a different API: unsupported.
- own.audio as a gPodder server: Basic with the email + the app key from `GET /me/subsonic-key` (only when
  the user adds it as a gPodder account; normally own.audio is used as an own.audio source).

**Done when:** sync both ways against podfetch and against own.audio's `/api/2`, including the
"unfollowed here, re-added by an old server action" case.

## P6 — Screens

Read the Swift file named for each.

- **Root** (`RootView.swift`): welcome while there's no show and no server; tabs; global deep links; the
  Add a Show sheet for `feed://`/`podcast://` links; the "Continue from another device?" dialog — when a
  server position differs from the local one by more than 30 s (both unplayed, local > 0): **Continue
  from ‹time›** / **Keep this device's position (‹time›)**, "Another device got to X; this device was at
  Y." (skipped during automatic play-on: the newer one wins).
- **New Episodes** (`Episodes/InboxView.swift`): episode rows; an offline row "Offline Mode is on" / "No
  network"; empty "No New Episodes — New episodes of the shows you follow appear here."; pull to refresh
  (forced); top bar: the **source switch** (only with more than one source: **All Sources**, then each
  source with its icon — own.audio first, then This Device, then other servers, each ABS library separately;
  remembered; the Shows tab follows it), **Offline Mode**, **Settings**.
- **Episode row** (`Episodes/Components.swift`): artwork 56 dp, show title (small, muted), episode title
  (semibold; muted when played), a line with the Up Next mark, download badge and "16 Oct · 48 min" / "· 12
  min left" / "· Played" (the year when not this year); a round play/pause button. Greyed at 45 % when it
  can't play offline. At large font scales the row stacks. Swipe start→end: **Played** / **Unplayed**;
  end→start (inbox only): **Dismiss**. Menu: Play; Mark as Played/Unplayed; Play Next / Play Later (or
  Remove from Up Next); Download / Cancel Download / Remove Download / Try the Download Again; Remove from
  New Episodes (inbox only).
- **Episode** (`EpisodeView`): artwork 96 dp, show, title, progress, source label; **Play** / **Resume**,
  **Played** / **Unplayed**, the download button; **Play Next** + **Play Later** or **In Up Next**;
  **Summary** (P10); **Chapters** (feed/server chapters, tap seeks or plays from there); show notes as
  selectable text with links (`LinkAnnotation`); **Episode Website**.
- **Shows** (`Shows/ShowsView.swift`): adaptive grid (min 104 dp) of artwork + 2-line title, grouped by
  source with a header when more than one group; "Can't be reached right now" under a failing source; top
  bar **+** (Add a Show), **Sort**: Title, New Episodes First, Recently Followed; empty "No Shows — Follow a
  show by searching for it or by its feed address." + **Add a Show**.
- **Show** (`ShowView`): artwork 112 dp, title, author, source, description (6 lines, links); segmented
  **All / Unplayed / Downloaded**; rows; "The episodes can't be loaded right now."; **Similar Shows** row
  (own.audio catalogue); pull to refresh this show; ⋯: **Show Settings**, **Website**, **Share Feed** (the
  share sheet with the feed URL), **Share with the Family** (own.audio, owner), **Unfollow** (sources with
  `UNFOLLOW`; "Unfollow ‹title›?" — "Its episodes and positions on this source are removed."; also removes
  downloads and Up Next entries).
- **Show Settings** (`ShowSettingsView`, Cancel / Done): Show in New Episodes; Add New Episodes to Up Next;
  Skip the Intro (Off, 10, 15, 20, 30, 45, 60, 90, 120 s — only when starting from 0); Speed (As in
  Settings, 0.75, 1, 1.25, 1.5, 1.75, 2×); Download New Episodes (Off, Newest 1/3/5/10); Delete When Played;
  Keep at Most (No Limit, 1/3/5/10 Episodes); footer "New episodes download on their own, the newest first;
  unplayed ones only. Settings decides whether this waits for Wi-Fi."
- **Add a Show** (`AddFeedView`): Feed Address (URL keyboard), "The RSS address a show publishes. To find a
  show by name, use Discover."; **Follow On** when more than one target (This Device, own.audio servers,
  ABS libraries the account can add to); normalises `feed://`, `podcast://`, `pcast://` → `https://`, adds
  `https://` without a scheme; "That address is not a podcast feed."
- **Discover** (`Shows/SearchView.swift`, `DiscoverView.swift`, `Core/Discovery.swift`): before typing —
  **Add by Address**; **More Like Your Shows** (own.audio catalogue: up to 6 most recently followed shows,
  round-robin, max 20, followed feeds excluded, "Because you follow ‹show›", "From the shows you follow, not
  from what you play."); **Browse** category tiles (localized names, "N shows" on own.audio) with a header
  naming the source ("From ‹server›'s catalog. Nothing you search for here leaves your own server." or
  "Apple's top shows in each category, and Apple's search."); offline "Discover needs the network; your own
  shows are found offline too." Typing ("Shows and Episodes"): **Your Shows**, **Your Episodes** (inbox,
  downloads, Up Next — offline too), **From ‹catalogue›**. **Category** page (own.audio cursor pages; Apple
  one chart page of 50). **Search result** page: artwork, title, author, genre/episode count, **Follow On**,
  **Follow** → **Following — Open the Show**, Similar Shows.
- Catalogue choice: the first own.audio server whose directory answers with categories; else Apple:
  genres news 1489, society 1324, comedy 1303, education 1304, business 1321, history 1487, science 1533,
  technology 1318, health 1512, truecrime 1488, culture 1301, sports 1545, kids 1305, fiction 1483, religion
  1314, leisure 1502, music 1310, tv 1309, government 1511; chart `https://itunes.apple.com/<cc>/rss/
  toppodcasts/limit=50/genre=<id>/json` then one `lookup?entity=podcast&country=<cc>&id=<ids>` for feed
  URLs; search `https://itunes.apple.com/search?media=podcast&entity=podcast&term=…&limit=30&country=<cc>`
  (drop results without `feedUrl`, dedupe by feed). Country = the device region.
- **Player** (`Player/PlayerView.swift`): Close, Stop (clears mini player, widget, media notification,
  last-played); artwork 280 dp (140 at large font) with the sleep badge; episode title (3 lines), show,
  the current chapter as a button → chapters; scrubber (seek on release), elapsed and "X left" at the
  current speed; "No signal — waiting" or "8 min saved"; skip back, play/pause, skip forward; tools: **Speed**
  ("1.5×"), **Sleep**, **Chapters** (only with chapters), **Up Next** (tinted when not empty), **Output**;
  the engine's error line.
- Sheets: **Speed** (as Books, footnote "Pitch stays the same at any speed. A show can keep its own speed in
  its settings."), **Sleep Timer** (5/15/30/45/60, End of Chapter, End of Episode, Add 10 Minutes, Turn
  Off), **Chapters**, **Up Next** (artwork 44 dp, show, title; tap plays now; drag to reorder; swipe to
  remove; **Clear Up Next**; "Then the newest new episodes play on."; empty "Nothing Up Next — Add episodes
  with Play Next or Play Later from their menu.").
- **Mini player**: artwork 40 dp, title, "No signal — waiting" / "X left" / show, play/pause, skip forward,
  2 dp progress along the bottom.
- Tablets: the navigation suite as a rail/drawer; keyboard Space, Ctrl+←/→, Esc.

Playback specifics on top of `:player:spoken` (port `AppModel` play paths, `Core/PartLocator.swift`,
`PositionReporter.swift`):

- An episode = one part. Speed = the show's or the default. **Skip the Intro** only from 0. Chapters:
  inline (PSC or the server's) → the feed's `podcast:chapters` JSON (fetched at play, cached for offline)
  → embedded in the file (B3's readers) with a 6-second limit; sorted, past-the-end dropped, each ends at
  the next; unknown length: the last one runs +1 h.
- Played = the last 30 s or 98 %; played episodes leave the inbox; finishing triggers the download rules.
- Position reports every 10 s → memory + last-played file; to the source at most every 60 s while playing
  and at once on pause / seek / stop / another episode / finish / background.
- A fresh link is asked **once per load** (an iOS bug asked thousands of times a second for a broken file).
- Artwork for the notification at 900 px, not the multi-MB original.

**Done when:** every screen works with device feeds, own.audio and ABS in light, dark, Czech and 200 %;
screenshots recorded; guide pages written.

## P7 — Welcome, adding a source, server page

Foundation §10, with:

- Welcome (`Servers/OnboardingView.swift`): tagline **"Your podcasts. No account, no server needed."**;
  card **Use own.audio** ("An own.audio account keeps your shows and where you are in them on every
  device."): Create an own.audio Account, Sign In, Try the Demo Library ("Sample shows on a shared own.audio
  server, no account needed."); **Or listen without an account**: **Find a Show** (Discover in a sheet),
  **Add a Feed by Address**, **Add a Server**.
- **Add a Server** (`Servers/AddServerView.swift`): **Kind** Automatic (default) / own.audio /
  Audiobookshelf / gPodder sync with a footer per kind; Address (`own.audio` or
  `https://podcasts.example.com`), Email / User Name / Email or User Name, Password; Google for own.audio
  when offered; two-factor step; Cancel / Add.
- **Detection** (port `PodcastLibrary/ServerDetector.swift`, rewritten for v2), per candidate (https
  first), 6 s, no cookies:
  1. `GET /.well-known/own-audio` → own.audio (v2 revision ≥ 16). (iOS asks `/api/v1/server`.) If nothing
     answers at all, skip the host.
  2. `GET /api/v1/sys/config` with `rssFeed` and `basicAuth` → **podfetch** (a third-party server's own
     path, not own.audio's v1).
  3. `GET /status` with `app == "audiobookshelf"` (or `serverVersion` and `isInit`) → Audiobookshelf.
  4. `GET /api/2/devices/own-audio-probe.json` → 401/403 → a generic gPodder server.
  Then: own.audio → password grant; ABS → `POST /login`, at least one podcast library ("This account has no
  podcast library on the server."), `permissions.upload` recorded as "can add shows"; gPodder → login,
  register the device, keep the password, mark `software = "podfetch"` when step 2 answered.
- Address mapping also turns `app-X.own.audio` into `api-X.own.audio` (a console address typed instead of
  the API).
- Server page extras: **Kind** (podfetch shown as "podfetch"), **Version**; **Libraries** (ABS, a switch per
  podcast library); **Sync** (gPodder: **Sync Now**, **Last Synced**, footer "This device's shows and
  positions are kept in step with the account, as every other app signed in to it does."); Remove Server
  (own.audio/ABS: their shows leave the device; gPodder: shows stay, no longer synced).
- Settings server rows have a status dot (green answers, orange unreachable, grey unknown).

**Done when:** each kind is detected from a bare address and added; the server page works for each.

## P8 — Downloads, per-show rules, offline, refresh

Foundation §8, with (port `Core/DownloadManager.swift`, `PodcastLibrary/DownloadRules.swift`,
`EvictionPolicy.swift`, `Core/RefreshPolicy.swift`):

- Two queues: **user downloads** (expedited work) and **rule downloads** (constrained: unmetered when Wi-Fi
  only is on, battery not low; asking for an episode a rule is fetching moves it to the quick queue).
- File extension from the first bytes, else the MIME type; an HLS-only episode → "This episode can't be
  downloaded: the server only streams it."
- **Per-show rules** (pure, tested): newest first; delete played downloads when Delete When Played;
  download the newest `autoDownload` unplayed, not-downloaded episodes (never the back catalogue); Keep at
  Most N (new downloads count against it, the oldest go, planned downloads dropped first); never the
  playing episode. Run after each refresh, after a show's settings change, when an episode finishes; not
  offline.
- Storage cap (No Limit / 1 / 2 / 5 / 10 GB): played first, then least recently used, never the playing one.
- **Downloads** tab: show, episode, source label, size or progress; grouped by source ("Removed server"
  leftovers still play); swipe/menu **Remove**; Downloading N; Retry N Failed; **Remove All Downloads** with
  "<size> on this device."; empty "Nothing Downloaded — Episodes you download appear here, with the source
  each came from, and play with no network."
- **Offline**: lists read only device feeds and Room (servers would time out); rows that can't play are
  greyed; `canPlay` = online, downloaded, or whole in the listening cache.
- **Refresh order**: send pending positions → gPodder syncs → device feeds → each server (`refresh`) →
  reload lists → download rules → auto-queue. Triggers: launch, foreground, a settled network change while on
  screen, pull to refresh (always), Offline Mode off, the periodic job, a 15-min timer while open. Automatic
  ones wait at least 15 min since the last and never run in the background while playing. In the background
  only pending positions are sent on a network change.

**Done when:** rules tests ported and passing; an automatic download happens on Wi-Fi after a refresh;
offline lists and playback work.

## P9 — Settings

`Settings/SettingsView.swift`:

1. **Servers** (with icons and status dots; swipe Remove; Add a Server; footer "Shows you follow on this
   device need no server. …").
2. **Downloads and Offline Mode**: Downloads, Offline Mode, Download on Wi-Fi Only (on), Keep at Most.
3. **Playback**: Skip Back (5, 10, 15, 30, 45, 60, 90; 15), Skip Forward (30), Speed (0.75–2×, 1), Smart Rewind
   (on), **Play New Episodes After Up Next** (on). For own.audio the default speed is also saved to
   `PATCH /me/settings` `playback.speed`; the skip intervals stay on the device (v2 keeps per-account
   skip intervals only for audiobooks).
4. **Streaming**: On Mobile Data Stream / Downloaded Only (a feed has no Saver); Keep Ahead 5 / 10 / 15 min (10).
5. **Accent Color** (App Color = podcast blue).
6. **Language**.
7. **About** (footer "own.audio Podcasts is free and open source (MPL-2.0). It talks only to the feeds you
   follow, Apple's podcast directory and the servers you add …").

## P10 — Summaries and Ask (hosted own.audio)

Port `OwnAudioKit/EpisodeAI.swift`, `Episodes/InboxView.swift` `EpisodeSummarySection`:

- Visible when the episode is from an own.audio server, has a transcript (`transcript_url`), and discovery
  has `modules.summaries`.
- **Summary** with segmented **Short / Medium / Long** (default Medium): `GET
  /summaries/episodes/{id}/summary?language=<app language>&length=` (404 → none) shows the text and "Cost
  $0.012"; else an explanation + **Summarize Episode** (`POST`, "Summarizing…").
- **Ask**: earlier turns (question bold, answer muted, cost) from `GET …/questions`; a field "Ask about this
  episode" (1–4 lines), **Ask** (`POST …/ask {question}`); a footnote on cost and privacy; **Clear
  Questions** (`DELETE …/questions`, confirm).
- Errors: 402 "There isn't enough credit in the family for this.", 409 "Today's limit for paid work is
  reached. Try again tomorrow.", 400 "This episode has no transcript to read." Prices in USD, three decimals
  below $0.10.

**Done when:** against a hosted test account a summary is read, and Ask is shown (tests don't pay for new work).

## P11 — Android Auto, widget, deep links, voice, background refresh

Foundation §11, with:

- **Auto** (port `Core/CarPlayContent.swift`): root **New Episodes** (playable only, ≤ 100), **Downloads**
  (finished, newest first), **Shows** → episodes; rows with artwork and a progress extra; a speed custom
  action cycling 1 → 1.25 → … → 2 → 0.75 → 1; chapters as the queue; empty "Follow a show on your phone".
- **Widget** "Up Next": small (one) and medium (three): the episode in the player, then started ones, then the
  newest new; "Nothing new yet"; tap → `ownpodcast://play/<escaped id>`.
- **Deep links**: `ownpodcast://play/<id>`; `feed://…`, `podcast://…`, `pcast://…` → Add a Show pre-filled;
  optionally `https` links ending in `.rss`/`.xml` through the share target ("Share to own.podcast" adds the
  feed).
- **Voice**: "continue listening" (player episode → last played → under way → newest), "play ‹show›"
  (episode under way, else newest unplayed) — port `Intents/PodcastIntents.swift`.

## P12 — Localization, accessibility, tablets, misuse tests

- `values-cs` from podcast-ios `Localizable.xcstrings` (429 keys) and its `scripts/make-catalog.py` table.
- End-to-end fixtures: port `UITests/Fixtures/` and `serve.py` (a local feed server with byte ranges) so the
  suite needs no internet.
- Misuse: hammered player, killed mid-download, a feed that answers HTML, a feed of 5,000 episodes, a source
  removed while one of its episodes plays, a dead gPodder server, offline for a day then back.

## P13 — Release

Foundation §14, with podcast-ios `AppStore/` texts. `PRIVACY.md` names the feed hosts the user follows,
Apple's podcast directory (charts, search words, lookup ids) and the servers the user adds.

## P14 — After 1.0

- **OPML import/export** (`OpenDocument` / `CreateDocument`): import follows each feed on the chosen target
  (own.audio throttled to the 30/min outbound bucket); export the device's shows (or a server's via `GET
  /podcasts`).
- **Transcript viewer**: `GET /episodes/{id}/transcript` (own.audio) or the feed's transcript file (VTT,
  SRT, JSON, HTML), timed lines highlighted while playing, tap to seek.
- **Episode translation** (hosted, `modules.translation`): `POST /translation/episodes/{id}/quote`, `POST
  /translation/episodes/{id}`, `GET /translation/episodes/{id}`, `translation` events, the translated
  episode as its own item (`playback-info` kind `translation`, progress kind `translation`).

---

## Server work this app would like (none blocks 1.0)

1. **Podcast chapters in the v2 episode record** (`chapters_url` or parsed chapters), so own.audio-stored
   episodes have chapters without fetching the feed.
2. **A cross-show "new episodes" list** (`GET /me/podcasts/new`) if deriving the inbox from the feed proves
   heavy on large accounts.
3. **The public demo server on 1.0.4 or later** (the demo account in discovery, v2 podcast routes).
4. **Real `Content-Type`** on locally stored episodes.

## Open questions for the maintainer

1. Ship follow / unfollow / refresh for own.audio shows on Android (v2 has them) and port it back to
   podcast-ios? (Recommended.)
2. Build New Episodes for own.audio from the change feed (recommended) rather than iOS's one request per show?
