# Android foundation: what the three own.audio apps share

This document is the same in `music-android`, `book-android` and `podcast-android`. It describes
the parts every app has: the build, the look, the own.audio client, servers and addresses, the
player core, downloads, sync, sign-in, settings pages and system integrations. Each app's
`IMPLEMENTATION_PLAN.md` builds its own screens and engine on top of it.

When this file changes in one repository, copy it to the other two in the same week, and note in
each CHANGELOG where the change came from.

**Reference apps.** Every Android app is a port of its iOS sibling:

| Android | iOS reference | Store name | Home-screen name | applicationId |
|---|---|---|---|---|
| `music-android` | [music-ios](https://github.com/own-audio/music-ios) | own.audio Music | own.music | `audio.own.musicplayer` |
| `book-android` | [book-ios](https://github.com/own-audio/book-ios) | own.audio Books | own.book | `audio.own.bookplayer` |
| `podcast-android` | [podcast-ios](https://github.com/own-audio/podcast-ios) | own.audio Podcasts | own.podcast | `audio.own.podcastplayer` |

The iOS code is the specification. When this plan and the iOS code disagree about behaviour, the
iOS code wins, unless this plan says on purpose that Android differs, and why. Each phase names
the Swift files to read before writing the Kotlin.

The server is [own-audio/server](https://github.com/own-audio/server). Its contract is
`docs/api/openapi-v2.json` there, with `docs/api-v2-first.md` and `docs/api-v2-rules.md`.

---

## 1. Principles

Taken from the iOS apps and kept here word for word in spirit:

1. **One app, many servers.** Each app speaks own.audio and the third-party servers its iOS
   sibling speaks. A feature a server lacks is **hidden, not broken**: screens ask the source what
   it can do (`Capabilities`), never the server kind.
2. **own.audio on `/api/v2` only.** There is no `/api/v1` code in these apps at all, no
   fallback, and no Subsonic path into own.audio. The iOS apps still call v1 in a few places
   (§5.4). Each of those calls has a v2 replacement, and Android uses the replacement from the start.
3. **own.audio is one server among the others, never a funnel.** It gets the first card on the
   welcome screen because it is the only way to use the app without running a server. Beyond
   that it is not preferred anywhere.
4. **Never lose a position.** Every position and play is written to disk before it is sent.
5. **Small and native.** Kotlin, Jetpack Compose, Media3. No cross-platform framework, no
   Firebase, no analytics, no crash reporter, no ads. Server clients written by hand on OkHttp.
   Each new dependency needs a sentence in `docs/dependencies.md` saying why. Watch the APK size
   on every release (budget: §2.6).
6. **The look is the iOS app's look**: the same structure, colours, brand, wording, icons and
   behaviour, drawn with Compose. Android conventions win where the platform expects them: system
   back, edge-to-edge, the media notification, the share sheet, the photo picker, the system font
   scale (§3.1).
7. **No lock icons** for private content. "Private" means "not shared with the family". Family-shared
   content gets a people icon. Nothing else gets a badge.
8. **English and Czech together**, every string, with plurals.
9. **Public from the first commit.** No own.audio internals: no internal hosts, no test
   accounts, no team ids, no signing material, no references to closed repositories. Comments
   explain *why*, for an outside reader.

---

## 2. Repository and build (phase F0)

### 2.1 Toolchain

- Gradle wrapper, Android Gradle Plugin and Kotlin: the current stable versions when F0 starts.
  AGP 9 has built-in Kotlin, so **never apply `org.jetbrains.kotlin.android`**. Apply
  `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization`.
- `compileSdk`/`targetSdk`: the newest stable. `minSdk` **28** (Android 9). Below that,
  `DynamicsProcessing` (the music EQ) and several Media3 paths are missing.
- Java toolchain 17 for sources, built with Android Studio's bundled JBR (export `JAVA_HOME`).
- KSP only for Room. **No Hilt** (it fought AGP 9's built-in Kotlin). Use a hand-written
  `AppContainer` (§2.4).

### 2.2 Libraries (the whole list; anything more needs a reason in `docs/dependencies.md`)

| Area | Library | Why |
|---|---|---|
| UI | Compose BOM, `material3`, `material-icons-extended` (or a trimmed icon set), `navigation-compose`, `activity-compose`, `lifecycle-*-compose` | Native UI |
| Adaptive | `material3-adaptive-navigation-suite`, `material3-adaptive` | Tablet sidebar / list-detail (the iPad layouts) |
| Playback | `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`, `media3-exoplayer-hls` (Audiobookshelf transcodes) | The player, media session, Android Auto |
| Network | OkHttp 5 (or the newest stable major) | All HTTP, SSE and NDJSON by hand |
| JSON | `kotlinx-serialization-json` | No reflection, R8-friendly |
| DB | Room (runtime, ktx, compiler via KSP) | Catalogue, downloads index, outboxes |
| Settings | DataStore Preferences | Settings, small state |
| Background | WorkManager | Downloads, uploads, refresh, flushing outboxes |
| Images | Coil 3 (`coil-compose`, `coil-network-okhttp`) | Covers; custom cache keys (§7.3) |
| Widgets | Glance (`glance-appwidget`, `glance-material3`) | Home-screen widget |
| Google sign-in | `androidx.credentials`, `credentials-play-services-auth`, `googleid` | Only in the `play` flavour (§10.3) |
| QR | ZXing `core` (encoding only) | Family invitation QR codes |
| Tests | JUnit 4, kotlinx-coroutines-test, Turbine, OkHttp MockWebServer, Robolectric, Compose ui-test, Roborazzi | §13 |

No Retrofit: the iOS clients are hand-written and port line by line to OkHttp calls, and SSE,
NDJSON and signed uploads need raw OkHttp anyway.

### 2.3 Module layout

The same names in all three repos, so the copied modules diff cleanly (§2.7). Package root
`audio.own`.

```
:app                       the app, screens, AppContainer, widgets, Auto browse tree
:core:designsystem         tokens, theme, accent choice, brand (mark, wordmark, hero), shared composables
:core:strings              en + cs resources shared by core modules (app strings live in :app)
:core:common               composite ids, clock, logging (ring buffer), result types, dispatchers
:core:network              OkHttp setup, User-Agent, problem decoding, SSE + NDJSON readers, canonical JSON
:core:servers              ServerRecord store (encrypted), address scopes, racing, network monitor, offline switch, answer cache
:core:sync                 Room database base, outboxes (pending progress, pending changes), WorkManager glue
:core:downloads            download workers, index, eviction, Wi-Fi only, storage-route retry
:player:core               Media3 service base, session, notification, audio focus, sleep timer, resume store,
                           listening cache (SimpleCache), playback-link resolver, storage-route fallback, payload check
:kit:ownaudio              own.audio /api/v2 client (port of OwnAudioKit), pure Kotlin + OkHttp, no Android
```

Per app, added on top:

| Music | Books | Podcasts |
|---|---|---|
| `:kit:subsonic`, `:kit:jellyfin`, `:library` (MusicLibrary), `:player:music` (gapless, crossfade, EQ, ReplayGain, spectrum) | `:kit:audiobookshelf`, `:library` (BookLibrary), `:player:spoken` (book timeline, chapters, smart rewind, look-ahead) | `:kit:feed` (RSS/Atom parser), `:kit:audiobookshelf`, `:kit:gpodder`, `:library` (PodcastLibrary), `:player:spoken` (copied from Books) |

Rules:

- `:kit:*` modules are plain Kotlin/JVM (no Android). They are tested with MockWebServer on the
  JVM, fast.
- `:player:*` knows items by an **opaque id** and nothing about servers. The app joins sources
  to the player (as `AppModel` does on iOS).
- Feature screens live in `:app` under one package per tab (`home`, `library`, `player`,
  `settings`, `servers`…), like the iOS `App/` folders. Split them into `:feature:*` modules only
  if build times demand it. The iOS apps are one target and that has been fine.

### 2.4 App shell

- `OwnAudioApp : Application` creates `AppContainer` (hand-wired constructor injection) and
  implements `Configuration.Provider` (WorkManager with our worker factory) and Coil's
  `SingletonImageLoader.Factory`.
- **The playback dependencies are wired eagerly in `AppContainer`**, not lazily from the UI: Android
  Auto, Assistant and the widget can bind to the media service without any activity having
  started.
- One `AppModel`-like state holder per concern instead of one 1,800-line class: `ServersModel`,
  `LibraryModel`, `PlaybackModel`, `DownloadsModel`, `SettingsModel`. Each exposes `StateFlow`s.
  View models expose `@StringRes` ids, never built strings.
- `MainActivity` is a single activity, edge-to-edge, with `navigation-compose`. The full player is a
  full-screen destination with its own enter/exit transition (it is a cover on iOS). Settings is a
  full-screen destination from the gear on the first tab (a sheet on iOS; a sheet with a long
  scrolling list doesn't work well on Android).

### 2.5 Build variants

- `debug` / `release`. Release has R8 with optimisation, resource shrinking and
  `proguard-rules.pro` for kotlinx.serialization.
- Product flavours: **`play`** (Google sign-in through Credential Manager) and **`foss`** (no
  Google Play services at all; the Google button is not shown). The `foss` build is the one for
  F-Droid and GitHub release APKs. Everything else is identical.
- App identity in `gradle.properties` (`app.id`, `app.name`, `app.versionName`, `app.versionCode`), read
  by the convention plugin. `versionCode` = commit count at release time, as the iOS build numbers are.

### 2.6 Convention plugins, lint, CI, size budget

- `build-logic/` with `ownaudio.android.application`, `ownaudio.android.library`,
  `ownaudio.android.library.compose`, `ownaudio.jvm.library`. They set the SDKs, Java 17,
  `generateLocaleConfig = true` (per-app language in system settings), and **i18n lint checks as
  errors**: `HardcodedText, MissingTranslation, ExtraTranslation, StringFormatInvalid,
  StringFormatMatches, MissingQuantity, SetTextI18n`.
- GitHub Actions `ci.yml`: `./gradlew assembleDebug testDebugUnitTest lintDebug verifyRoborazziDebug`
  on Ubuntu + JDK 21. Skip docs-only changes. Cancel runs superseded by a newer push.
- `tools/apk-size.sh`: prints the release APK size and fails above the budget, which is set at
  the first release (start at 12 MB and lower it to the real size plus 20 %).
- `.gitignore`: `local.properties`, `*.jks`, `keystore.properties`, `*.local.env`, `.idea/`
  (do not track it: it holds device serials), `build/`, `.gradle/`, `.kotlin/`.

### 2.7 Drift check between the three repos

`tools/sibling-drift.sh` compares this repo with `../music-android`, `../book-android` and
`../podcast-android` when they exist next to it:

- **Tier 1, must be byte-identical:** `build-logic/`, `gradle/libs.versions.toml`, the wrapper,
  `docs/android-foundation.md`, `:core:common`, `:core:network`, `:core:servers`, `:kit:ownaudio`,
  `:player:core`, and `:core:designsystem` except `Accent.kt` (§3.2). Books and Podcasts also
  share `:kit:audiobookshelf` and `:player:spoken`.
- **Tier 2, diffed for reading:** `:core:downloads`, `:core:sync`, the settings screens,
  onboarding, family, devices, listening, logs.
- Shared string keys must have the same English and Czech wording, with an allowlist for the
  intended differences ("books" vs "songs").

It is a local check, run before a phase is ticked; CI can't see the sibling repos. The iOS apps
copy code without such a script and drift has cost them fixes ported by hand three times, which is
why Android adds one.

**F0 done when:** an empty app with the four or five tabs (placeholders) builds in both flavours,
installs beside any other own.audio app, CI is green on GitHub, lint has no warnings, and the
README, LICENSE (MPL-2.0), CHANGELOG, CLAUDE.md and `docs/dependencies.md` exist.

---

## 3. The look (phase F1)

### 3.1 How "the iOS look" translates to Android

| iOS | Android |
|---|---|
| `TabView` with SF Symbols | Bottom `NavigationBar` on phones; `NavigationSuiteScaffold` switches to a rail or permanent drawer on tablets (the iPad sidebar). Same tab order, names and icons (Material Symbols equivalents, table in §3.5). |
| `NavigationStack` with large titles | `LargeTopAppBar` collapsing to a small one; same titles. |
| `.sheet` with Done/Cancel | `ModalBottomSheet` for short choosers (speed, sleep timer, queue, sort); full-screen dialog destinations with Cancel/Done in the top bar for forms (Add Server, account sheets, show settings). |
| `.fullScreenCover` player | Full-screen destination, slides up from the mini player. |
| Swipe actions on rows | `SwipeToDismissBox` for the one destructive or main action iOS puts on the swipe, plus the same actions in a long-press menu. Every swipe action must also exist in the menu (the Mac build on iOS already requires that). |
| Context menus (long press) | `DropdownMenu` anchored to the row's **⋯** button, also opened by long press. |
| `.searchable` | `SearchBar` in the Search tab, opened with the keyboard when the tab is chosen (as iOS 26+ does). |
| `Toggle`, `Picker` in forms | `Switch` rows; pickers as a row showing the value that opens a dialog or menu of choices. Settings rows mirror iOS's grouped list: section headers, footers in muted text. |
| Materials (`.regularMaterial`) | A surface tonal colour with ~92 % opacity over content. Blur only on Android 12+ (`Modifier.blur` on the player background), a plain dimmed cover below. |
| Dynamic Type | Respect the system font scale with `sp`; test at 200 %. |
| Haptics | `LocalHapticFeedback` on the same controls. |
| SF font | The system font (Roboto/Google Sans). **Monospaced digits** for every time and track number: `fontFeatureSettings = "tnum"`. |

Do **not** imitate iOS chrome literally: no fake iOS navigation bars, no iOS back chevrons, no iOS
switches. The goal is that someone who knows the iOS app finds every screen, control and word in the
same place.

### 3.2 Tokens (`:core:designsystem`, `OwnAudioTokens.kt`)

The same neutrals in all three apps. They come from the iOS `Theme.swift`:

| Token | Light | Dark |
|---|---|---|
| background | `#FFFFFF` | `#000000` |
| backgroundAlt | `#F7F7F8` | `#0C0C0E` |
| card | `#FFFFFF` | `#131316` |
| foreground | `#1D1D1F` | `#F5F5F7` |
| muted | `#6E6E73` | `#98989D` |
| border | `#E5E5E7` | `#1D1D1F` |
| success | `#1E8E3E` | `#30D158` |
| error | `#C41E3A` | `#FF453A` |
| mark arcs | `#D2D2D7` | `#2C2C2E` |
| mark core | `#6E44FF` | `#6E44FF` |
| family dots | `#FABB05 #29ABE2 #FF3B30 #FFCC00 #34C759` (clockwise from top) | same |

The **accent** is the only token that differs between the apps (`Accent.kt`, Tier 2 in the drift check):

| App | Light | Dark |
|---|---|---|
| Music | `#D63A6A` | `#FF6482` |
| Books | `#7A4A2B` | `#D7A97C` |
| Podcasts | `#0A6E9C` | `#29ABE2` |

- Spacing: xs 4, sm 8, md 12, lg 16, xl 24, xxl 32; screen edge 16 (dp).
- Radii: Music cover 8, mini player 14, library cards 14, mini cover 6, row cover 4. Books and
  Podcasts card 12, cover 12, mini player 16, sheet top 28. (Copy each app's own values, as iOS does.)
- Minimum touch target **48 dp** (iOS 44 pt).
- No shadows. Depth comes from surfaces.
- Cover crossfade: Music 300 ms, Books/Podcasts 200 ms.
- A `TokenTest` checks the Kotlin values against `docs/design-tokens.json` in this repo, which is
  the Android mirror of the iOS `Theme.swift` values.

The theme builds a Material 3 `ColorScheme` from these tokens (primary = accent, surface =
background/card, onSurfaceVariant = muted, outline = border, error = error) so stock components
pick them up. **No dynamic colour (Material You)**: the apps have their own colour, as on iOS.

### 3.3 Accent choice

Settings → **Accent Color**: swatches App Color (the app's accent), Blue, Indigo, Purple, Pink or
Brown (whichever isn't the app's own), Red, Orange, Yellow, Green, Teal, Graphite. A ring marks the
chosen one. Footer: "The icon and the widgets keep the app's color." It is stored in DataStore as
`accentColor` and applied at the theme root. The player, icon, widget and the own.audio mark keep
the app's colour. Port `AccentChoice.swift` (the swatch values are there).

### 3.4 Brand (`:core:designsystem/brand`)

Port from each iOS app's `App/*/Core/Brand.swift`:

- **`OwnAudioMark`**: a Compose `Canvas` on a 100-unit construction. Core circle 22u pulsing
  1→1.25 (cubic (0.21, 0.98, 0.6, 0.99), 420 ms, reversing); two arcs (trim 0.1151…0.3849 at 40u,
  stroke 3.5u, offset y −1.23u; trim 0.1190…0.3810 at 60u, stroke 2.5u, 0.6 opacity, offset
  y −0.4u) spinning 360° linear over 6 s; five dancers at 0/72/144/216/288°, each a head (10u at
  y −36u) and two hands (5u at x ±8.5u, y −35u), swaying ±8° and scaling 0.98–1.02 over 4 s.
  Below 24 dp: five static dots (9u). Variants: `tribe()` (family colours + violet core, the welcome
  screen at 120 dp) and single-colour in the app's accent. **No animation** when the system's
  "Remove animations" is on (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) or in UI tests.
- **`RotatingWordmark`**: "own" (foreground) + "." (accent) + a slot (muted) cycling the app's
  words every 1.8 s, sliding ±16 dp with opacity over 600 ms (cubic (0.77, 0, 0.175, 1)). The slot is
  as wide as the widest word so "own." never moves. Accessibility label "own.audio". Words: Music
  `audio, music, family, playlist, song, beat, dance, party`; Books `audio, book, family, story,
  chapter, novel, page, bedtime`; Podcasts `audio, podcast, family, episode, show, feed, talk,
  morning`. Weight heavy, 32 sp.
- **`HeroBackground`**: the iOS `OwnAudioHero` images (light and dark PNG, copied into
  `res/drawable-nodpi`), cropped to fill, under a background-coloured veil at 0.72 opacity.
- **App icon**: adaptive icon from the iOS `Design/AppIcon.svg` (and `AppIcon-dark.svg` for the
  monochrome/themed layer): the five family figures around the app's object (a note in red for Music,
  a closed book in brown for Books, a microphone in blue for Podcasts). Foreground and background
  layers as vector drawables with the 66 dp safe zone respected. `tools/render-icons.mjs` (or a Python
  script) produces the Play Store 512 px icon. The home-screen label is `own.music` / `own.book` / `own.podcast`.
- **Server logos**: own.audio (light/dark), and per app Jellyfin (CC BY-SA 4.0), OpenSubsonic
  (Apache-2.0); Audiobookshelf and gPodder servers' own icons fetched at run time (§6.5). Note the
  licences in `docs/third-party-assets.md`.
- **Google button**: drawn by the app ("Continue with Google", the G logo, white with a grey outline
  in light, near-black with a lighter outline in dark), matching the iOS buttons.

### 3.5 Icons (SF Symbols → Material Symbols)

Keep one mapping table in `docs/icons.md`; the important ones:

| iOS | Android |
|---|---|
| `house` | `Home` | 
| `square.stack` / `books.vertical` / `square.grid.2x2` | `LibraryMusic` / `LibraryBooks` / `GridView` |
| `music.note.list` | `QueueMusic` |
| `arrow.down.circle` (+ `.fill` when downloaded) | `Download` / `DownloadDone` (circled variants) |
| `magnifyingglass` | `Search` |
| `tray` | `Inbox` |
| `gearshape` | `Settings` |
| `wifi` / `wifi.slash` | `Wifi` / `WifiOff` |
| `person.2.fill` (shared with family) | `Group` |
| `star` / `star.fill` | `StarOutline` / `Star` (Music) — Books uses a heart: `FavoriteBorder` / `Favorite` |
| `moon.zzz.fill` | `Bedtime` |
| `gobackward.15` / `goforward.30` | `Replay` / `Forward` with the number drawn in (`Replay10`/`Forward30` exist for some values; build the others as icon + text overlay) |
| `server.rack` | `Dns` |
| `sparkles` | `AutoAwesome` |

Never `Lock` anywhere.

**F1 done when:** a design catalogue screen (debug builds only) shows every token, the accent
swatches, the mark in both variants, the wordmark, the hero and the shared components (cover art
with placeholder, mini player shell, offline banner, section card, load-state/empty-state,
provider buttons, row with badges), and Roborazzi screenshots of it pass in light, dark, Czech and
200 % font scale.

---

## 4. Shared composables (also F1)

Ported from the iOS `LibraryComponents.swift`, `DownloadsView.swift`, `MiniPlayerBar.swift`:

- `CoverArt(item, size, radius)`: Coil image keyed by the cover key (§7.3), placeholder square in
  backgroundAlt with the app's symbol, 0.5 dp border, crossfade.
- `LoadStateView`: spinner → content, or "Couldn't Load" + **Try Again**.
- `EmptyState(title, text, action?)`: the iOS `ContentUnavailableView` look (large symbol, title, muted text).
- `OfflineBanner`: the texts per app (Music "No network · downloaded music only", "Offline mode ·
  downloaded music only" + **Turn Off**, "Mobile data · downloaded music only" + **Stream**; Books and
  Podcasts the same with books/episodes).
- `MiniPlayerBar` shell: floating rounded surface above the navigation bar, cover 40 dp, title,
  subtitle, play/pause with a spinner while loading, one secondary button (next track for Music,
  skip forward for the spoken apps), a 2 dp progress line (Music along the top with the buffered part
  at 30 %, Books/Podcasts along the bottom).
- `ErrorAlert`: the shared "That Didn't Work" dialog.
- `ProviderButtons`: Google (and Apple if it is ever added, §10.4).
- `SettingsRow`, `SettingsSection(header, footer)`, `ValueRow` (label + value + chevron), `SwitchRow`.
- `DownloadButton` (arrow → ring with progress → check; check asks **Remove Download?**; failed →
  retry) and `SetDownloadButton` (arrow → ring with "Downloading 3 of 10" → filled arrow with
  **Remove These Downloads**).
- `NowPlayingIndicator` (animated bars, static when animations are off), `FamilyBadge` (people icon),
  `DownloadBadge`.
- `QrCode(text)` (ZXing encoder → `ImageBitmap`).

---

## 5. own.audio client `:kit:ownaudio` (phase F2)

A port of `Packages/OwnAudioKit` from the iOS apps (start with book-ios's copy, which has the
sorted-key encoder and the User-Agent, then add music-ios's catalogue, playlists, smart playlists
and identify, and podcast-ios's podcasts and summaries). Pure Kotlin + OkHttp + kotlinx.serialization.
Each app copies the whole module, even the parts it doesn't use, so the drift check can compare them.

### 5.1 Transport

- Base `https://<chosen address>/api/v2`. Discovery at the root: `GET /.well-known/own-audio`.
- **User-Agent on every request**: `own.audio Music/<version> (Android <release>; <model>)` (Books,
  Podcasts accordingly). The hosted service's edge refuses requests without a proper User-Agent.
- `Accept-Language` from the app's locale (server mail comes in English or Czech from it).
- JSON: snake_case on the wire, `ignoreUnknownKeys = true`, `explicitNulls = false`, unknown enum
  values decoded to an `Unknown` case, never a crash. Skip unknown change kinds and unknown modules.
- **Canonical JSON for request bodies**: encode to `JsonElement`, sort object keys recursively, then
  print. The server matches `Idempotency-Key` retries on the body bytes, and an unsorted body made a
  retried create fail as "used for a different request" on iOS.
- Errors: every non-2xx under `/api/v2` is `application/problem+json`
  `{type, title, status, code, detail, errors[]}` (+ `mfa_token`, `mfa_methods`, `feature`). Decode to
  `OwnAudioError.Problem(status, code, …)`; **branch on `code` only**, never on `detail`. Map transport
  failures to `OwnAudioError.Network`, offline to `OwnAudioError.Offline`.
- Paging: `Page<T>(items, next, included)`, `pageAll()` helper following `next`, limit default 100,
  max 500. Never offsets.
- `include=` → `Included(contributors, albums, genres, release_groups, podcasts, items)` maps.
- Timestamps RFC 3339 with or without fractions; durations and positions **milliseconds**.
- Timeouts: 20 s per request, 6 s for detection, 4 s for the address race; SSE has a 75 s read timeout.
- Rate limits: on `429` honour `Retry-After`; never retry in a tight loop. `POST /auth/token`
  (refresh included) shares the 30-per-minute login bucket, so refresh in one place only (§5.2).

### 5.2 Discovery, sign-in, tokens

- `discover(address)`: `GET /.well-known/own-audio` → `Discovery(id, name, edition, version,
  api[], auth{password, providers[], grants[], clients{google{web}}}, modules{…}, addresses[{url,
  scope}], limits, setup{complete}, demo{email,password}?)`. **Require `api` version 2 with
  `revision ≥ 16`**, else the error "This own.audio server is too old for this app. Update it to
  1.0.4 or later." Gate later features on the revision and on `modules` (a missing module is false).
  **Never branch on `edition`.**
- `POST /auth/token`, form-encoded with sorted keys:
  - `password`: `username`, `password`, `device_name` (the device model or the user's device name),
    `device_kind=android`.
  - `403 mfa_required` → keep `mfa_token` → two-factor sheet →
    `grant_type=urn:own-audio:params:oauth:grant-type:mfa-otp`, `mfa_token`, `otp`.
  - `urn:ietf:params:oauth:grant-type:jwt-bearer` with `provider=google`, `assertion=<id token>`,
    optional `invite_code`. `409 identity_conflict` → "An account with this email already exists.
    Sign in with its password."
  - `refresh_token`.
  - Then `GET /me` (the token answer has no user).
- `TokenSession` (one per server, kept across rebuilt clients in a `TokenSessions` registry):
  - a `Mutex` makes refresh **single-flight**; refresh 60 s before expiry; on a `401` from any call,
    refresh once and retry once;
  - refresh tokens **rotate and are single-use**: persist the new pair atomically before using it;
  - `400 invalid_grant` (or `401 invalid_credentials`) on refresh → the server is marked **sign-in
    refused** ("Sign In Again" on its page), all its requests stop, nothing loops;
  - **never write stale tokens back.** `ServerStore.update(record)` keeps the stored tokens unless the
    call is a fresh sign-in (`update(record, signIn = true)`). This exact bug signed iOS users out
    after every restart (`ServerStoreTokenTests` on iOS; port them).
- `POST /auth/revoke` when a server is removed. `POST /auth/register` for sign-up (hosted service
  only in the UI). `DELETE /me {password?}` for account deletion.

### 5.3 Endpoints the foundation needs (each app adds its own)

| Area | Calls |
|---|---|
| Me | `GET /me`, `PATCH /me`, `DELETE /me`, `GET/PATCH /me/settings`, `GET /me/stats`, `GET /me/sessions`, `DELETE /me/sessions/{id}`, `DELETE /me/sessions` |
| Household | `GET /family`, `GET /family/members`, `GET/POST /family/invites`, `DELETE /family/invites/{id}`, `POST /family/join`, `GET /family/storage`, `GET /family/stats`, `GET /family/billing` (hosted, `modules.billing`) |
| Playback | `POST /playback-info`, signed `GET /stream` links (never built by hand) |
| State | `PUT/GET/DELETE /me/progress/{kind}/{id}`, `GET /me/progress`, `PUT/DELETE /me/favourites/{kind}/{id}`, `GET /me/favourites`, `POST /me/plays`, `PUT /me/holdings` |
| Sync | `GET /sync/snapshot` (NDJSON), `GET /sync/changes`, `POST /sync/push`, `GET /events` (SSE) |
| Uploads | `POST /uploads`, signed `PUT` with exactly the returned headers |
| Images | record `image` links + `&w=64|128|256|512|1024` |

### 5.4 What the iOS apps still call on v1, and what Android calls instead

| iOS (v1) | Android (v2) |
|---|---|
| `GET /api/v1/server` (identity, addresses, providers, demo account, features, revision) | `GET /.well-known/own-audio` (`id`, `addresses`, `auth.providers`, `demo`, `modules`, `api[].revision`) |
| `POST /api/v1/auth/register` fallback | `POST /auth/register` only |
| `DELETE /api/v1/users/me` | `DELETE /me` with `{password}` for password accounts; `204`; `400 password_required`, `403 invalid_credentials`, `409 last_admin`, `403 demo_read_only` |
| `GET /api/v1/stats/me`, `/stats/family`, `PUT /stats/me/visibility` | `GET /me/stats?from&to&group&kind&tz_offset_minutes`, `GET /family/stats`, `PATCH /me/settings {stats_visible_to_admin}` |
| Multipart cover uploads (`/music/tracks/{id}/upload-cover`, `/music/artists/upload-image`, `/music/playlists/{id}/upload-cover`) | `POST /uploads` (kind `track_cover`, …), then `PUT /tracks/{id}/image`, `/playlists/{id}/image`, `/contributors/{id}/image`, `/books/{id}/image` with `{upload_id}` |
| `GET /api/v1/library/folders` | `GET /library/folders` |
| Family/member pictures by v1 path with a bearer | the signed `image` link in the record (expires in 1–2 weeks: read the record again) |
| Podcast `discover/*`, `POST /podcasts/search` | `GET /podcasts/directory/categories`, `/podcasts/directory?category=`, `/podcasts/directory/similar`, `/podcasts/directory/search`, `/podcasts/directory/preview` |
| Legacy sign-in through Subsonic keys | none: v2 tokens only |

**Tests (F2):** every endpoint against MockWebServer with answers recorded from a real server
(`kit/ownaudio/src/test/resources/answers/*.json`, no personal data), the token rules (single-flight
with 20 concurrent callers, rotation persisted before use, `invalid_grant` stops everything, stale
tokens never written), problem decoding, canonical JSON (same bytes for equal objects), NDJSON with
and without the `end` line, SSE parsing by bytes (blank line ends an event, comments ignored, 75 s
silence = dead). Live tests run only when `OWNAUDIO_HOST`, `OWNAUDIO_EMAIL`, `OWNAUDIO_PASSWORD`
are set (from the gitignored `test-servers.local.env`).

---

## 6. Servers, addresses, network (phase F3, `:core:servers`)

### 6.1 Server records

- `ServerKind` per app (Music: `OWN_AUDIO, SUBSONIC, JELLYFIN`; Books: `OWN_AUDIO, AUDIOBOOKSHELF`;
  Podcasts: `OWN_AUDIO, AUDIOBOOKSHELF, GPODDER`).
- `ServerRecord(id: UUID, kind, displayName, host, auth, serverIdentity?, addresses[], connectionMode,
  libraryId?, userName, userId?, addedAt)`. `auth` holds tokens only. **No password is kept**, except
  Subsonic's (token+salt needs it) and gPodder's (Basic auth on every call).
- All records in one file encrypted with AES-256-GCM, key in the Android Keystore (no user
  authentication required, so background work and Auto can read it). Written atomically
  (temp file + rename). This is the Keychain item on iOS.
- **Composite ids**: every item the app holds is `"<server UUID>|<item id>"` (`TrackID`/`BookID`
  on iOS; Podcasts uses `"<source>/<showId>|<episodeId>"`). Queues, downloads, covers, favourites and
  positions all key on it, so one queue can mix servers and a removed server's data can be found
  and deleted.
- Removing a server: revoke (own.audio), delete its tokens, downloads, catalogue, caches and
  pending changes. Confirm text: "Remove ‹name›?" / "Its downloads leave this device. Nothing
  changes on the server."
- `AccountOwner`: signing in to a server as a different user clears the old user's data for that
  server first.

### 6.2 Adding a server, detection

Port each app's `addServer` / `ServerDetector`:

1. Map hosted web addresses to the API: `own.audio`, `www.own.audio`, `app.own.audio` →
   `https://api.own.audio`.
2. Candidates: with a scheme, as typed; without one, **`https://` first, then `http://`**. (Defaulting
   to http on iOS hit own.audio's http→https redirect, where a POST is replayed as GET → `405`, and
   the password went out unencrypted.)
3. Per candidate, the app's detection order (each app's plan lists it), 6 s timeout, no cookies.
4. Sign in; save the record **only after a successful sign-in**; learn addresses; race; sync.

### 6.3 Address scopes and racing

Port `Addresses.swift`:

- `AddressScope.infer(url)`: `LAN` = localhost, `*.local`, `*.lan`, `*.home.arpa`, 10/8,
  172.16/12, 192.168/16, 169.254/16, 127/8, IPv6 ::1, fc00::/7, fe80::/10; `VPN` = 100.64/10 (CGNAT,
  Tailscale) and `*.ts.net`; otherwise `PUBLIC`.
- `ServerAddress(url, scope, origin = ADDED | SERVER | USER)`. `isSafeToUse`: https, or LAN, or typed by
  the user. Credentials never go over http to an address the server advertised for the public internet.
- own.audio's `addresses[]` come from discovery; Jellyfin's `LocalAddress` from its public info;
  the others by hand.
- `ConnectionMode.AUTOMATIC` or `FIXED(url)`.
- **Race**: all candidates at once (4 s timeout); the fastest that answers **with the same server
  id** wins (Subsonic has no id: a signed ping). Remember the winner per network in DataStore
  (`address.<serverUUID>.<network signature>`) and use it at once next time, then re-check. A
  reinstalled server (new id) is re-adopted only at its original public https address.
- Race at launch, after a network change settles (3 s debounce), after a request fails with a
  network error, on **Check Now**. In the background only if the player needs the server.

### 6.4 Network monitor and offline

- `ConnectivityManager.registerDefaultNetworkCallback`: connected, **metered**
  (`!NET_CAPABILITY_NOT_METERED`, the iOS "expensive"), **Data Saver**
  (`RESTRICT_BACKGROUND_STATUS_ENABLED`, the iOS Low Data Mode), the network signature (transport
  types + interface names, sorted, so reordering isn't a new network). Debounce 3 s.
- `OfflineSwitch` (thread-safe, read by every kit): `isOffline = offlineMode || !connected ||
  (metered && mobileData == DOWNLOADED_ONLY)`. `isOfflineByChoice` tells the banner which text to show.
- Offline means **no server requests**: kits return a saved answer or `Offline`.

### 6.5 Answer cache, server icons

- `AnswerCache`: GET answers saved per server **identity** (not address) + path + sorted query, on
  disk under `cacheDir/server-responses/<sha256>`, returned when offline or on a network failure.
  Which paths are kept is listed per app (iOS `OwnAudioClient.keeps`). Never `/me`. Never a token in
  a key. Cleared when the server is removed.
- Server icons for third-party servers: fetched once (Audiobookshelf `icon192.png` → `icon64.png` →
  `favicon.ico`; podfetch `ui/logo.png`; others `apple-touch-icon.png` → `favicon.ico`), kept in
  `filesDir/server-icons/<serverUUID>.png`.

**Tests (F3):** scope inference table, racing with fake servers (wrong id refused, fastest wins,
memory per network), `isSafeToUse`, record encryption round trip, `update(signIn=false)` keeps the
stored tokens, detection order with MockWebServer.

---

## 7. Player core `:player:core` (phase F4)

### 7.1 Service

- `PlaybackService : MediaLibraryService` (Music too: Android Auto needs the library service).
  One `MediaLibrarySession` for the process; foreground service type `mediaPlayback`.
- Start at a position with `setMediaItems(items, startIndex, startPositionMs)`, **never `prepare()`
  then `seekTo()`**: the seek is dropped.
- Audio focus handled by ExoPlayer (`setAudioAttributes(…, handleAudioFocus = true)`), content type
  `MUSIC` or `SPEECH`; `setHandleAudioBecomingNoisy(true)` (headphones out pauses).
- **Ask for audio focus only on the first play**, never at launch (opening the app must not stop
  another app's audio — an iOS 1.0 bug).
- After a phone call or other focus loss, the player resumes where it was. Write a test that loses focus
  for 30 s mid-item and checks that position and sound continue (an iOS 1.0.1 bug: time ran with no
  sound).
- Battery: release the decoder/`AudioTrack` 30 s after pause (`player.stop()` keeping the
  position, re-prepare on play); report progress only while playing; position ticks 1/s while in
  the background, 4/s while the player screen is visible.
- The media notification and lock screen: title, artist/author, album or chapter, artwork (600 px
  cover via Coil, then `setArtworkData`), play/pause, previous/next (Music) or skip back/forward with
  the chosen intervals (spoken apps, custom `CommandButton`s with the numbered icons), seek bar.

### 7.2 Playback links

- `PlaybackLinkResolver` (one per source kind) behind a Media3 `ResolvingDataSource`: the stream
  URL is fetched **when the data source opens**, never stored in a `MediaItem` or the database.
- own.audio: `POST /playback-info {kind, id, capabilities{direct[], transcode["mp3","aac"],
  max_bitrate_kbps?}, start_ms?}` → `{decision, url, expires_at, content_type, bitrate_kbps}`. The URL
  needs **no Authorization header**. Re-ask after `expires_at` (6 h) and once on `403`/`404`/`410` from
  `/stream`. A second failure shows the error; **never loop**.
- **Transcode preference MP3 before AAC**: ffmpeg writes digital silence as 13-byte AAC frames some
  decoders reject. ExoPlayer copes better than Core Audio, but keep the order and test with the
  silent-start fixtures copied from music-ios (`test-tone-silent-start.aac/.flac`).
- `capabilities.direct` = what this device decodes: query `MediaCodecList` once at start for
  `audio/flac`, `audio/opus`, `audio/vorbis`, `audio/mp4a-latm`, `audio/mpeg`, `audio/raw` (WAV), ALAC
  if present, and map to MIME types the server knows (`audio/flac`, `audio/ogg`, `audio/opus`,
  `audio/mpeg`, `audio/mp4`, `audio/x-m4a`, `audio/aac`, `audio/wav`). Android plays Ogg/Opus directly,
  unlike iOS.
- Two OkHttp clients: an **authenticated** one for the API, and a **bare** one for stream links,
  storage redirects, publisher feeds and third-party artwork. A bearer token must never reach a
  publisher's or storage host. Allow cross-protocol redirects on the bare client (307 to storage).
- **Content type is a hint.** own.audio's local storage serves `application/octet-stream`. Let
  ExoPlayer sniff; set `MediaItem.mimeType` only for HLS. Check that downloaded and cached bytes are
  audio (`AudioPayloadCheck`: reject JSON/HTML/XML and anything under 1 KB; magic numbers ID3, `FF Fx`,
  `fLaC`, `OggS`, `RIFF`, `FORM`, `ftyp` at 4, EBML).
- **Storage-route fallback** (`StorageRoute`): own.audio stream links redirect to object storage.
  If that host fails (DNS, refused, reset, TLS) or a proxy answers 403/407/451, retry the same link with
  `&route=proxy` (the server then serves through its own media proxy). Remember per
  server + network signature for 7 days (32 entries). A 403 from the own.audio host itself means an
  expired link, not a blocked route.

### 7.3 Caches

- **Listening cache**: Media3 `SimpleCache` with `LeastRecentlyUsedCacheEvictor(1 GB)` in
  `cacheDir/listening`. Wrap the resolver in a `CacheDataSource` with a **custom cache key =
  composite id (+ `#fileId` for books, `~reduced` for a lower-bitrate copy)**. The URL changes with
  every signature and must never be the key. Caching while streaming is allowed on Wi-Fi, and on
  metered networks only for what is being played.
- **Covers**: Coil with a memory + disk cache keyed `"<serverUUID>|<record id>~<image object id>"`,
  not by URL (signatures change). On a failed load, read the record once for a fresh link.
  Catalogue art links don't expire; people's pictures expire after 1–2 weeks.
- Reduced copies (Saver) are cached separately and used only while a smaller stream is wanted; an
  original always replaces them. Downloads are always originals.

### 7.4 Sleep timer, resume, last item

- `SleepTimer`: countdown presets (5/15/30/45/60 min), **+10 minutes**, and end-of-item modes
  (Music "End of Track"; Books "End of Chapter", "End of Book"; Podcasts "End of Chapter", "End of
  Episode"). End-of-item is consumed **before** queue auto-advance. The cover shows a sleep badge
  (moon + time left, or "End of track"). Port `SleepTimer.swift` and its tests.
- `ResumeStore` per container, and the **last item**: written on item change, pause and when
  going to the background. After a relaunch the mini player shows it paused with a play symbol; a
  tap plays it from there. It is loaded only on that tap (an Audiobookshelf play session must not open
  at launch).
- `onPlaybackResumption` (Media3) returns the last item, so "resume" from Bluetooth/Assistant/the
  system media controls works after the app was killed.

**Tests (F4):** Robolectric + a fake `ExoPlayer` for the service rules (start position, focus on
first play only, end-of-item before advance), resolver expiry and the one-retry rule, cache keys
independent of the URL, storage-route memory, payload check table, sleep timer.

---

## 8. Downloads (phase F5, `:core:downloads`)

- **WorkManager workers**, not Media3's `DownloadManager`: each downloaded file must be a plain file
  with a real extension that survives the app's own cache rules. Proven on earlier Android builds.
- One unique work per file (`<composite>#<fileId>`), **two at a time** (signed links stay fresh), as a
  foreground worker with a progress notification. Fetch through the bare client into `<name>.part`,
  then rename. Range-resume a `.part` after a kill. Progress updates throttled to 2/s.
- Ask `playback-info` for the **original** (`capabilities.direct` = everything, no
  `max_bitrate_kbps`), name the file from the payload's magic bytes → `content_type` → the record,
  default `.mp3`.
- **Download on Wi-Fi Only** (default on): `NetworkType.UNMETERED` constraint; changing it
  re-enqueues the waiting work.
- **Keep at Most** (No Limit / per-app sizes in GB): eviction by least recently played (Books: finished
  first, then least recently played), **never the playing item**. Port `EvictionPolicy.swift` and its tests.
- Files in `filesDir/downloads/<serverUUID>/…` (not backed up: exclude in
  `data_extraction_rules.xml`). An index in Room holds every downloaded item's **full record**,
  so offline screens need no network. A file shared by several collections is removed only when none
  needs it. Orphaned files are reconciled at launch.
- Downloads tab basics (per app details in each plan): **On This Device** (bytes), **Downloading N**,
  **Retry N Failed**, grouped by server when there is more than one ("Removed server" for leftovers, which
  still play), swipe or menu **Remove Download**, **Remove All Downloads** ("They stay on the server
  and can be downloaded again.").
- A finished download replaces the streamed copy at the next pause or seek, never mid-sentence.
- Report holdings to own.audio with `PUT /me/holdings` (the whole set, after changes, debounced).

---

## 9. Sync (phase F6, `:core:sync`)

### 9.1 Catalogue on the phone (own.audio)

- First: `GET /sync/snapshot?kinds=<per app>` (NDJSON: one `begin` line with the cursor, one `record`
  per line, one `end`). Stream it into Room in one transaction per 500 records. **No `end` line = truncated**:
  resume with `after=<kind>:<id>` and the same cursor.
- Then `GET /sync/changes?cursor=&limit=500` until `has_more` is false. Commit the new cursor **in
  the same Room transaction** as the changes. `op: delete` removes. `reset: true`, `410
  sync_cursor_expired` or `invalid_cursor` → a fresh snapshot.
- Kinds per app: Music `contributor, album, genre, track, playlist, favourite, rating`; Books
  `contributor, book, series, collection, favourite, progress, bookmark`; Podcasts `podcast, episode,
  progress, favourite`. Ignore other kinds.
- Max age: 10 min for a fed catalogue (also on metered networks); never under Data Saver unless the
  user taps **Update Now**. A sync asked for during a sync runs once more after it.
- Favourites/ratings come from the feed, not from list answers (v2 lists don't carry them).
- **Library on This Device** (on the server page): counts ("Songs: n · Albums: m"), "Updated 5 min
  ago" / "Updating…" / "Not yet updated", **Update Now**.

### 9.2 Live updates

- `GET /events` (SSE, `Last-Event-ID`) **only while an activity is in the foreground**, online, not
  under Data Saver, and the server is signed in. A launch by Auto, the widget, a download or a refresh
  must not open it. `library` events → a changes sync (debounced 2 s). Reconnect 2 s doubling to 120 s.
  401 once → refresh; sign-in refused → stop.
- In the background, no push (the server sends none): a periodic WorkManager job (≥ 1 h,
  network-connected, battery not low) syncs catalogues, flushes outboxes and refreshes the widget —
  the iOS background refresh.

### 9.3 Outboxes

- **Pending progress** (Room table): every engine report is stored first, merged per (server, item,
  session): a newer position replaces, listened time adds up. Sent at once on pause, seek, chapter,
  stop, finish and going to the background; otherwise at most every 60 s while playing. Oldest first;
  a network error stops the flush (next checkpoint retries); a permanent refusal drops the entry.
  `PUT /me/progress/{kind}/{id} {position_ms, finished, at, file_id?, file_position_ms?}`. The answer's
  `applied: false` means another device is newer: take the server's position. Offline batches go
  through `POST /sync/push` (≤ 500, per-item `ok | stale | duplicate | <error>`).
- **Pending changes** (favourites, ratings, playlist edits, …): latest per item wins; sent in one
  `/sync/push` where the server takes the op, else one call each; network errors keep the entry.
- **Plays**: `POST /me/plays` with a stable `client_id` (`"<itemId>@<startedAt ms>"`) so a retry counts
  once; rules per app (Music: half the track or 4 min, tracks under 30 s never count).
- Flush at launch, on foreground, after a network change settles, in the periodic job.

**Tests (F6):** port the iOS feed tests (`OwnAudioFeedTests`), and add: tombstones, atomic cursor,
a failure leaves Room untouched, truncated snapshot resumes,
`reset` re-snapshots, outbox merge and order, `applied:false` handling, plays deduped.

---

## 10. Welcome, sign-in and account screens (phase F7)

### 10.1 Welcome screen (`OnboardingScreen`)

Port each app's `Servers/OnboardingView.swift`. Over `HeroBackground`, content max width 420 dp:
`OwnAudioMark.tribe(120 dp)`, `RotatingWordmark`, the app's tagline and server line, then:

- Card **"No server? Use own.audio"** (Podcasts: **Use own.audio**) with the app's text, **Create an
  own.audio Account** (filled), **Sign In** (tonal), **Try the Demo Library** (text) with its footnote.
- The app's other ways in (Music/Books: "Or connect your own server" form; Podcasts: **Find a Show**,
  **Add a Feed by Address**, **Add a Server**).
- Link "Free and open source · MPL-2.0" to the GitHub repo.

**Demo**: `GET https://demopi.own.audio/.well-known/own-audio` → `demo{email, password}` → the normal
password sign-in, display name "own.audio demo". If discovery has no `demo`, the button shows "The demo
is not available right now." Deleting the demo account is refused (`403 demo_read_only`): "The shared
demo account stays."

### 10.2 own.audio account sheet (`OwnAudioAccountScreen`, modes sign-up / sign-in)

Port `OwnAudioAccountView.swift`: Continue with Google at the top (when discovery of
`https://api.own.audio` lists `google` and the build is `play`), a fixed row **Server: own.audio**
(globe icon; always `https://api.own.audio`, no address field), sign-up: **Your Name**, **Email**,
**Password** (footer "At least 12 characters. A few words are fine."), **Create Account**; sign-in:
Email, Password, **Sign In**; footer with own.audio's Terms and Privacy Policy links and "own.audio
emails you a link to confirm the address."; two-factor step when asked. Register errors by `code`:
`invalid_email`, `weak_password`, `invalid_display_name`, `registration_closed`, `email_taken`.

### 10.3 Google sign-in (play flavour)

- Credential Manager `GetGoogleIdOption` (then `GetSignInWithGoogleOption` as a fallback when the
  account list is empty), `serverClientId` = the own.audio web client id **from discovery**
  (`auth.clients.google.web`). The app's Android OAuth client (package + signing SHA-1) must live in
  the same Google Cloud project as that web client, so in practice this works for the hosted service
  and for self-hosted servers whose operator allows that client id. Show the button only when
  `auth.providers` contains `google` **and** `auth.clients.google.web` is present; if Credential Manager
  fails with a configuration error, hide the button for that server and log it.
- The id token goes to `POST /auth/token` as the jwt-bearer `assertion`.
- Each Play signing key (debug, upload, Play app signing — measure the last one with `apksigner` from an
  installed Play build, don't copy it from a console page) needs its own Android OAuth client. This is
  maintainer setup, listed in `docs/release.md`, never committed.

### 10.4 Apple sign-in

**Not in 1.0 on Android.** Android has no native Sign in with Apple, and the server has no callback for
Apple's web flow yet. People with an Apple-only own.audio account set a password with "Forgot
password" on the web console, then sign in with it. If the server gains an Apple web callback later,
add an `AppleSignInButton` that opens a Custom Tab and receives the id token by App Link.

### 10.5 Two-factor

`TwoFactorSheet`: "Two-Factor Sign-In", explainer "The six-digit code from your authenticator app, or
one of your recovery codes.", code field (numeric keyboard but accepts recovery codes), **Sign In**,
Cancel.

### 10.6 Server page (`ServerDetailScreen`)

Port `ServerDetailView.swift` of the app. Common parts: **Use This Server** (when not active);
status "Connected · Home network / VPN / Internet" + the address in use, "Checking…", "No address
answers right now"; **Check Now** with its three result lines; **Account**: "Signed in as …", the
refused warning, **Sign In Again** (password, or Google where offered; refuses a different server id;
keeps downloads, catalogue and addresses), **Delete Account…** (own.audio; the confirmation lists what
goes; last admin and demo messages); own.audio links **Family**, **Devices**, **Listening**;
**Server** (logo + kind + version), **Name** (rename); **Connection** "Use: Automatic / <address>";
**Addresses** (scope · origin, **Add Address**, remove the ones the user added); **Library on This
Device**; **Remove Server**. Buttons on this page use the text colour, not the accent (the accent read
as an error next to status lines on iOS).

### 10.7 Family, Devices, Listening, Logs, About

- **Family** (`HouseholdScreen`, own.audio): picture, name, "N members"; **Members** (you first,
  "(you)", Admin / Invited, avatars from the signed links, else initials in the family colours);
  **Storage** bar by kind (Music `#6E44FF`-ish, Audiobooks brown, Podcasts blue) + rows; **Credit**
  where `/family/billing` exists: Balance (red when depleted), A Month at Today's Storage, Lasts About
  N days — **display only, never a link or a word about where to top up** (store rules); admin
  **Invitations**: **Invite Someone** → code, QR of the join link ("Scanned or typed in, it lets one
  person join your family."), **Share Invitation** (Android share sheet); withdraw; **Join Another
  Family…** (code → confirm "What its members share appears instead…") → catalogue reset + snapshot.
- **Devices** (`DevicesScreen`): `GET /me/sessions`, icon by `device_kind`, "This device" first,
  "Last used … ago" / "Signed in …", **Sign Out** per row, **Sign Out Everywhere** (confirm; this
  device too).
- **Listening** (`ListeningScreen`): Week / Month / Year / All; **Listened**, **Days in a Row**,
  **Audiobooks Finished**; a bar chart of minutes per day drawn with Compose `Canvas` (no chart
  library); **By Kind**; **Most Played**; admins see **Family** (members' totals or "Not Shared"); the
  others get **Share with the Family's Admin** (`PATCH /me/settings`).
- **Logs** (`LogsScreen`): an in-app ring buffer (2,000 lines, also written to
  `filesDir/logs/current.log`, previous run kept) with time · category · message; **Share Log** as a
  text file through `FileProvider`, headed "own.music <version>, Android <release>, <model>". URLs are
  logged **without query strings** (tokens and signatures live there).
- **About** section of Settings: Version ("1.0.0 (123)"), **Source Code and Licence** (GitHub),
  **Privacy** (`PRIVACY.md` on GitHub), **Licences** (third-party notices: OkHttp, Media3, Coil, ZXing,
  the server logos), **Logs**. Footer per app: "own.audio Music is free and open source (MPL-2.0). It
  talks only to the servers you add."

---

## 11. System integrations (phase F8)

| iOS | Android |
|---|---|
| Lock screen / Control Center | Media3 media notification + lock-screen controls (§7.1) |
| CarPlay | **Android Auto** through `MediaLibraryService.onGetChildren`; the same root nodes as each app's `CarPlayContent.swift`; lists capped at 100; "Add a server on your phone" when there is none; errors shown as a single item instead of an empty list; `automotive_app_desc.xml`. Content style hints (grid for albums/books, list for songs/episodes). No resume question in the car. |
| Siri / App Intents | `onPlayFromSearch` / `MEDIA_PLAY_FROM_SEARCH` with the voice-search scorer (accent-folded, port the iOS entity queries); `onPlaybackResumption`; static shortcuts (`shortcuts.xml`) for "Continue listening". |
| Widget | **Glance** widget reading a snapshot file the app writes (`filesDir/widget/snapshot.json` + small JPEG covers); redrawn only when its entries change; `allowHardware(false)` for bitmaps; declare small and medium sizes; tap = deep link. |
| Deep links | Intent filters for `ownmusic://`, `ownbook://`, `ownpodcast://` (+ `feed://`, `podcast://` in Podcasts). A link to an item on another server: "That is on another server. Switch to it in Settings first." |
| Background refresh | Periodic WorkManager (§9.2) |
| AirPlay button | The system output switcher (`MediaRouteButton` with `androidx.mediarouter`, only if it stays small; else omit and rely on the system media panel). No Cast in 1.0. |
| Keyboard shortcuts (iPad/Mac) | Hardware keys on tablets and Chromebooks: Space play/pause, Ctrl+→/← next/previous (or skip), Esc closes the player; not while a text field has focus. |
| Photo picker | `PickVisualMedia` (no storage permission); resize to 1,600 px JPEG before upload. |
| Files picker (Books import) | SAF `OpenMultipleDocuments` / `OpenDocumentTree`. |
| Notifications | Only the media and download/upload progress notifications. `POST_NOTIFICATIONS` asked the first time a download or upload starts, not at launch. |

---

## 12. Localization (phase F9)

- `res/values/strings.xml` (English, American spelling: "Favorites", "Accent Color", "catalog",
  "grayed out") and `res/values-cs/strings.xml`, with `<plurals>` using Czech `one`/`few`/`many`/`other`.
- **Reuse the iOS translations.** `tools/xcstrings-to-android.py` reads the iOS app's
  `Localizable.xcstrings` and writes Android resources: the key is a slug of the English text
  (`settings_accent_color`), printf placeholders are converted (`%@` → `%1$s`, `%lld` → `%1$d`), plural
  variations become `<plurals>`. Run it once per app at the start; after that the Android XML is the
  source and the script is only used to bring over new iOS strings. Wording stays with the own.audio
  glossary (Czech examples: "Úsporný" for Saver, "Odhlásit se všude", "Nesdíleno", "Původní", "Jen já";
  podcasts: feed = kanál, show = pořad, episode = epizoda, follow = odebírat).
- Say "this device", not "phone" or "iPhone".
- Numbers with the locale's decimal sign: "0,75×" in Czech; speed shows "1.75×", never "1.8×", and "1.5×", never "1.50×".
- Per-app language in the system settings (`generateLocaleConfig`, `resources.properties` with
  `unqualifiedResLocale=en`) and an in-app **Language** row (System / English / Čeština) through
  `AppCompatDelegate.setApplicationLocales`.
- Sort with `Collator` for the user's locale.
- Store listing texts (`fastlane/metadata/android/en-US` and `cs-CZ`, the F-Droid layout too) from the
  iOS `AppStore/<lang>/` texts, rewritten for Android.

---

## 13. Tests

- **Unit (JVM)**: kits against MockWebServer, library logic, outboxes, eviction, address racing,
  queue, sleep timer, chapters. Port the Swift tests file by file; keep their names
  (`EvictionPolicyTest` ← `EvictionPolicyTests.swift`).
- **Robolectric**: service rules, Room DAOs and migrations, WorkManager workers (`TestListenableWorkerBuilder`).
- **Screenshots (Roborazzi)**: every screen in light, dark, Czech and 200 % font, recorded in CI
  (`verifyRoborazziDebug`). These replace iOS's large-text and Czech UI tests.
- **End-to-end** (`scripts/test-servers.sh <server>`): instrumented Compose tests on an emulator
  against real servers. Credentials only in the gitignored `test-servers.local.env`. Servers: an
  own.audio test server, the public demo, a throwaway Navidrome or Audiobookshelf in Docker, a local
  feed fixture server (Podcasts, port `UITests/Fixtures/serve.py`). The emulator reaches the host's
  servers at `10.0.2.2`; when a server returns links to `localhost` storage, `adb reverse tcp:<port>
  tcp:<port>` for each port — never rewrite hosts in the app.
- **Misuse suite** (port `UITests/MisuseTests.swift`): hammered play/pause, stop while loading,
  killed mid-download, server removed mid-song, offline mid-stream, restless navigation, wrong sign-ins.
- Drive the emulator UI with `uiautomator dump`, not with coordinates from screenshots. Never send
  `KEYCODE_ESCAPE` in scripts (it is Back).

Gate for every phase: `./gradlew testDebugUnitTest lintDebug verifyRoborazziDebug` green,
`tools/sibling-drift.sh` clean, CHANGELOG entry, the guide page updated for anything users see.

---

## 14. Release (phase F10, per app)

- **Google Play**: a new app per applicationId (`audio.own.musicplayer`, …). A personal developer
  account needs a **closed test with 12 testers for 14 days** before production; internal testing
  doesn't count. Data safety: "No data collected", "No data shared" (true: the app talks only to the
  servers the user adds, and to Google for sign-in in the play flavour). Account deletion: in the app
  (server page) plus the web URL of own.audio's account deletion. Content rating (IARC) questionnaire
  (Family Link testers can't install before it). Target audience 13+; no ads.
- **App signing**: Play App Signing; the upload key stays with the maintainer (gitignored
  `keystore.properties`). Never commit any signing material.
- **F-Droid / GitHub releases**: the `foss` flavour; reproducible build notes in `docs/release.md`;
  `fastlane/metadata` for the F-Droid listing; release APKs attached to GitHub releases, signed with a
  separate key held by the maintainer.
- **Guide**: `guide/` on GitHub Pages, one page per screen with screenshots, the same structure as the
  iOS guide; describes only what ships.
- `PRIVACY.md`: the same promises as iOS (nothing collected; talks only to the servers you add,
  Google if you sign in with it, Apple's podcast directory in Podcasts when no own.audio catalogue is
  there).
- Every release: APK size checked, CHANGELOG section dated, store texts in English and Czech, the
  screenshots regenerated from a demo-only server (never real users' data).

---

## 15. Order of the foundation phases

| Phase | Content | Built in |
|---|---|---|
| F0 | Repo, build, CI, empty tabs | Music first; Books and Podcasts copy it |
| F1 | Tokens, theme, brand, shared composables, icons | Music |
| F2 | `:kit:ownaudio` | Music (copied whole, even the parts Music doesn't use) |
| F3 | Servers, addresses, network, answer cache | Music |
| F4 | Player core | Music |
| F5 | Downloads | Music |
| F6 | Sync, outboxes, live updates | Music |
| F7 | Welcome, sign-in, server page, family, devices, listening, logs, about | Music |
| F8 | Auto, widget, deep links, refresh | Music |
| F9 | Localization pipeline | Music |
| F10 | Release setup | each app |

Books and Podcasts start with a phase 0 that copies `music-android`'s foundation at a named commit,
renames the applicationId, accent, words and server kinds, and runs the drift check. From then on a
fix in a Tier 1 module is made in one repo and copied to the other two in the same change.
