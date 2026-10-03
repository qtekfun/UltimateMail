<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

Nothing has been released yet. This is what has landed on `master` so far.

### Added

- Base Android project: Kotlin, Jetpack Compose, Material 3, Hilt, Room, English and Spanish
  strings, GPL-3.0-or-later.
- Quality gates and CI: detekt, ktlint, Android Lint, Kover thresholds, license and forbidden
  dependency checks, dependency verification, GitHub Actions and Dependabot.
- Room database model: accounts (with signature and offline policy), folders and labels, messages,
  threads, attachments, the operation queue and full-text search tables.
- Encrypted credential store and account domain: add an account with autodetected server settings,
  password or app password, connection test with a real IMAP login, removal that wipes the data.
- IMAP/SMTP client over Angus Mail behind domain interfaces, with Gmail extensions.
- Persisted operation queue: idempotent operations, merging and exponential backoff.
- Conflict and consistency rules: flags, moves and labels resolved by message identity, UIDVALIDITY
  reset planning, and sends that never duplicate after an unclear result.
- Sync engine: UIDVALIDITY/UIDNEXT/CONDSTORE handling, offline window, periodic background sync with
  WorkManager, pull to refresh and sync when an account is added.
- Conversation threading with `X-GM-THRID` and References/subject.
- Safe HTML: allow-list sanitizer, a locked-down WebView, blocked remote content and a check for
  deceptive links, tested against a corpus of hostile e-mails (debug screen only for now).
- Google OAuth2 sign-in prototype with AppAuth, with your own client ID (debug screen only for now).
- Per-account signature logic in the domain layer.
- Add-account and folder list screens.
- Conversation list with paging, pull to refresh, a unified inbox and a "pending sync" indicator.
- Debug-only demo data for the inbox.

### Changed

- `master` is the default branch.

### Security

- No telemetry, analytics or Google services; the build fails if Play Services, Firebase or
  Crashlytics appear, or if a dependency has a non-free license.
- Android backup and device transfer are disabled; credentials are encrypted with the Android
  Keystore and kept in no-backup storage.
- TLS with full certificate validation for every mail connection.
- Gradle dependency verification with checksums, including artifacts only fetched on a clean cache.

[Unreleased]: https://github.com/qtekfun/UltimateMail/commits/master
