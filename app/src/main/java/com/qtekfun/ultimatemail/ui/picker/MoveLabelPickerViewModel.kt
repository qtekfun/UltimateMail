// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.folder.FolderTree
import com.qtekfun.ultimatemail.domain.picker.CheckState
import com.qtekfun.ultimatemail.domain.picker.FolderSearch
import com.qtekfun.ultimatemail.domain.picker.LabelSelection
import com.qtekfun.ultimatemail.domain.picker.MoveLabelActions
import com.qtekfun.ultimatemail.domain.picker.PickerCandidates
import com.qtekfun.ultimatemail.domain.picker.PickerFolder
import com.qtekfun.ultimatemail.domain.picker.PickerListing
import com.qtekfun.ultimatemail.domain.picker.PickerMode
import com.qtekfun.ultimatemail.domain.picker.PickerOperations
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.domain.picker.PickerResult
import com.qtekfun.ultimatemail.domain.picker.PickerSource
import com.qtekfun.ultimatemail.domain.picker.RecentDestinations
import com.qtekfun.ultimatemail.domain.picker.SearchTarget
import com.qtekfun.ultimatemail.domain.picker.shownPath
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the picker ended: with something to do, or with nothing changed. */
sealed interface PickerFinish {
    data class Applied(val result: PickerResult) : PickerFinish

    data object Unchanged : PickerFinish
}

/**
 * What the picker shows. [loading] is true until the folders were read; [unavailable] when the
 * account no longer exists. [canApply] is true in label mode once a checkbox was changed.
 */
data class PickerState(
    val loading: Boolean = true,
    val unavailable: Boolean = false,
    val mode: PickerMode = PickerMode.FOLDERS,
    val query: String = "",
    val listing: PickerListing = PickerListing.Empty,
    val canApply: Boolean = false,
    val applying: Boolean = false,
    val finished: PickerFinish? = null
)

/**
 * The view model of the move / label picker for one [PickerRequest] (RF-06). In folder mode a tap
 * on a folder moves the messages at once; in label mode taps toggle labels and [apply] saves them.
 * Create it with [Factory]; the dialog does that itself.
 */
class MoveLabelPickerViewModel(
    private val request: PickerRequest,
    private val source: PickerSource,
    private val actions: MoveLabelActions,
    private val recents: RecentDestinations,
    private val io: CoroutineDispatcher,
    private val roleNames: Map<FolderRole, String> = emptyMap()
) : ViewModel() {
    /** Everything that depends on the folders of the account. */
    private class Loaded(
        val mode: PickerMode,
        val candidates: PickerCandidates,
        val search: FolderSearch,
        val recent: List<String>,
        val messageLabels: List<Set<String>>
    )

    /** The latest folders; written while the state is observed, read by the taps. */
    @Volatile
    private var current: Loaded? = null
    private var selectionStarted = false

    /** Null when the account is gone. Reads Room only while someone watches the state. */
    private val loaded: Flow<Loaded?> = flow {
        val account = source.account(request.accountId)
        if (account == null) {
            emit(null)
        } else {
            emitAll(
                source.observeTree(request.accountId).map { tree ->
                    load(source.modeOf(account, tree), tree).also(::keep)
                }
            )
        }
    }
    private val query = MutableStateFlow("")
    private val selection = MutableStateFlow(LabelSelection.of(emptyList()))
    private val progress = MutableStateFlow<Pair<Boolean, PickerFinish?>>(false to null)

    val state: StateFlow<PickerState> = combine(
        loaded,
        query,
        selection,
        progress
    ) { loaded, query, selection, (applying, finished) ->
        if (loaded == null) {
            PickerState(loading = false, unavailable = true, query = query)
        } else {
            val labels = loaded.mode == PickerMode.LABELS
            val listing = PickerListing.build(
                loaded.candidates,
                query,
                loaded.recent,
                loaded.search
            ) { folder -> if (labels) selection.stateOf(folder.value) else CheckState.UNCHECKED }
            PickerState(
                loading = false,
                mode = loaded.mode,
                query = query,
                listing = listing,
                canApply = labels && selection.changed && !applying,
                applying = applying,
                finished = finished
            )
        }
    }.flowOn(io).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        PickerState()
    )

    private fun keep(next: Loaded) {
        if (!selectionStarted) {
            // The checkboxes start from the labels the messages have when the picker opens.
            selection.value = LabelSelection.of(next.messageLabels)
            selectionStarted = true
        }
        current = next
    }

    private suspend fun load(mode: PickerMode, tree: FolderTree): Loaded {
        val candidates = PickerCandidates.build(
            tree,
            mode,
            request.messages.map { it.folderPath }.toSet(),
            roleNames
        )
        val usage = recents.usage(request.accountId)
        val search = FolderSearch(
            candidates.folders.map {
                SearchTarget(it.path, it.name, it.shownPath() ?: it.name, usage[it.path] ?: 0)
            }
        )
        return Loaded(
            mode,
            candidates,
            search,
            recents.recent(request.accountId),
            source.labelsOf(request, tree)
        )
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** A tap on [folder]: moves the messages there (folder mode) or toggles its label. */
    fun onDestinationClick(folder: PickerFolder) {
        val now = current ?: return
        if (!folder.enabled || progress.value.first) return
        when {
            now.mode == PickerMode.FOLDERS || folder.moveTarget ->
                finish(PickerOperations.move(request, folder), listOf(folder.path))

            else -> selection.update { it.toggle(folder.value) }
        }
    }

    /** Saves the label changes (label mode). */
    fun apply() {
        val now = current ?: return
        if (now.mode != PickerMode.LABELS || progress.value.first) return
        val changes = selection.value.changes()
        val result = PickerOperations.labels(
            request,
            now.messageLabels,
            changes,
            now.candidates
        )
        val used = changes.add.mapNotNull { now.candidates.byValue(it)?.path }.sorted()
        finish(result, used)
    }

    private fun finish(result: PickerResult?, destinations: List<String>) {
        if (result == null) {
            progress.value = false to PickerFinish.Unchanged
            return
        }
        progress.value = true to null
        viewModelScope.launch {
            actions.commit(result, destinations)
            progress.value = false to PickerFinish.Applied(result)
        }
    }

    /** Creates the view model for a request; the dialog uses it, so callers need not. */
    class Factory @Inject constructor(
        private val source: PickerSource,
        private val actions: MoveLabelActions,
        private val recents: RecentDestinations,
        @IoDispatcher private val io: CoroutineDispatcher
    ) {
        /** [roleNames] are the names of the special folders in the user's language. */
        fun create(request: PickerRequest, roleNames: Map<FolderRole, String> = emptyMap()) =
            MoveLabelPickerViewModel(request, source, actions, recents, io, roleNames)

        /** For `viewModel(key, factory)`: the same view model across recompositions. */
        fun provider(
            request: PickerRequest,
            roleNames: Map<FolderRole, String>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { create(request, roleNames) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
