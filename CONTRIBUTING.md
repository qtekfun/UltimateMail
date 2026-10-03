<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Contributing

Thanks for helping! A few rules keep UltimateMail free, reliable and easy to review. The full
project rules are in [CLAUDE.md](CLAUDE.md); read also [PRD.md](PRD.md), [SPEC.md](SPEC.md) and
[PLAN.md](PLAN.md) (written in Spanish).

## Ground rules

- **Free software only.** No Google Play Services, Firebase, Crashlytics, analytics or any
  non-free dependency.
- **No telemetry**, of any kind. Never log subjects, bodies or e-mail addresses.
- **Every visible string in `strings.xml`**, in English (`values/`) and Spanish (`values-es/`).
- **SPDX header** in every source file: `SPDX-License-Identifier: GPL-3.0-or-later`.
- Do not implement what SPEC.md lists as out of scope (IDLE push, snooze, PGP/S-MIME, aliases...).
- If the spec is ambiguous, ask in an issue instead of guessing.

## Workflow

1. One task of `PLAN.md` (or one fix) per branch, `feat/<task>`, started from `master`.
2. Small, atomic commits following [Conventional Commits](https://www.conventionalcommits.org)
   (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`, `ci:`, `build:`...).
3. `./gradlew check` must pass before you push.
4. Open a pull request against `master`; CI runs the same checks. Never push to `master`
   directly and never force-push shared branches.
5. Mark the task in `PLAN.md` when it is done.

## Quality gates

`./gradlew check` runs unit tests, detekt, ktlint, Android Lint, the dependency checks and Kover.
The pieces, if you want to run one:

| Command | What |
|---|---|
| `./gradlew testDebugUnitTest` | unit tests (JUnit 5, MockK, Turbine, GreenMail, in-memory Room) |
| `./gradlew detekt ktlintCheck lintDebug` | static analysis and style; Kotlin and Lint warnings are errors |
| `./gradlew koverVerify koverHtmlReport` | coverage thresholds and report |
| `./gradlew connectedDebugAndroidTest` | instrumented tests, on a device or emulator you provide |

Do not disable or relax detekt, ktlint, Lint or Kover to make CI pass.

## Code

- Kotlin, Jetpack Compose and Material 3; MVVM with `ui` / `domain` / `data` / `sync` layers.
- Room is the single source of truth: the UI reads Room, never the network.
- Network and IO errors are typed results (sealed classes), not exceptions reaching the UI.
- Room schema changes need a new version, a migration and a migration test.
- Immutable by default; one public class per file; business logic in `domain`, not in composables.
- Everything is per account: keys are `(accountId, ...)`.
- E-mail HTML is rendered isolated, with scripts off and remote content blocked by default.
- Secrets (app passwords, OAuth tokens) are encrypted with the Android Keystore.
- Accessibility: content descriptions, 48 dp touch targets, layouts that work with large fonts.

## Tests and coverage

- Coverage (Kover): at least **85%** over `domain`, `data` and `sync`; **100%** (lines and
  branches) on the operation queue (`sync.queue`) and the conflict resolver (`sync.conflict`).
  Generated code, `@Preview` and pure Compose UI are excluded.
- A test must be able to fail for a real reason: no empty or tautological tests to raise numbers.
- Never use real accounts or real mail in tests; use GreenMail or the scripted servers.

## Dependencies

- Before adding a dependency, open an issue: its license must be compatible with
  GPL-3.0-or-later and it must not pull in Google services. The build enforces it with the
  `licensee` plugin (allowed licenses are listed in `app/build.gradle.kts`) and the
  `checkForbiddenDependencies` task.
- Dependabot keeps versions up to date; do not bump them by hand.
- Gradle verifies every artifact against `gradle/verification-metadata.xml`. When dependencies
  change, regenerate it **from an empty Gradle home**: CI starts with a clean cache, and some
  artifacts (for example `.pom` or `.module` files) are only fetched on a clean cache, so a file
  generated on a warm machine can lack their checksums and fail verification there.

  ```sh
  GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 \
    --no-build-cache --rerun-tasks --no-configuration-cache \
    clean check assembleDebug assembleRelease
  ```

  This is the command the *Dependabot verification* workflow runs on Dependabot's pull requests.
  Review the diff of the file (only the new dependencies should appear) and commit it on its own,
  for example `chore: checksums for <dependency>`. If the Android SDK is not found with the
  temporary Gradle home, set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
