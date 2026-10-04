<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Releasing

Version 0.1.0 was released on 2026-10-04 following this process (tag `v0.1.0`, signed APK on the
GitHub Release). There is no F-Droid submission yet: see the F-Droid section.

## Versions

- The version lives in one place: `appVersion` in `gradle.properties`, as SemVer (`1.2.3`), or
  `1.2.3-rc.N` for a release candidate.
- The Android version code is derived from it in `app/build.gradle.kts` (`versionCodeOf`), never
  set by hand: `(MAJOR*10000 + MINOR*100 + PATCH) * 100 + N`, with `N = 99` for a final release.
  So `0.1.0` is `10099`, `1.0.0-rc.1` is `1000001` and `1.0.0` is `1000099`: a final version
  always sorts after its release candidates, and nothing depends on dates or the machine
  (reproducible builds).
- Before 1.0.0 the app is `0.x`.

## Signing (one time)

Releases are signed with the project's own key, and the builds are reproducible: F-Droid builds
the same source, checks that its APK matches the one published on GitHub and then ships ours. That
way the app can be updated from F-Droid or GitHub interchangeably.

1. Create the key, and keep the file and passwords somewhere safe and **backed up**: if the key is
   lost, users would have to uninstall to update. Never commit it (`*.jks` is ignored).
   ```sh
   keytool -genkeypair -v -keystore ultimatemail-release.jks -alias ultimatemail \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add these secrets to the GitHub repository (Settings, Secrets and variables, Actions):
   - `UM_KEYSTORE_BASE64`: `base64 -w0 ultimatemail-release.jks`
   - `UM_KEYSTORE_PASSWORD`, `UM_KEY_ALIAS` (`ultimatemail`), `UM_KEY_PASSWORD`
3. For F-Droid, give them the certificate fingerprint (`AllowedAPKSigningKeys` in its metadata):
   ```sh
   keytool -list -v -keystore ultimatemail-release.jks -alias ultimatemail | grep SHA256
   ```
   Use the value without colons, in lower case.

Without the `UM_KEYSTORE_*` variables, `./gradlew assembleRelease` builds an unsigned APK, which is
what F-Droid does before comparing.

## Making a release

1. Move the `[Unreleased]` notes in `CHANGELOG.md` under `## [X.Y.Z] - YYYY-MM-DD` and add the
   compare link at the bottom.
2. Set `appVersion=X.Y.Z` in `gradle.properties`.
3. Add `fastlane/metadata/android/{en-US,es-ES}/changelogs/<versionCode>.txt` (500 bytes at most)
   and check the descriptions still tell the truth about the app's status.
4. Commit (`chore: release X.Y.Z`), merge to `master`, then tag and push the tag:
   ```sh
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
5. The **Release** workflow (`.github/workflows/release.yml`) runs only on a pushed tag. It checks
   that the tag matches `appVersion`, runs `./gradlew check`, builds the signed APK and publishes a
   GitHub Release with `UltimateMail-X.Y.Z.apk` attached and the notes of that version. Release candidates
   (`-rc.N`) are marked as pre-releases.
6. F-Droid picks the new tag up by itself (`UpdateCheckMode: Tags`, final versions only: release
   candidates are not offered there).

Do not pass `-PUM_GOOGLE_CLIENT_ID` to a release build: the value goes into `BuildConfig`, and
F-Droid builds without it, so the APKs would differ.

## F-Droid

`fdroid/com.qtekfun.ultimatemail.yml` is the app's metadata as it will be submitted to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (`metadata/com.qtekfun.ultimatemail.yml`). It is
ready for 0.1.0: `commit` is the full SHA of the `v0.1.0` tag and `AllowedAPKSigningKeys` the
fingerprint of the release certificate. To submit it:

1. Open a merge request against fdroiddata adding this file as
   `metadata/com.qtekfun.ultimatemail.yml`, then follow its review. F-Droid's own update checks
   (`UpdateCheckMode: Tags`) add later versions by themselves once the first one is accepted.
2. Before each later version is picked up, check `versionName`, `versionCode`, `CurrentVersion` and
   `CurrentVersionCode` if you edit the file by hand.

F-Droid builds each tagged version with JDK 21, like CI, checks that its APK matches ours
(`Binaries`, `AllowedAPKSigningKeys`) and then publishes ours.

Store texts and images come from `fastlane/metadata/android/` (three screenshots per language).

## Reproducible build checklist

What `app/build.gradle.kts` and the wrapper already guarantee:

- `dependenciesInfo` is off for the APK and the bundle (no Google-encrypted dependency blob).
- `vcsInfo.include = false` in the release build type (the commit is not in the APK).
- Gradle is pinned by the wrapper (`gradle-9.8.0`, with `distributionSha256Sum`); AGP and Kotlin
  have exact versions in `gradle/libs.versions.toml`, with no dynamic versions.
- Every dependency is checked against `gradle/verification-metadata.xml`.
- The version code and name come from `appVersion`, not from dates or git.
- Code and resource shrinking (R8) are deterministic for the same inputs.

To check by hand, build `assembleRelease` (unsigned) twice from a clean checkout and a clean Gradle
home with the same JDK 21, and compare the two APKs, for example with `diffoscope`.
