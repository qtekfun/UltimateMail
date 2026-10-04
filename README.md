<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" alt="UltimateMail icon" width="112">

# UltimateMail

**A modern, offline-first IMAP/SMTP mail client for Android.**

[![CI](https://github.com/qtekfun/UltimateMail/actions/workflows/ci.yml/badge.svg)](https://github.com/qtekfun/UltimateMail/actions/workflows/ci.yml)
[![License: GPL v3+](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)

</div>

*Resumen en español al final: [Español](#español).*

> **Status: first version (0.1).** It reads, writes, searches and syncs mail for IMAP/SMTP accounts
> and has been used with Gmail with an app password. TalkBack has not been reviewed, and there
> are no push notifications. See the [status table](#status) and the
> [changelog](CHANGELOG.md).

## What and why

UltimateMail aims to take the best of iOS Mail and the Gmail app and make it a free-software mail
client for any IMAP/SMTP account. It exists because Thunderbird for Android, the obvious free
choice, falls short in ways that matter every day:

- a dated interface;
- slow or missing swipe gestures;
- sync and performance problems with large mailboxes;
- no way to search the list of folders and labels when moving a message to one.

## Design goals

- **iOS Mail + Gmail look and feel**: Material 3 with dynamic colors and dark mode, conversation
  view, a side drawer with the unified inbox, folders and labels, fast configurable gestures.
- **Offline first**: everything you see comes from a local database; changes are saved at once and
  synced later by a queue that survives restarts and never loses a message, draft or action.
- **Free software**: GPL-3.0-or-later, no Google Play Services, Firebase or proprietary SDKs,
  built to be published on [F-Droid](https://f-droid.org).
- **No telemetry**, of any kind. See [PRIVACY.md](PRIVACY.md).
- **Per account**: settings, signature and offline policy are kept for each account.

## Status

The roadmap is [PLAN.md](PLAN.md); the specification is [SPEC.md](SPEC.md). The whole plan is
implemented and covered by unit tests, and the instrumented UI tests pass on a phone. "Used" means
tried against a real server by the maintainer.

| Feature | Status |
|---|---|
| Accounts: autodetection, password or app password, connection test, remove, export/import (encrypted) | Done, used with Gmail |
| Sign-in with Google or Microsoft (OAuth2 with your own client ID) | Implemented but **hidden**: accounts use a password or an app password for now (see below) |
| Credentials encrypted with the Android Keystore | Done |
| Offline-first sync in phases, operation queue and conflict rules | Done, used with Gmail |
| Conversation list, threads, unified inbox, mailboxes menu | Done |
| Reading, safe HTML, attachments on demand, offline bodies | Done |
| Writing: reply, forward with attachments, drafts, offline send queue with undo, signatures | Done (plain text only) |
| Gestures, quick selection, undo, move/label picker with search | Done |
| Search on the device and on the server | Done |
| Settings: theme, density, previews, language, gestures, offline policy | Done |
| iOS Mail inspired look | Done |
| Accessibility (48 dp targets, descriptions, 200% font) | Done by code and tests; **not reviewed with TalkBack** (skipped by decision) |
| Push notifications (IMAP IDLE), rich text, PGP/S-MIME | Not included |

Out of scope for the first version: push with IMAP IDLE, snooze, PGP/S-MIME, aliases. See SPEC.md.

## Requirements

- Android 8.0 (API 26) or newer.
- An account with IMAP and SMTP access (see below).

## Supported providers and sign-in

| Provider | Sign-in |
|---|---|
| Gmail / Google Workspace | App password (needs 2-Step Verification) |
| Outlook.com / Microsoft 365 | Password or app password, where the account allows IMAP/SMTP with one |
| Any IMAP/SMTP server (Dovecot, Fastmail, your own...) | Password, with TLS or STARTTLS; certificate authorities installed on the device are accepted |

## Building

You need JDK 21 and the Android SDK. Gradle is the wrapper in the repository.

```sh
./gradlew assembleDebug   # debug APK, in app/build/outputs/apk/debug/
./gradlew check           # what CI runs: unit tests, detekt, ktlint, Android Lint, Kover
```

Dependencies are verified (`gradle/verification-metadata.xml`) and their licenses are checked:
only free software is allowed, and Google Play Services, Firebase and Crashlytics fail the build.

### OAuth sign-in (hidden)

Sign-in with Google and Microsoft (OAuth2, with a client ID you create yourself) is implemented
and tested, but the add-account screen does not offer it: a Google client that can read Gmail
needs a verified app (a paid security assessment) and, unverified, its token expires every week.
Accounts use an app password, which is free and does not expire. The switch is
`OAuthFeature.ENABLED` in `domain/oauth/OAuthFeature.kt`; `docs/oauth-setup.md` has the steps to
create the clients if you turn it on.

## Architecture

Kotlin, Jetpack Compose and Material 3, Hilt, Room, WorkManager, AppAuth and Angus Mail. MVVM with
unidirectional data flow (StateFlow) in four layers under `com.qtekfun.ultimatemail`:

- `ui`: Compose screens and ViewModels; no business logic.
- `domain`: models, rules and interfaces (accounts, folders, threads, HTML sanitizer, signatures,
  inbox).
- `data`: Room database, the IMAP/SMTP client behind `domain` interfaces, the encrypted credential
  store, OAuth.
- `sync`: the engine that pulls and pushes, the operation queue and the conflict resolver.

**Room is the single source of truth**: the UI reads Room, never the network. User actions are
written to Room and to a persisted **operation queue** (idempotent operations, exponential
backoff); the **sync engine** pushes the queue first and then pulls each folder. Conflicts follow
fixed rules (SPEC.md, section 5): flags, last change wins; moves and labels are resolved by
message identity; drafts edited in two places are both kept; a changed UIDVALIDITY resets the
folder without losing pending operations; a send is never retried after an unclear result
without checking the Sent folder first.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Releases are described in [RELEASING.md](RELEASING.md) and
changes in [CHANGELOG.md](CHANGELOG.md).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

---

## Español

UltimateMail es un cliente de correo IMAP/SMTP para Android, libre (GPL-3.0-or-later), offline-first
y sin telemetría, con un aspecto inspirado en Mail de iOS y Gmail. Nace de la frustración con
Thunderbird para Android: interfaz anticuada, gestos lentos, problemas de sincronización y
rendimiento, y ninguna búsqueda al mover un correo a una etiqueta.

**Estado: primera versión (0.1).** Lee, escribe, busca y sincroniza correo IMAP/SMTP y se ha usado
con Gmail con contraseña de aplicación. No se ha revisado con TalkBack y no hay
notificaciones push (ver la [tabla de estado](#status), el [changelog](CHANGELOG.md), `PLAN.md` y
`SPEC.md`, estos últimos en español).

- Gmail: contraseña de aplicación (verificación en dos pasos). El acceso con Google y Microsoft por OAuth2 está implementado pero oculto (`OAuthFeature`).
- Microsoft: usuario y contraseña (o contraseña de aplicación) donde la cuenta lo permita.
- Cualquier servidor IMAP/SMTP: usuario y contraseña con TLS o STARTTLS.
- Compilar: JDK 21, `./gradlew assembleDebug`; comprobaciones: `./gradlew check`.
- Privacidad: ver [PRIVACY.md](PRIVACY.md) (bilingüe). Contribuir: [CONTRIBUTING.md](CONTRIBUTING.md).
