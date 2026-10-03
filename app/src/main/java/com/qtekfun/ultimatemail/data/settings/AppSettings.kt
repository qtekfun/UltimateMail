// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

/** Light, dark, or whatever the system uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How much fits on screen, like Gmail's display density (RF-11): it sizes the rows of the side
 * menu and of the conversation list. [COMPACT] trades touch target size for more rows.
 */
enum class DisplayDensity { COMFORTABLE, DEFAULT, COMPACT }

/** What swiping a conversation row does (RF-11). The gestures themselves arrive with T16. */
enum class SwipeAction { ARCHIVE, DELETE, MOVE, TOGGLE_READ, TOGGLE_STAR, NONE }

/** What the app does with images and other remote content of HTML mail (RF-04). */
enum class RemoteContentPolicy {
    /** Always blocked: nothing is fetched from a remote server. The default. */
    NEVER,

    /** Blocked, with an offer to show it for one message. */
    ASK
}

/** The action of each swipe direction on a conversation row. */
data class SwipeActions(
    /** Swiping from the left edge towards the right. */
    val right: SwipeAction = SwipeAction.ARCHIVE,
    /** Swiping from the right edge towards the left. */
    val left: SwipeAction = SwipeAction.DELETE
)

/**
 * Preferences of this device (RF-11). [amoled] only applies to the dark theme. Settings that
 * belong to one account (signature, offline window, folders) live in the account, not here.
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val density: DisplayDensity = DisplayDensity.DEFAULT,
    val swipe: SwipeActions = SwipeActions(),
    val remoteContent: RemoteContentPolicy = RemoteContentPolicy.NEVER
)
