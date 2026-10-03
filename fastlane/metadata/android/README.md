<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# F-Droid / fastlane metadata

Store texts for F-Droid, in `en-US/` and `es-ES/`: `title.txt`, `short_description.txt` (80
characters at most), `full_description.txt` and `changelogs/<versionCode>.txt` (500 bytes at most).

The version code comes from `appVersion` in `gradle.properties` (see `versionCodeOf` in
`app/build.gradle.kts`): `0.1.0` gives `(0*10000 + 1*100 + 0) * 100 + 99 = 10099`. Add a new
`changelogs/<versionCode>.txt` in each language for every release (RELEASING.md).

## Still missing

- [ ] **Screenshots**, `en-US/images/phoneScreenshots/1.png`, `2.png`... and the same in `es-ES/`
  (taken in each language). They need the final UI (conversation list, reading, composing,
  side drawer, dark mode), so none has been made yet. Do not use mock-ups.
- [x] **Icon**, `en-US/images/icon.png` (512x512). Rendered from the shapes of the adaptive icon
  (`app/src/main/res/drawable/ic_launcher_foreground.xml` over the color of
  `ic_launcher_background.xml`), cropped to the 72 dp safe area. Re-render it if the launcher icon
  changes. F-Droid uses it for every language. A designed icon may replace it later.
- [ ] Optional: `featureGraphic.png` (1024x500) in each language.
