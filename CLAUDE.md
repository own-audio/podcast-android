# CLAUDE.md — podcast-android

**own.audio Podcasts** for Android: a native, open-source (MPL-2.0) podcast player that plays from plain podcast feeds with no account, and optionally own.audio, Audiobookshelf and gPodder sync servers.
applicationId `audio.own.podcastplayer`, home-screen name **own.podcast**. The repository is public: write everything for
strangers to read.

- Work phase by phase from `IMPLEMENTATION_PLAN.md`; tick phases there and update "Where we stopped".
- The shared parts (build, look, own.audio client, servers, player core, downloads, sync, sign-in,
  settings pages, tests, release) are described in `docs/android-foundation.md`. That file is the same
  in music-android, book-android and podcast-android: change it in all three.
- **The iOS app is the specification**: [podcast-ios](https://github.com/own-audio/podcast-ios). Read the Swift files
  a phase names before writing Kotlin; keep its words, layout and behaviour unless the plan says why not.
- Every change gets a `CHANGELOG.md` entry under `[Unreleased]`. A change users can see also updates
  `guide/` (the public user guide) in the same commit; describe only what ships.
- **own.audio on `/api/v2` only.** No `/api/v1` calls at all. If v2 seems to lack something, check
  `docs/api/openapi-v2.json` in [own-audio/server](https://github.com/own-audio/server) first; if it is
  really missing, hide the feature and add it to the plan's "Server work".
- A feature a server lacks is hidden, not broken: screens ask the source's `Capabilities`.
- own.audio is one server among the others, never a funnel.
- Never a lock icon for private content; a people icon for family-shared content.
- **No own.audio internals**: no internal hosts, test accounts, team ids, signing material or references
  to closed repositories. Test credentials live only in the gitignored `test-servers.local.env`.
- Keep it small: no dependency without a line in `docs/dependencies.md`; system decoders only; no
  cross-platform frameworks, Firebase, analytics or crash reporters.
- Comments explain why, for an outside reader. Default to none.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest lintDebug verifyRoborazziDebug
tools/sibling-drift.sh        # with the sibling repositories checked out next to this one
```

The emulator reaches servers on this machine at `10.0.2.2`. When a server hands out links to
`localhost` storage, `adb reverse tcp:<port> tcp:<port>` for each port; never rewrite hosts in the app.
