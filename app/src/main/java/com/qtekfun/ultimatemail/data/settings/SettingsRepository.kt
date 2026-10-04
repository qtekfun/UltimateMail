// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The preferences of this device (RF-11), kept in a [PreferenceStore]. Unknown or missing stored
 * values read as the defaults of [AppSettings], so an older or damaged file never breaks the app.
 *
 * The swipe actions are only stored here: the gestures of the conversation list (T16) read them
 * through [swipeActions] (a flow, to follow changes) or [current] (a snapshot).
 */
@Singleton
class SettingsRepository @Inject constructor(private val store: PreferenceStore) {
    /** The current settings, and every change after. */
    val settings: Flow<AppSettings> = store.changes()
        .onStart { emit(Unit) }
        .map { current() }
        .distinctUntilChanged()

    /** The swipe actions of the conversation list, and every change after. */
    val swipeActions: Flow<SwipeActions> = settings.map { it.swipe }.distinctUntilChanged()

    /** The settings right now, read synchronously (cheap), e.g. for the first frame. */
    fun current(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = enumValue(KEY_THEME, defaults.theme),
            dynamicColor = store.getBoolean(KEY_DYNAMIC_COLOR, defaults.dynamicColor),
            amoled = store.getBoolean(KEY_AMOLED, defaults.amoled),
            density = enumValue(KEY_DENSITY, defaults.density),
            previewLines = enumValue(KEY_PREVIEW_LINES, defaults.previewLines),
            showAvatars = store.getBoolean(KEY_SHOW_AVATARS, defaults.showAvatars),
            swipe = SwipeActions(
                right = enumValue(KEY_SWIPE_RIGHT, defaults.swipe.right),
                left = enumValue(KEY_SWIPE_LEFT, defaults.swipe.left)
            ),
            remoteContent = enumValue(KEY_REMOTE_CONTENT, defaults.remoteContent)
        )
    }

    fun setTheme(theme: ThemeMode) = store.putString(KEY_THEME, theme.name)

    fun setDynamicColor(enabled: Boolean) = store.putBoolean(KEY_DYNAMIC_COLOR, enabled)

    fun setAmoled(enabled: Boolean) = store.putBoolean(KEY_AMOLED, enabled)

    fun setDensity(density: DisplayDensity) = store.putString(KEY_DENSITY, density.name)

    fun setPreviewLines(lines: PreviewLines) = store.putString(KEY_PREVIEW_LINES, lines.name)

    fun setShowAvatars(enabled: Boolean) = store.putBoolean(KEY_SHOW_AVATARS, enabled)

    fun setSwipeRight(action: SwipeAction) = store.putString(KEY_SWIPE_RIGHT, action.name)

    fun setSwipeLeft(action: SwipeAction) = store.putString(KEY_SWIPE_LEFT, action.name)

    fun setRemoteContent(policy: RemoteContentPolicy) =
        store.putString(KEY_REMOTE_CONTENT, policy.name)

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T {
        val stored = store.getString(key)
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_AMOLED = "amoled"
        const val KEY_DENSITY = "density"
        const val KEY_PREVIEW_LINES = "preview_lines"
        const val KEY_SHOW_AVATARS = "show_avatars"
        const val KEY_SWIPE_RIGHT = "swipe_right"
        const val KEY_SWIPE_LEFT = "swipe_left"
        const val KEY_REMOTE_CONTENT = "remote_content"
    }
}
