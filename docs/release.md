# Releasing own.audio Podcasts

What a release needs that can't be done from the repository. Foundation §14 has the reasons.

## Every release

1. Date the CHANGELOG section, raise `app.versionName` in `gradle.properties` if it changes (0.1.0
   until the first store release).
2. Check the store texts in `fastlane/metadata/android/{en-US,cs-CZ}` against what ships.
3. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. `.github/workflows/release.yml`
   builds both flavours, checks the APK size and attaches the foss APK, the play bundle and
   `SHA256SUMS` to the GitHub release.
4. Upload the `.aab` to Play Console (closed test first, then production).

**versionCode** is the commit count, passed at build time:

```bash
./gradlew bundlePlayRelease assembleFossRelease -Papp.versionCode=$(git rev-list --count HEAD)
```

`app.versionCode=1` in `gradle.properties` is only the default for debug builds. A release built
locally without the `-P` gets 1 and Play refuses it. The workflow needs the full history
(`fetch-depth: 0`) for the count; a shallow clone gives 1.

**APK size**: `tools/apk-size.sh` fails above the budget in that script (real size + 20 % at the
first release). Raise it on purpose, not to get a build through.

**Store icon**: `tools/store-icon.py` renders `fastlane/metadata/android/en-US/images/icon.png` from
the launcher icon. Run it after `tools/render-icon.py`.

## Signing

- The upload key lives with the maintainer, never in the repository. Locally:
  `keystore.properties` in the repo root (gitignored) with `storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`. Without it release builds come out unsigned.
- GitHub repository secrets for `release.yml` (Settings → Secrets and variables → Actions):
  - `ANDROID_KEYSTORE_BASE64`: `base64 -i upload.jks | pbcopy`
  - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

  Without them the workflow still runs and attaches unsigned files. The same key signs the bundle
  and the GitHub APK. Play re-signs the bundle with its own key, so a GitHub or F-Droid install
  can't be updated from Play and the other way round; users have to uninstall to switch.

## Google Play (once)

1. Create the app in Play Console for `audio.own.podcastplayer`. Free, no ads.
2. **Play App Signing**: on (the default). Upload the first bundle signed with the upload key.
3. **Closed test**: a personal developer account needs 12 testers opted in for 14 days in a closed
   test before production can be requested. Internal testing doesn't count.
4. **Data safety**: "No data collected", "No data shared". The app talks only to the servers the
   user adds, to the feeds it follows, to Apple's public podcast directory when searching, and
   in the play build to Google for sign-in. None of it reaches us.
5. **Account deletion**: in the app (server page → Delete Account) and on the web at
   https://www.own.audio/delete-account/.
6. **Content rating** (IARC questionnaire): no user-generated content shown to strangers, no ads.
   Family Link testers can't install the app until it is rated.
7. **Target audience**: 13 and over.
8. Store listing: the texts and icon from `fastlane/metadata/android`, screenshots from a
   demo-only server (never real users' data), in English and Czech.

## Sign in with Google (Android OAuth clients)

Each key that signs an installed app needs its own Android OAuth client (package
`audio.own.podcastplayer` + the key's SHA-1) in the own.audio Google Cloud project, the same project
as the web client the server hands out in discovery:

- **debug**: `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`
- **upload**: `keytool -list -v -keystore upload.jks`
- **Play App Signing**: measure it from a build installed from Play, don't copy it from the
  console page:

  ```bash
  adb shell pm path audio.own.podcastplayer        # → package:/data/app/…/base.apk
  adb pull /data/app/…/base.apk
  apksigner verify --print-certs base.apk        # SHA-1 digest
  ```

Only the play flavour has Google sign-in; the foss build doesn't show the button.

## F-Droid

F-Droid builds the **foss** flavour from source and reads the listing from `fastlane/metadata`.

1. Open a merge request on https://gitlab.com/fdroid/fdroiddata adding
   `metadata/audio.own.podcastplayer.yml`: license MPL-2.0, `subdir: app`, `gradle: [foss]`, and per
   build `gradleprops: [app.versionCode=<commit count of the tag>]`.
2. Because versionCode isn't in the source, F-Droid's update check can't read it. Either write the
   count into `app.versionCode` in the release commit (the tagged commit's count is the current
   count + 1), or add each build entry to fdroiddata by hand.
3. Reproducible builds: the build has no timestamps or machine paths in it; use the JDK and
   Gradle the wrapper and `ci.yml` use (JDK 21). To let F-Droid ship the APK signed with our key,
   add `Binaries:` pointing at the GitHub release APK and `AllowedAPKSigningKeys:` with the
   signing key's SHA-256; F-Droid then publishes only if its build matches ours byte for byte.
   Without that, F-Droid signs with its own key.
4. The app has no anti-features: no tracking, no non-free dependencies in foss.

## GitHub Pages (guide)

Settings → Pages → Source: **GitHub Actions**. `.github/workflows/pages.yml` then publishes
`guide/` on every push to `main` that touches it. The guide describes only what ships; update it
with every user-visible change.
