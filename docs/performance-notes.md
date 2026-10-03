<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Performance notes (T22)

Targets (SPEC section 6): **cold start with local data under 1.5 s on mid-range hardware** and
**60 fps scrolling with the reference volume** (about 50,000 messages in Room, 90-day window).

This was a **static review plus host-JVM measurements**, done without a device. Nothing here
was measured on the phone: the measurement plan at the end is for that.

## What was reviewed

### Startup path

* `UltimateMailApp.onCreate`: injects `SyncWorkerFactory` (it takes `Provider<SyncEngine>`, so
  the engine and its graph are not built at startup), the scheduler and two coroutine
  parameters. It used to call `WorkManager.getInstance()` and enqueue two works on the main
  thread; the first use of WorkManager builds its own Room database, schedulers and a
  `ForceStopRunnable`. **Fixed**: both calls now run on `Dispatchers.IO` through the
  application scope (`UltimateMailApp.kt`). WorkManager's own initializer is already removed in
  the manifest (on-demand configuration).
* Room database: `Room.databaseBuilder(...)` only describes the database; it is opened on the
  first query, which Room 3 runs on the injected IO dispatcher (`setQueryCoroutineContext`). No
  open on the main thread. Migrations are SQL only.
* Keystore: `AndroidKeystoreCipher` opens the key store and loads the key inside each
  `encrypt`/`decrypt` call; there is no eager initialisation, and nothing in `onCreate` or in the
  startup graph (the workers' engine is behind a `Provider`) reads a secret. The sync engine
  reads credentials on its IO dispatcher. A first secret read on the main thread would show in
  the cold-start trace as a `KeyStore` slice: look for it in test 4 of the plan.
* `SharedPreferences`: one read on the main thread, deliberate: `SettingsViewModel` starts from
  the stored `AppSettings` (`repository.current()`) so the first frame already has the right
  theme, density and AMOLED setting instead of flashing the defaults. The file is a few
  booleans and strings; the first `getSharedPreferences` load is the cost. If the measurement
  shows it in a trace, the alternative is a splash that waits for an async read; it is not
  worth it before measuring.
* `MainActivity` takes 13 activity-scoped view models with `by viewModels()` and passes
  them all to `AppRoot`, so all of them are constructed at the first composition even though
  only the inbox and the drawer are visible. Their constructors only assemble flows (`stateIn`
  with `WhileSubscribed`, so nothing runs until a screen collects), apart from
  `ComposeEntryViewModel` (a counter flow started eagerly) and `SearchViewModel` (an `init`).
  It is cheap and was **not changed**: making `AppRoot` take lazies is a wide change to a file
  the other open branches touch. If the cold start misses, this is the first thing to try
  (pass `Lazy<...>` or create the view models inside the screens that use them).

### Lists

* Every `LazyColumn` has stable `key`s (inbox, drawer, search, drafts, outbox, settings,
  picker). **Added** `contentType` for the conversation rows of the inbox and of the search
  results so the lists reuse the compositions of rows that scrolled out
  (`InboxScreen.kt`, `SearchScreen.kt`). The other lists have one row type.
* Date formatting is hoisted: one `MessageTimeFormatter` per configuration
  (`rememberMessageTimeFormatter`), `DateTimeFormatter`s are built once, per row only
  `format()` runs. Highlights are `remember`ed. No regex or HTML conversion in composition.
* `InboxRow` takes the whole `InboxState`: any new state (for example the refresh flag)
  recomposes the visible rows (about 10-15) and runs `SwipePlanner.decide` twice for each. It is
  bounded work, so it was **not changed without a measurement**; if `gfxinfo` shows janky
  frames while syncing, the fix is to pass each row only what it needs (its item, the
  selected flag, the two swipe decisions) so rows can skip.
* Swipe icons, avatar circles and label chips are plain drawing; no bitmaps are decoded for the
  list, so there is no image cache to review.

### Reading screen

* `SafeHtmlView` sizes the WebView to its content in `MailWebView.onMeasure` (clamped by
  `ReaderHeight`), inside the thread's own scroll, so there is no nested scroll container and
  no JavaScript round trip to learn the height. The page is wrapped once per
  `(content, allowRemoteContent, colors)` with `remember`, and `loadDataWithBaseURL` only runs
  when the page changed (`webView.tag != page`). The WebView is released with `destroy()`.
  The cost is the WebView creation itself, once per expanded HTML message: a long thread with
  many expanded messages creates many WebViews; messages are folded by default except the first
  unread or newest one, which keeps this small.

### Database (measured on the host JVM, bundled SQLite, in memory, 50,000 messages)

`QueryPlanTest` runs `EXPLAIN QUERY PLAN` on the queries behind the screens over a 50,000
message dataset (two accounts, four folders, threads of four) and asserts the plans. The SQL of
each query is now a constant (`CONVERSATIONS_OF_FOLDER_SQL`, `UNIFIED_INBOX_SQL`,
`UNREAD_PER_FOLDER_SQL`, `THREAD_SQL`, `BODY_WORK_SQL`, `OUTBOX_SQL`, `DRAFTS_BY_STATE_SQL`)
used by the DAO annotation and by the test, so the test cannot drift from what runs.

**Found and fixed: the conversation list was quadratic.** For every row, "the latest message
of this conversation" (`ORDER BY sentAt DESC LIMIT 1`) was planned on the
`(accountId, folderPath, sentAt)` index to avoid a sort, which walks the folder from its newest
message down to the conversation: the cost of a row grows with its position. A folder of
12,484 conversations took **9.9 s**; with `INDEXED BY index_message_accountId_folderPath_threadId`
on that lookup it takes **34 ms** (about 290 times less), and the unified inbox over 24,984
conversations 118 ms. The list is paged (a limit that grows with scrolling), so the user
felt it as a cost that grew with how far they had scrolled, repeated on every database change
(the list is a `Flow`, re-run whenever messages, folders or queued operations change). The same
hint is on the single-conversation query (`THREAD_SQL`), which planned on the date index and
walked the whole folder to open a conversation. No schema change: the index existed. A rename
or a drop of that index makes the query fail to prepare, and the plan test names it.

| Query | Plan | Verdict |
|---|---|---|
| Conversations of a folder | date index for the order, thread index per row, queue index per row | OK, no scan, no sort of the folder |
| Unified inbox | folder scan (a handful of rows), date index, thread index, one sort of the inbox rows | OK; linear in inbox size, 118 ms for 25,000 conversations on a desktop |
| Unread per folder | `SEARCH message USING INDEX ..._sentAt (accountId=?)`, then `seen` per row | OK but linear: about 5 ms for 25,000 rows on a desktop, and it re-runs on every message change. A covering index `(accountId, seen, folderPath)` would make it index-only; it needs a schema migration to version 6, **not done** (see "Decision") |
| Conversation (thread) | thread index | OK |
| FTS search | already covered by `SearchPlanTest` (full-text index, rows by key, no scan) | OK |
| Pending operations of a message | `index_pending_operation_accountId_folderPath_uid` | OK |
| Outbox of an account | `(accountId, nextAttemptAt)` index plus a tiny sort | OK (the queue is small) |
| Outbox of all accounts | `SCAN pending_operation` plus a sort | OK while the queue is small; not asserted |
| Drafts of an account | `(accountId, state, updatedAt)` | OK |
| Body download work list | `(accountId, messageId)` index, then filter and one sort per batch | Works, runs in the background, about 7 ms per batch of 100 on a desktop; linear in the account's messages for each batch |

**Decision for the user:** the two linear queries (unread per folder, body work list) would
be index-only or index-ordered with an index of `message` `(accountId, seen, folderPath)` and
`(accountId, sentAt)` respectively. Both need migration 5 to 6. They are small on a desktop and
not obviously a problem on a phone, so I did not add a migration that other open branches could
collide with. If the device measurement shows the folder badges or the body download costing
frames, that migration is the next step.

## R8 and the release build

`./gradlew assembleRelease` (minify and resource shrinking on) succeeds with **no R8
warnings or missing-class messages**; the unsigned APK is 3.3 MB. The mapping, seeds and
configuration of the build were inspected (not run: no device):

* **Angus Mail providers.** `META-INF/jakarta.providers`, `javamail.providers` and the
  `.default.providers` files are in the APK (the `merges` in `build.gradle.kts` keep every
  provider), and list `imap`, `imaps`, `gimap`, `gimaps`, `smtp`, `smtps` (and pop3). The
  classes they name (`IMAPStore`, `IMAPSSLStore`, `GmailStore`, `GmailSSLStore`,
  `SMTPTransport`, `SMTPSSLTransport`) are in the mapping under their own names, because
  `app/proguard-rules.pro` keeps `org.eclipse.angus.**`, `jakarta.mail.**` and
  `jakarta.activation.**`. The mailcap and mimetypes files and
  `org.eclipse.angus.activation.MailcapRegistryProviderImpl` (loaded through `ServiceLoader`)
  are kept as well. Nothing to add.
* **Room.** `UltimateMailDatabase_Impl` is in the seeds, from Room's own consumer rule
  (`-keep class * extends androidx.room3.RoomDatabase { void <init>(); }`).
* **WorkManager.** `SyncWorker` is in the seeds, with its constructor, from WorkManager's
  consumer rules (`-keepnames` and the keep of the worker constructor): the class name WorkManager
  stores in its database survives, which matters for periodic work across app updates.
* **Hilt.** Generated components and factories are referenced from generated code; they are
  in the seeds where needed and the build has no warnings.
* **AppAuth.** The classes are present and renamed (the library does not use reflection);
  `AuthorizationManagementActivity` is kept (it is in the manifest).
* **Findings.** (1) The keep rules for Angus and Jakarta are broad (`{ *; }`): the whole of both
  libraries is kept, which is the safe choice for reflection-loaded providers and costs APK
  size. Narrowing them to the providers, the SSL socket factories and the activation SPI would
  save some size, but a minified build that fails at runtime on a sign-in is the classic
  failure, so it is not worth doing without a device test of every protocol path. (2) The
  release APK has not been **run**: sign it locally and do one login, one sync, one send and one
  attachment download on the phone before a release candidate.

## Measurement plan (for the phone)

Package: `com.qtekfun.ultimatemail`, launcher activity `.ui.MainActivity`. Use a **release**
build (R8 minified, `./gradlew assembleRelease`, signed with a local key) on the mid-range
phone, with the account synced and the reference volume loaded (about 50,000 messages, 90
days). Debug builds are not representative (no R8, debuggable, slower Compose).

Notes before measuring: charge the phone, disable battery saver, same network, close other apps,
let the first sync finish (check the Outbox/pending counters are zero), and run each
measurement at least 10 times and report the median and the worst value. Without a baseline
profile the first launches after an install run mostly interpreted/JIT code: do one warm-up
launch and then `adb shell cmd package compile -f -m speed com.qtekfun.ultimatemail` to measure
the steady state, and report the first-install number separately.

### 1. Cold start (threshold: median under 1500 ms)

```sh
adb shell am force-stop com.qtekfun.ultimatemail
adb shell am start -W -n com.qtekfun.ultimatemail/.ui.MainActivity
```

Read `TotalTime` (to the first frame of the activity) and `WaitTime`. Repeat 10 times with a
`force-stop` before each. A better cold-start figure is "time to the first drawn inbox": watch
```sh
adb logcat -c
adb shell am force-stop com.qtekfun.ultimatemail
adb shell am start -W -n com.qtekfun.ultimatemail/.ui.MainActivity
adb logcat -d | grep -E "Displayed com.qtekfun.ultimatemail"
```
and, for the time until the list is complete, `adb shell dumpsys activity activities | grep -i
"fully drawn"` after `reportFullyDrawn` (not called yet; see "Follow-ups").

Pass: median `TotalTime` < 1500 ms. Fail otherwise; then capture a trace (below) and start with
the items under "If it fails".

### 2. Scrolling (threshold: at most 5% janky frames and 90th percentile frame time of 16.6 ms or less)

```sh
adb shell dumpsys gfxinfo com.qtekfun.ultimatemail reset
# scroll the inbox (the 50,000-message account, "All" filter) from top to about 2,000 rows
# down and back, at normal speed, for 20 seconds; then:
adb shell dumpsys gfxinfo com.qtekfun.ultimatemail
```

Read "Total frames rendered", "Janky frames" (and its percentage), "50th/90th/95th/99th
percentile". A scripted flick, so every run is the same:
```sh
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 1800 540 500 250; done
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 500 540 1800 250; done
```
(adjust the coordinates to the screen). Repeat on: the inbox of one account, the unified
inbox, search results (a common word) and a long thread. With the frame timeline available
(Android 12+):
```sh
adb shell dumpsys gfxinfo com.qtekfun.ultimatemail framestats
```
Pass: janky frames <= 5% and 90th percentile <= 16 ms (60 fps), 99th percentile < 32 ms.
Also scroll **deep** (to about row 2,000 and further, the list grows by pages) and check the
frame time does not grow with depth: that was the quadratic query fixed in this task.

### 3. Memory

```sh
adb shell dumpsys meminfo com.qtekfun.ultimatemail
```
Take it after the cold start and after the scroll test; report "TOTAL PSS", "Java Heap",
"Native Heap", "Graphics". There is no threshold in the SPEC; the purpose is to compare to the
previous measurement and to notice a leak: the heap after scrolling 2,000 rows and returning
to the top must come back close to the one after the start. Open and close ten conversations
with HTML and check "Views"/"WebViews" in the same report goes back to a low number.

### 4. Traces (optional, when something fails)

```sh
adb shell perfetto -o /data/misc/perfetto-traces/um.pftrace -t 15s sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory
```
(start it, perform the launch or the scroll, pull it with `adb pull`, open it in
ui.perfetto.dev). For CPU samples of a launch:
```sh
adb shell simpleperf record -p $(adb shell pidof com.qtekfun.ultimatemail) --duration 10 -o /data/local/tmp/perf.data
```
Look at main-thread slices during `bindApplication`, `activityStart` and the first `Choreographer#doFrame`.

### 5. Sync does not make the UI jank

Repeat test 2 while a sync runs (pull to refresh at the start of the scroll). The recomposition
of visible rows on every state change (see "Lists") is the suspect if frames fail only then.

### If it fails

1. Cold start: the 13 view models created in `MainActivity` (make them lazy), the settings
   `SharedPreferences` read, Hilt graph size; check the trace for `WorkManager` (moved off
   the main thread in this task).
2. Scrolling: narrow `InboxRow` to the data it needs; check `SwipeableConversationRow`
   (the `SwipeToDismissBox` state per row) and `Modifier.animateItem()`.
3. Badges and body download: the migration to version 6 described above.
4. Add a baseline profile (needs `androidx.profileinstaller`, a new dependency: ask first).

## Follow-ups (not done)

* `reportFullyDrawn()` once the first page of the inbox is on screen, so the system and
  `am start -W` can report time to a useful screen.
* Baseline profile (new dependency, needs the owner's decision).
* Migration to version 6 with the two indexes, only if the device numbers ask for it.
