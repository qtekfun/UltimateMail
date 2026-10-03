// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.picker.PickerFolder
import com.qtekfun.ultimatemail.domain.picker.PickerListItem
import com.qtekfun.ultimatemail.domain.picker.PickerMode
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.domain.picker.PickerResult
import com.qtekfun.ultimatemail.domain.picker.SectionKind
import com.qtekfun.ultimatemail.ui.drawer.displayName
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID

/**
 * The "Move to" / "Label as" picker (RF-06): a large bottom sheet with a search field that is
 * focused with the keyboard up, live filtering that ignores case and accents, the recent
 * destinations first and then the folder tree. Which of the two it is follows the account: Gmail
 * accounts get checkboxes (labels, several at once, partially set ones shown as indeterminate) and
 * an Apply button; other accounts get a list where a tap moves the messages at once.
 *
 * Show it while the caller holds [request] in its state, and drop it in [onDismiss]:
 * ```
 * if (picking != null) {
 *     MoveLabelDialog(
 *         request = picking,
 *         onDismiss = { picking = null },
 *         onApplied = { result -> offerUndo(result.message(resources), result) }
 *     )
 * }
 * ```
 * [onApplied] is called once, after the operations were queued and the local state updated, and
 * before [onDismiss]; it is not called when nothing changed. To undo, call
 * `MoveLabelActions.undo(result)`. For quick actions without the picker use
 * `MoveLabelActions.archive(request)`.
 *
 * The view model is kept per opening (it survives rotation), so a dialog shown a second time
 * starts afresh.
 */
@Composable
fun MoveLabelDialog(
    request: PickerRequest,
    onDismiss: () -> Unit,
    onApplied: (PickerResult) -> Unit
) {
    val context = LocalContext.current
    val factory = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MoveLabelPickerEntryPoint::class.java
        ).factory()
    }
    val names = FolderRole.entries.mapNotNull { role ->
        role.displayName()?.let { role to it }
    }.toMap()
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel: MoveLabelPickerViewModel =
        viewModel(key = key, factory = factory.provider(request, names))
    MoveLabelDialog(viewModel, onDismiss, onApplied)
}

/** The same with a view model of the caller's making, for tests and previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveLabelDialog(
    viewModel: MoveLabelPickerViewModel,
    onDismiss: () -> Unit,
    onApplied: (PickerResult) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) {
        when (val finished = state.finished) {
            is PickerFinish.Applied -> {
                onApplied(finished.result)
                onDismiss()
            }

            PickerFinish.Unchanged -> onDismiss()

            null -> Unit
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        PickerContent(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onClick = viewModel::onDestinationClick,
            onApply = viewModel::apply,
            onClose = onDismiss
        )
    }
}

@Composable
private fun PickerContent(
    state: PickerState,
    onQueryChange: (String) -> Unit,
    onClick: (PickerFolder) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    val labels = state.mode == PickerMode.LABELS
    // The sheet fills the screen below the status bar; the keyboard takes its share of it.
    Column(Modifier.fillMaxHeight().imePadding()) {
        PickerHeader(labels, state.canApply, onApply, onClose)
        SearchField(state.query, labels, onQueryChange)
        Box(Modifier.fillMaxSize()) {
            when {
                state.unavailable -> CenteredMessage(stringResource(R.string.picker_unavailable))

                state.loading -> Loading()

                state.listing.noMatches ->
                    CenteredMessage(stringResource(R.string.picker_no_matches, state.query.trim()))

                else -> PickerList(state, labels, onClick)
            }
        }
    }
}

@Composable
private fun PickerHeader(
    labels: Boolean,
    canApply: Boolean,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                if (labels) R.string.picker_title_labels else R.string.picker_title_move
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        if (labels) {
            TextButton(onClick = onApply, enabled = canApply) {
                Text(stringResource(R.string.picker_apply))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.picker_close))
        }
    }
}

@Composable
private fun SearchField(query: String, labels: Boolean, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(
                    if (labels) {
                        R.string.picker_search_hint_labels
                    } else {
                        R.string.picker_search_hint_folders
                    }
                )
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.picker_search_clear)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus)
    )
}

@Composable
private fun PickerList(state: PickerState, labels: Boolean, onClick: (PickerFolder) -> Unit) {
    val searching = state.query.isNotBlank()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.listing.items, key = { it.key }) { item ->
            when (item) {
                is PickerListItem.Section -> SectionHeader(item.kind, labels)

                is PickerListItem.Group -> GroupRow(item, indented = !searching)

                is PickerListItem.Entry -> EntryRow(
                    item,
                    labels,
                    flat = searching,
                    onClick = onClick
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(kind: SectionKind, labels: Boolean) {
    val text = when {
        kind == SectionKind.RECENT -> R.string.picker_section_recent
        labels -> R.string.picker_section_all_labels
        else -> R.string.picker_section_all_folders
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 4.dp
        )
    )
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.picker_loading),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = MAX_MESSAGE_LINES,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val MAX_MESSAGE_LINES = 4
