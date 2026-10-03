<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Accessibility audit (T22)

Scope: SPEC section 6, "Accessibility: TalkBack, touch targets of at least 48dp, font scaling,
contrast". This is a **code audit**, done without a device. Everything marked "needs a device"
in the last section has not been verified by running the app.

Audited: every file under `app/src/main/java/com/qtekfun/ultimatemail/ui` (inbox, drawer,
conversation, composer, drafts, outbox, search, settings, account setup, re-authentication,
backup, picker, shell, theme, components).

## Checklist

| Area | What was checked | Result |
|---|---|---|
| Touch targets | Every `clickable`, `selectable`, `toggleable`, `IconButton`, `TextButton`, chip, row | OK, with the intended exception of the compact density (see below) |
| Content descriptions | Every `Icon`/`Image`; `contentDescription = null` only where a sibling text or the row already says it | OK |
| Roles and state | Custom controls: switches, radio rows, checkbox rows, drawer tabs, dropdowns | OK (`Role.Switch`, `RadioButton`, `Checkbox`, `Tab`, `DropdownList`, `Button`; `stateDescription` where the state is not a checked value) |
| Headings | Titles of sections and screens | 4 gaps found and fixed |
| Reading order and forms | Composer, add account, re-authentication, settings: order follows layout order; keyboard `ImeAction.Next` chains | OK; 2 fields without a stable name fixed |
| `mergeDescendants` | Conversation rows use `clearAndSetSemantics` with one description and the row's click, long click and custom actions | OK, no abuse found |
| Live regions | Errors, progress, results that appear without a user action | 4 gaps found and fixed |
| Dialogs and sheets | `AlertDialog` for confirmations (system focus handling); the picker is a full-screen dialog whose search field takes focus | OK, needs a device pass |
| Font scaling | `sp` text only; fixed `height()` on text containers; `heightIn(max)`; `maxLines` | 2 fixes (drawer row height, composer label width); remaining `maxLines` are deliberate single-line list rows |
| Horizontal overflow at 200% | `Row`s with several texts; `FlowRow` where content can grow (chips, reply bar) | OK by reading; needs a device pass |
| RTL | `start`/`end` everywhere (no `left`/`right` layout code); directional icons | 4 custom icons did not mirror: fixed |
| Colour-only information | Unread dot, selected row, invalid recipient, starred, pending sync | OK: each has a text or state alternative (see below) |
| Contrast | Avatar palette, label chips (light/dark), star, fallback theme colours, AMOLED containers | 1 failure found and fixed (star on a light surface); the rest pass, now guarded by `ContrastTest` |

### Touch targets

All interactive elements have a 48dp minimum through `heightIn(min = MinTouchTarget)` (the
Material `IconButton`, `TextButton` and chips add their own 48dp interactive size on top).
Intentional exceptions, all driven by the user's *Compact* display density setting
(`DensityMetrics`): drawer rows are 40dp and conversation rows 56dp minimum. The setting's
warning and the exception are kept as they are.

### Colour is never the only signal

* Unread: bold sender, bold subject, and the spoken description starts with "unread"
  (`ConversationDescriber`).
* Selected: check mark replaces the avatar, plus `selected` and `stateDescription` semantics.
* Invalid recipient: red chip, plus the spoken text "invalid recipient".
* Starred and pending sync: the icon is described by the row description ("starred",
  "pending") and by its own `contentDescription` in the conversation view.
* Label chips carry the label name as text; account markers carry the account name.

## What was found and fixed

| # | Finding | Fix | Where |
|---|---|---|---|
| 1 | The star (`#F2A600`) is about 2:1 on a light surface; icons need 3:1 | `starColorOn(surface)`: a deeper amber (`StarColorOnLight`, `#B87400`, above 3:1) on light surfaces, the old amber on dark ones. Chosen from the theme surface, not from the system setting | `ui/theme/Contrast.kt`, `ui/theme/MailColors.kt`, `ConversationRowParts.kt`, `MessageItem.kt`, `ConversationScreen.kt` |
| 2 | Label chips picked their light/dark palette with `isSystemInDarkTheme()`, but the app theme can differ from the system (Settings: theme light/dark). Pastel chips on a dark app, and the reverse | The palette follows the surface of the app theme | `ui/components/LabelChip.kt` |
| 3 | Section titles were not headings, so TalkBack's heading navigation skipped them: settings sections, search "Recent" and "On the server", composer "Attachments", picker title | `semantics { heading() }` | `SettingsComponents.kt`, `SearchScreen.kt`, `ComposerParts.kt`, `MoveLabelDialog.kt` |
| 4 | The composer subject and body and the search field named themselves only with a placeholder; once the field has text a screen reader no longer says what the field is | `contentDescription` with the same text, as the recipient input already did | `ComposerScreen.kt`, `SearchScreen.kt` |
| 5 | Progress that appears after a user action was silent, or read twice (spinner description plus the same text): body loading, local search, server search, export and import progress | Polite live regions; the decorative spinner is cleared where a text beside it says the same | `MessageBodyContent.kt`, `SearchScreen.kt`, `SearchServerPart.kt`, `BackupComponents.kt` |
| 6 | Drawer rows had a fixed `height()`; at 200% font a two-line label would be clipped | `heightIn(min = ...)`: the density height stays the minimum | `DrawerContent.kt` |
| 7 | Composer recipient labels ("To", "Cc", "Bcc", and "Para", "Cco" in Spanish) had a fixed 56dp width: clipped or wrapped by letter at large fonts | `widthIn(min = ...)` | `ComposerParts.kt` |
| 8 | Custom directional icons (reply, reply all, forward, move) did not flip in right-to-left layouts | `autoMirror = true` for those four; objects (inbox, archive, paperclip, folder, label, star, download) stay as they are | `ui/components/MailIcons.kt` |

## Tests

* `ui/theme/ContrastTest`: the WCAG ratio itself (21:1, 1:1, symmetry, the 4.54:1 reference
  grey), and the ratios of every custom colour: initials on the avatar palette (4.5:1), label
  chips in both palettes (4.5:1), overflow chip and secondary text, the star (3:1) on light,
  dark and AMOLED surfaces, primary/tertiary/secondary text on the fallback palettes, the
  AMOLED containers, selection and error containers. The test also asserts the *old* star
  colour fails on light, so it cannot pass vacuously.
* `ui/components/MailIconsTest`: which icons mirror.

## Left as it is (decisions, not defects)

* Compact density below 48dp: intentional, per CLAUDE.md and SPEC.
* Avatar initials use a font size derived from the circle, not from the font scale: the
  circle does not grow with the font, and the avatar is hidden from screen readers.
* Conversation rows show the sender and subject on one line (ellipsis) at any font scale; the
  full text is in the row's spoken description and in the conversation view. The preview line
  allows two lines.
* Swipe actions in right-to-left layouts follow the layout direction (`StartToEnd`), as the
  system does; the "swipe right" setting then means "towards the end". Screen readers use the
  custom actions of the row, which do not depend on direction.
* Dynamic colour (wallpaper) schemes are produced by the system (Material You) and are not
  audited here.

## Needs a human with TalkBack on the phone

1. Inbox: swipe through three rows with TalkBack on. Each row must be read once ("unread, from
   ..., subject ..., N messages, attachment, starred"), then offer the actions (select,
   archive, delete...) in the actions menu. Double tap opens; double tap and hold selects.
2. Inbox: pull-to-refresh is not possible with TalkBack; the "Refresh" custom action of the
   list must work.
3. Open a conversation and swipe through the toolbar: back, star/unstar (name changes with
   the state), mark unread, archive, move, delete. Then the subject must be a heading
   (TalkBack "headings" navigation lands on it).
4. In a conversation with several messages: each folded message says "collapsed" and its
   action "expand"; the expanded one says "expanded". Check the sender, date and recipients
   line ("show details").
5. Open an HTML message: TalkBack must read the body of the WebView (it is not focusable by
   keyboard on purpose). Check that links are announced and a tap asks for confirmation.
6. Composer: go through To, Cc/Bcc, Subject, Body with swipe. The field names must be spoken
   while typing and after (subject, body). Add a recipient, then an invalid one: the chip must
   say "invalid recipient" and offer removal. Check the suggestion list.
7. Settings: headings navigation must jump between the sections; switches say on/off, the
   dropdown rows say their value and open a list.
8. Search: type a query; "searching" must be spoken once, then the results, or "no results".
   Check "Recent searches" is a heading and the clear action is reachable.
9. Font size: set the system font to the largest, then display size to the largest. Check the
   drawer (rows, 2-line labels), the composer (To/Cc/Bcc labels, chips wrapping), add account
   (port and security row), settings rows, the reply bar (wraps onto a second line), dialogs
   (buttons visible without clipping). No horizontal scroll must appear.
10. Display density: with Comfortable, Default and Compact, confirm the 48dp rule holds in the
    first two, and that Compact keeps its warning.
11. Dark theme, AMOLED, and a light theme set in the app while the system is dark (and the
    reverse): label chips, the star, unread time and badges must stay readable.
12. Right-to-left: switch the language to Arabic or Hebrew (developer options: "Force RTL
    layout direction"). The drawer, back arrows, reply/forward/move icons and the swipe
    background must mirror.
13. Re-authentication and add account: errors (wrong password) must be spoken when they appear
    (assertive live region) and focus must remain usable.
14. Switch Access or a keyboard: Tab through the inbox, the composer and settings; check a
    visible focus indicator and no focus trap in the move/label picker dialog.
15. Reduce animations (system setting): swipes and list changes must not animate
    (`rememberReduceMotion`).
