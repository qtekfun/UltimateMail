<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# UI tests (T23)

The key flows of SPEC section 7 are covered by instrumented Compose tests in
`app/src/androidTest/java/com/qtekfun/ultimatemail/`. They use Hilt, with test modules that
replace the network, the database, the credential store, WorkManager, the preferences and the
clock, so they never reach a real server or touch a real account.

## Status: compiled, not run

These tests compile (`./gradlew assembleDebugAndroidTest`) and pass ktlint, detekt and Lint, but
**they were never run on a device or emulator**: the development machine only had a phone with a
real synced account, and running instrumented tests there is dangerous (see below). Expect to
adjust selectors or timings the first time they run. Please run them once on an emulator and
report or fix what fails.

## How to run them

Use a **separate device or emulator that holds no data you care about**:

```
./gradlew connectedDebugAndroidTest
```

> **Warning.** `connectedDebugAndroidTest` installs the app and the test APK and **uninstalls
> both when it finishes**. Uninstalling the app **wipes all of its data**: on a device with a real
> account, that deletes the synced mail and the stored credentials. Never run it on a device
> that has your real UltimateMail installed. The tests themselves use an in-memory database and
> in-memory credentials, but the uninstall is done by the Android Gradle Plugin, not by them.

To run one class: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatemail.uitests.SwipeArchiveUiTest`.

The tests read every text from the string resources, so the device language does not matter.
Animations may be left on; turning them off in the developer options makes runs faster.

## What each test covers

| Class | Asserts |
| --- | --- |
| `AddAccountUiTest` | An empty form shows the missing email and password and adds nothing. A wrong password shows the authentication error and stores nothing. The right password creates the account, stores the password, asks for the first sync, opens the Inbox with the synced message and lists the folders (Inbox, Sent, Drafts, Archive, Trash and a custom one) in the side menu. |
| `SwipeArchiveUiTest` | Swiping a row right removes it and shows "Conversation archived" with Undo; the other row stays; Undo brings the row back and leaves no move to Archive in the queue. A second action makes the first one final: the first message ends up in Archive on the (fake) server. |
| `MoveWithSearchUiTest` | A long press selects (count shows), "More actions" then "Move to..." opens the picker, a search with no match shows "No matches", "FACTURACION" (upper case, no accent) finds "Facturación" and hides the other folders, tapping it removes the row, shows "Moved 1 message to ..." with Undo and queues a MOVE to that folder. |
| `ComposeOfflineUiTest` | With the network off, Compose, a recipient, subject and body, Send: "Sending..." with Undo shows, goes away after the 5 s window, the drawer gets an Outbox entry, and the Outbox shows the message as "Waiting to be sent". One SEND is queued, nothing reached SMTP, a sync was requested. |
| `SignaturePerAccountUiTest` | Two accounts with different signatures. A new message starts with the signature of the account that writes it (with the `-- ` delimiter); switching From swaps only that block (text typed by the user stays) and switching back restores the first one. A message started after switching the menu to the second account gets the second signature. |
| `ReauthenticationUiTest` | An account in the "sign in again" state shows that line in the menu; tapping it opens the sign-in screen; a wrong password is refused and nothing is stored; the right one stores it, clears the state, asks for a user-initiated sync and the mail already on the device is still there. |
| `DensitySettingUiTest` | Changing Display density in Settings changes the height of a side menu row: 48 dp (default), 56 dp (comfortable), 40 dp (compact), and the setting is stored. |

## How the fakes work

Everything lives in `androidTest/.../testing/`.

- `HiltTestRunner` runs the tests on a generated Hilt application built from `TestAppBase`. It is
  not `UltimateMailApp`, so the periodic sync and the sync-on-start never run.
- Each production module that touches the outside world is replaced by a `@TestInstallIn` module
  that provides the same bindings (`TestPersistenceModules.kt`, `TestMailModules.kt`,
  `TestSettingsModules.kt`). The production wiring is not changed.
- **Database**: an in-memory Room database per test. Files (attachments, outbox) go under the
  cache folder.
- **Mail**: `FakeMailWorld` holds one `FakeMailbox` per address (folders, UIDs, messages) and an
  `online` switch. `FakeMailConnector`, `FakeMailSender` and `FakeConnectionTester` only talk to
  it; `FakeMailSession` implements the `MailSession` interface (move really moves, flags change).
  The real sync engine runs against it, so what a test sees went through the real sync code.
- **Credentials**: `InMemoryVault`. The connection tester accepts one password per mailbox.
- **Scheduler**: `TestSyncScheduler` records every `requestSync` and, only when `runSyncs` is on,
  also runs the real `SyncEngine` in the background. Tests that look at what stays in the queue
  leave it off.
- **Preferences**: in-memory settings, recent destinations, recent searches and OAuth client IDs.
- **Clock**: frozen at the start of the test. The 5 s send window uses coroutine delays, so it
  takes real time.
- `Seeds.kt` creates an account the way a finished sync leaves it: it fills the fake server,
  inserts the account and runs one real sync.

No production code was changed for these tests and no test tags were needed: the tests find
nodes by text, content description and actions.

## What could not be verified

- Nothing ran on a device or emulator. Selectors (merged semantics of text fields, drawer rows,
  popup menus), gestures (`swipeRight`, `longClick`) and timings are reasoned from the code.
- The Compose test clock controls snackbar timeouts, so the tests do not rely on a snackbar
  expiring by itself; they use Undo, or a second notice that replaces the first.
- Real servers, TLS, OAuth and WorkManager are out of scope: they are not part of these tests.
