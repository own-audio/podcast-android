# Dependencies

Every library the app uses, and why. A new one needs a line here in the same change
(docs/android-foundation.md §2.2). Versions live in `gradle/libs.versions.toml`.

| Library | Why |
|---|---|
| Jetpack Compose (BOM), Material 3, material icons | The UI toolkit of the platform |
| `material3-adaptive-navigation-suite` | Bottom bar on phones, rail or drawer on tablets, like the iPad sidebar |
| `activity-compose`, `lifecycle-*-compose`, `navigation-compose` | Activity, state and navigation for Compose |
| AppCompat | Per-app language below Android 13 (`AppCompatDelegate.setApplicationLocales`) |
| Media3 ExoPlayer, session, OkHttp data source, HLS | Playback, the media session (lock screen, Bluetooth, Android Auto); HLS for Audiobookshelf transcodes |
| OkHttp | Every request, by hand: own.audio, Subsonic, Jellyfin, feeds, SSE, NDJSON |
| kotlinx.serialization | JSON without reflection |
| Room (KSP) | The catalogue on the phone, the downloads index, outboxes |
| DataStore | Settings |
| WorkManager | Downloads, uploads, refresh in the background |
| Coil 3 | Covers, with cache keys that survive expiring signed links |
| Glance (`glance-appwidget`, `glance-material3`) | The home-screen widgets; `glance-material3` gives them the fallback colour scheme below Android 12 |
| Credential Manager + Google ID (play flavour only) | Sign in with Google |
| AndroidX Browser (Custom Tabs) | Sign in with Apple, which runs in the browser: Android has no Apple sign-in of its own |
| ZXing core (encoder only) | Family invitation QR codes |
| Tests: Media3 test utils (`media3-test-utils`, `-robolectric`) | Real ExoPlayer instances on a fake clock in JVM tests |
| Tests: Glance testing (`glance-appwidget-testing`) | The widgets' layouts and click actions in JVM tests |
| Tests: WorkManager testing (`work-testing`) | Running download and refresh workers in JVM tests |
| Tests: JUnit 4, coroutines-test, Turbine, MockWebServer, Robolectric, Compose ui-test, Roborazzi | Unit, JVM UI and screenshot tests |
