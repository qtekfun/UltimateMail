// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.backup.BackupError
import com.qtekfun.ultimatemail.domain.backup.BackupImporter
import com.qtekfun.ultimatemail.domain.backup.BackupPreview
import com.qtekfun.ultimatemail.domain.backup.ImportSummary
import com.qtekfun.ultimatemail.domain.backup.OpenResult
import com.qtekfun.ultimatemail.domain.backup.PreviewStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the import is. */
enum class ImportStage { PICK, PASSPHRASE, OPENING, PREVIEW, IMPORTING, DONE }

/** What the import screens show. It never holds the passphrase. */
data class ImportState(
    val stage: ImportStage = ImportStage.PICK,
    /** Why the last attempt to open the file failed; shown on the passphrase step. */
    val error: BackupError? = null,
    val preview: BackupPreview? = null,
    /** Positions (in the file) of the accounts the user ticked. */
    val selected: Set<Int> = emptySet(),
    val importSettings: Boolean = false,
    val summary: ImportSummary? = null
)

/**
 * The import of accounts (RF-12): pick the file, type the passphrase, tick the accounts in the
 * preview, see the result. The passphrase goes straight to the importer, which wipes it.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(private val importer: BackupImporter) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    private var chosenUri: String? = null

    /** The user picked a file ([uri]), or closed the picker (null). */
    fun onFileChosen(uri: String?) {
        if (uri == null) return
        chosenUri = uri
        mutableState.update { it.copy(stage = ImportStage.PASSPHRASE, error = null) }
    }

    /** Tries to open the chosen file with [passphrase], which is wiped by the importer. */
    fun open(passphrase: CharArray) {
        val uri = chosenUri
        if (uri == null) {
            passphrase.fill('\u0000')
            return
        }
        mutableState.update { it.copy(stage = ImportStage.OPENING, error = null) }
        viewModelScope.launch {
            val result = importer.open(uri, passphrase)
            mutableState.update {
                when (result) {
                    is OpenResult.Opened -> it.copy(
                        stage = ImportStage.PREVIEW,
                        preview = result.preview,
                        selected = result.preview.entries
                            .filter { entry -> entry.status == PreviewStatus.IMPORTABLE }
                            .map { entry -> entry.index }
                            .toSet(),
                        importSettings = false
                    )

                    is OpenResult.Failed ->
                        it.copy(stage = ImportStage.PASSPHRASE, error = result.error)
                }
            }
        }
    }

    fun toggle(index: Int) = mutableState.update { state ->
        val importable = state.preview?.entries?.firstOrNull { it.index == index }
            ?.status == PreviewStatus.IMPORTABLE
        when {
            !importable -> state
            index in state.selected -> state.copy(selected = state.selected - index)
            else -> state.copy(selected = state.selected + index)
        }
    }

    fun onImportSettingsChange(enabled: Boolean) =
        mutableState.update { it.copy(importSettings = enabled) }

    /** Imports what is ticked. */
    fun import() {
        val current = mutableState.value
        val preview = current.preview ?: return
        if (current.stage != ImportStage.PREVIEW) return
        mutableState.update { it.copy(stage = ImportStage.IMPORTING) }
        viewModelScope.launch {
            val summary = importer.import(preview, current.selected, current.importSettings)
            mutableState.update {
                it.copy(stage = ImportStage.DONE, summary = summary, preview = null)
            }
        }
    }

    /** Back to the first step, forgetting the file and what was read from it. */
    fun reset() {
        chosenUri = null
        mutableState.value = ImportState()
    }
}
