<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# UI tests (T23)

The key flows of SPEC section 7 are covered by instrumented Compose tests in
`app/src/androidTest/java/com/qtekfun/ultimatemail/`. They use Hilt, with test modules that
replace the network, the database, the credential store, WorkManager, the preferences and the
clock, so they never reach a real server or touch a real account.

## Status: run on a device (2026-10-04)

All the tests (the 11 first ones and the 26 of the redesign screens, see below) pass on a physical phone (PGEM10, Android 16) through `am instrument`, see below. The first run
found three tests out of date with the redesign (density sizes, the new swipe defaults, the Mailboxes
menu with two rows that match an account's address) and a missing Hilt binding in the test module; they
were fixed. There is no emulator on the development machine, so no other device was tried.

## How to run them

Use a **separate device or emulator that holds no data you care about** with
`./gradlew connectedDebugAndroidTest`, or, on a phone that has your real UltimateMail, run them
**without uninstalling the app**:

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.qtekfun.ultimatemail.test/com.qtekfun.ultimatemail.HiltTestRunner
# one class: add  -e class com.qtekfun.ultimatemail.uitests.SwipeArchiveUiTest
adb uninstall com.qtekfun.ultimatemail.test     # only the TEST package, never the app
```

> **Warning.** `connectedDebugAndroidTest` installs the app and the test APK and **uninstalls
> both when it finishes**. Uninstalling the app **wipes all of its data**: on a device with a real
> account, that deletes the synced mail and the stored credentials. Never run it on a device
> that has your real UltimateMail installed. The tests themselves use an in-memory database and
> in-memory credentials, so `am instrument` as above leaves the real data alone (the screen is
> driven for about a minute); the uninstall is done by the Android Gradle Plugin, not by them.
> Keep the screen on and unlocked while they run. On some phones (OPPO) the installer's "done" screen stays
> in front right after `adb install`: go to the home screen and wait a few seconds before running, or the first
> test fails with "No compose hierarchies found".

To run one class with Gradle: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatemail.uitests.SwipeArchiveUiTest`.

The tests read every text from the string resources, so the device language does not matter.
Animations may be left on; turning them off in the developer options makes runs faster.

## What each test covers

| Class | Asserts |
| --- | --- |
| `AddAccountUiTest` | An empty form shows the missing email and password and adds nothing. A wrong password shows the authentication error and stores nothing. The right password creates the account, stores the password, asks for the first sync, opens the Inbox with the synced message and lists the folders (Inbox, Sent, Drafts, Archive, Trash and a custom one) in the side menu. |
| `SwipeArchiveUiTest` | With the right swipe set to Archive (a fresh install marks read/unread), swiping a row right removes it and shows "Conversation archived" with Undo; the other row stays; Undo brings the row back and leaves no move to Archive in the queue. A second action makes the first one final: the first message ends up in Archive on the (fake) server. |
| `MoveWithSearchUiTest` | A long press selects (count shows), "More actions" then "Move to..." opens the picker, a search with no match shows "No matches", "FACTURACION" (upper case, no accent) finds "Facturación" and hides the other folders, tapping it removes the row, shows "Moved 1 message to ..." with Undo and queues a MOVE to that folder. |
| `ComposeOfflineUiTest` | With the network off, Compose, a recipient, subject and body, Send: "Sending..." with Undo shows, goes away after the 5 s window, the drawer gets an Outbox entry, and the Outbox shows the message as "Waiting to be sent". One SEND is queued, nothing reached SMTP, a sync was requested. |
| `SignaturePerAccountUiTest` | Two accounts with different signatures. A new message starts with the signature of the account that writes it (with the `-- ` delimiter); switching From swaps only that block (text typed by the user stays) and switching back restores the first one. A message started after switching the menu to the second account gets the second signature. |
| `ReauthenticationUiTest` | An account in the "sign in again" state shows that line in the menu; tapping it opens the sign-in screen; a wrong password is refused and nothing is stored; the right one stores it, clears the state, asks for a user-initiated sync and the mail already on the device is still there. |
| `DensitySettingUiTest` | Changing Display density in Settings changes the height of a side menu row: 48 dp (default), 52 dp (comfortable), 38 dp (compact), and the setting is stored. |
| `BatteryHintUiTest` | The one-time question about the battery exemption appears after the first account exists and "Not now" is final; it does not appear without an account; Settings shows the "Background sync" section with the Allow button. Skipped on a device where the app is already exempt. |
| `InboxShellUiTest` | The list screen of the iOS-style redesign. The large title shows the mailbox name. The bottom bar has the filter button, the Search field and Compose; Search opens the search screen and Compose the composer. The filter menu (All, Unread, Starred, With attachments) really changes the rows and the subtitle says "Filtered by: ...". Edit shows an empty selection circle on every row (the rows become selectable, none selected), the action bar (Mark, Move, Archive, Trash) disabled with nothing picked, and Done leaves. Select all selects every row and enables the actions. A long press selects exactly one row. |
| `ConversationRowUiTest` | The sentence a row speaks starts with "Unread" only for an unread conversation (the dot slot has no other hook). The Preview setting (set live through `SettingsRepository`): with 2 lines the snippet is in the row and the row is taller, with None it is gone and the row is shorter, and back to 2 lines it returns. |
| `MailboxesMenuUiTest` | The Mailboxes menu with two accounts: "All inboxes" with the summed counter, each account's Inbox with its own counter, in that order; the special mailboxes card (Drafts, Sent, Archive, Trash) in order below them; the Accounts section closed by default (the custom folder is not listed) and opening to show it; Settings at the bottom, opening Settings. |
| `ReaderTopBarUiTest` | Opening a conversation: Back with the mailbox name, previous and next arrows and the overflow button. At the first conversation Previous is disabled, at the last one Next is; the arrows open the neighbour conversations, and Back still returns to the list. |
| `ReaderBottomBarUiTest` | The bottom bar of the reader has Trash, Archive, Move, Reply and Compose; Archive and Trash leave the conversation and queue the move to Archive / Trash; Move opens the picker; Reply opens a menu with Reply, Reply all and Forward (Forward opens the composer); Compose opens a new message; the overflow menu has Star (which turns into Remove star) and Mark as unread. |
| `DraftsSwipeUiTest` | In Drafts, swiping a draft away hides it and shows "Draft discarded" with Undo (the draft is still stored until the window ends); Undo brings the row back. |

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

The tests find nodes by text, content description and actions; no test tags were needed. Two
small changes in the app came with the redesign tests (see decision 40): a row only exposes the
"selected" state while the list is in selection mode, and the list preview text is now really stored.
`FakeMailbox.deliver` takes flags and an attachment marker, and `seedAccount` an `onServer` block, to
seed read, starred and attachment messages.

## What could not be verified

- They ran on one phone only; other screen sizes, languages and Android versions are untested.
- The colour and shape of the unread dot and of the selection circles are not checked (no hook, and
  a screenshot comparison would depend on the device); the tests check the row sentence, the
  selectable/selected state and the row height instead.
- The swipe of the redesign's rows to read/unread and Trash, the Edit action buttons doing their
  action (Mark, Move, Archive, Trash from the list), the reader's Mark as unread and the collapse of the
  large title while scrolling have no UI test yet.
- The Compose test clock controls snackbar timeouts, so the tests do not rely on a snackbar
  expiring by itself; they use Undo, or a second notice that replaces the first.
- Real servers, TLS, OAuth and WorkManager are out of scope: they are not part of these tests.
