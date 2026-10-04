<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Changed

- Sign-in with Google and Microsoft is hidden: accounts use a password or an app password (free,
  does not expire). The OAuth code stays and can be turned back on (`OAuthFeature`). Accounts that
  already use OAuth can still sign in again.

## [0.1.0] - 2026-10-04

First version. It reads, writes, searches and syncs mail for IMAP/SMTP accounts and has been used
with Gmail; some parts are not verified yet (see Known limitations).

### Added

- Accounts: add an account with automatic server settings and a real login test; password or app
  password; Google sign-in with AppAuth and your own OAuth client ID; Microsoft sign-in the same
  way; sign in again without losing local data or pending changes; remove an account and its data;
  export and import accounts as an encrypted file (never includes mail).
- Reading: conversations with collapsible messages and quoted text, safe HTML in a locked-down
  WebView (no scripts, remote content blocked by default, link destination confirmed when the text
  looks like another address), attachments on demand, message bodies kept for offline reading, move
  or label from the reading screen, previous and next conversation.
- Writing: new message, reply, reply all and forward (with its attachments), drafts kept locally and
  on the server, a send queue that survives restarts with a 5 second undo, an outbox, one signature
  per account, plain text.
- Organising: swipe gestures you can configure, quick selection with Edit, undo for archive, delete
  and move, a move/label picker with live search and recent destinations, Gmail labels, Trash and
  Spam as destinations on Gmail, delete drafts by swiping.
- Search: on the device and on the server, with Gmail-style operators.
- Sync: offline first with Room as the single source of truth, in phases (the last 30 days, up to a
  year, then the account's offline window) with progress, resilient to a folder or a message that
  fails, an operation queue with back-off, and holds on moves so Undo always works.
- Look: modeled on iOS Mail and the Gmail app: large titles, a bottom bar with filter, search and
  compose, message previews of 0 to 5 lines, an unread dot, a Mailboxes menu with the accounts'
  inboxes first, a finer type scale, three display densities, dark theme, optional pure black,
  dynamic colors, English and Spanish.
- Accessibility: touch targets of 48 dp, content descriptions, large font support up to 200%.

### Security

- No telemetry, analytics or Google services; the build fails if Play Services, Firebase or
  Crashlytics appear, or if a dependency has a non-free license.
- Android backup and device transfer are disabled; credentials are encrypted with the Android
  Keystore and kept in no-backup storage.
- TLS with full certificate validation for every mail connection.
- Gradle dependency verification with checksums, including artifacts only fetched on a clean cache;
  reproducible builds.

### Known limitations

- No push notifications (IMAP IDLE) and no foreground service: mail arrives with the periodic sync
  (about every 15 minutes) or when you open the app or pull to refresh.
- The composer is plain text only; no PGP or S/MIME.
- Microsoft sign-in has not been tried against a real account.
- The app has not been reviewed with TalkBack by a person (touch targets, descriptions and font scaling are covered by code and tests).
- The first sync of a mailbox with hundreds of labels takes a few minutes.

[Unreleased]: https://github.com/qtekfun/UltimateMail/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/qtekfun/UltimateMail/releases/tag/v0.1.0
