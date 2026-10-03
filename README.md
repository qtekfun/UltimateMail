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

> **Status: early development, not usable as a daily mail client yet.** There is no release and no
> published APK. You can add an IMAP account with a password, sync folders and see the conversation
> list; reading, composing, search, gestures and settings are still being built. See the
> [status table](#status).

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

The roadmap is [PLAN.md](PLAN.md); the specification is [SPEC.md](SPEC.md). "Done" means it is
implemented and covered by unit tests; most of it has not yet been tried against real Gmail or
Microsoft 365 servers.

| Feature | Status |
|---|---|
| Add an account with a password or app password, with server autodetection and a connection test | Done |
| Credentials encrypted with the Android Keystore; removing an account wipes its data | Done |
| Google sign-in (OAuth2 with your own client ID) | Prototype: works on a real Gmail account from a debug-only screen; not yet part of the add-account flow |
| Microsoft sign-in (OAuth2) | Planned |
| IMAP/SMTP client (Angus Mail) | Done |
| Folder and label list, special folders first | Done |
| Operation queue (idempotent, backoff, persisted) and conflict rules | Done |
| Sync engine (UIDVALIDITY/UIDNEXT/CONDSTORE, offline window, periodic sync, pull to refresh) | Done, not yet tested against real servers |
| Threading (`X-GM-THRID` and References/subject) | Done |
| Conversation list, paging, unified inbox, "pending sync" indicator | Done |
| Safe HTML renderer (sanitizer and locked-down WebView, remote content blocked) | Done as a component and debug screen; not yet connected to a reading screen |
| Per-account signature logic | Domain logic done; editor and UI pending |
| Side drawer for folders and labels | Planned |
| Reading a conversation, attachments | Planned |
| Composing, drafts, send queue | Planned |
| Gestures and multi-select with undo | Planned |
| Move/label picker with live search | Planned |
| Search (local and on the server) | Planned |
| Settings (theme, language, gestures, offline policy) | Planned |
| Accessibility and performance pass, UI tests | Planned |

Out of scope for the first version: push with IMAP IDLE, snooze, PGP/S-MIME, aliases. See SPEC.md.

## Requirements

- Android 8.0 (API 26) or newer.
- An account with IMAP and SMTP access (see below).

## Supported providers and sign-in

| Provider | Sign-in |
|---|---|
| Gmail / Google Workspace | App password (needs 2-Step Verification), or Google OAuth2 with **your own** OAuth client (prototype) |
| Outlook.com / Microsoft 365 | Planned (OAuth2). Works today only if the account allows app passwords |
| Any IMAP/SMTP server (Dovecot, Fastmail, your own...) | Password, with TLS or STARTTLS; certificate authorities installed on the device are accepted |

## Building

You need JDK 21 and the Android SDK. Gradle is the wrapper in the repository.

```sh
./gradlew assembleDebug   # debug APK, in app/build/outputs/apk/debug/
./gradlew check           # what CI runs: unit tests, detekt, ktlint, Android Lint, Kover
```

Dependencies are verified (`gradle/verification-metadata.xml`) and their licenses are checked:
only free software is allowed, and Google Play Services, Firebase and Crashlytics fail the build.

### OAuth client IDs

Google only lets an app read mail with OAuth after a verification process, so the project does not
ship a client ID for now. To try Google sign-in, create your own Android OAuth client in Google
Cloud (package `com.qtekfun.ultimatemail`, with "Enable custom URI scheme" on) and give its ID to
the build, either with `-PUM_GOOGLE_CLIENT_ID=<id>` or as `UM_GOOGLE_CLIENT_ID=<id>` in your
`~/.gradle/gradle.properties`. The ID is public, not a secret. A step-by-step guide
(`docs/oauth-setup.md`) is coming; until then, the lessons learned are in SPEC.md, section 9
("OAuth con Google"). Microsoft is not wired up yet.

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

**Estado: desarrollo temprano, todavía no es utilizable a diario ni hay versión publicada.** Ya se
puede añadir una cuenta IMAP con contraseña, sincronizar carpetas y ver la lista de conversaciones;
leer, redactar, buscar, los gestos y los ajustes están en marcha o planificados (ver la
[tabla de estado](#status), `PLAN.md` y `SPEC.md`, estos últimos en español).

- Gmail: contraseña de aplicación, o OAuth2 con tu propio cliente de Google (prototipo).
- Microsoft: OAuth2 planificado.
- Cualquier servidor IMAP/SMTP: usuario y contraseña con TLS o STARTTLS.
- Compilar: JDK 21, `./gradlew assembleDebug`; comprobaciones: `./gradlew check`.
- Privacidad: ver [PRIVACY.md](PRIVACY.md) (bilingüe). Contribuir: [CONTRIBUTING.md](CONTRIBUTING.md).
