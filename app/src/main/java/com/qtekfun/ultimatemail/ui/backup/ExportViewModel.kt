// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.backup.BackupExporter
import com.qtekfun.ultimatemail.domain.backup.BackupPassphrase
import com.qtekfun.ultimatemail.domain.backup.ExportResult
import com.qtekfun.ultimatemail.domain.backup.PassphraseIssue
import com.qtekfun.ultimatemail.domain.backup.PassphraseStrength
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the export is. */
enum class ExportStage { FORM, CHOOSING_LOCATION, WRITING, DONE, FAILED }

/** What the export screen shows. It never holds the passphrase. */
data class ExportState(
    val stage: ExportStage = ExportStage.FORM,
    val includeCredentials: Boolean = false,
    val strength: PassphraseStrength = PassphraseStrength.NONE,
    val issue: PassphraseIssue? = null,
    val exportedAccounts: Int = 0,
    val credentialsIncluded: Boolean = false
)

/**
 * The export of accounts (RF-12). The passphrase is kept as a [CharArray] only between the
 * moment the form is accepted and the moment the user picked where to save; it is wiped as
 * soon as it is used, when the user backs out, and when this view model goes away. It is never
 * put in saved state and never logged.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(private val exporter: BackupExporter) : ViewModel() {
    private val mutableState = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    private var held: CharArray? = null

    /** Updates the strength shown while typing; [passphrase] is wiped. */
    fun onPassphraseChange(passphrase: CharArray) {
        val strength = BackupPassphrase.strength(passphrase)
        passphrase.fill('\u0000')
        mutableState.update { it.copy(strength = strength, issue = null) }
    }

    fun onIncludeCredentialsChange(include: Boolean) =
        mutableState.update { it.copy(includeCredentials = include, issue = null) }

    /** Checks the form; when it is fine the screen is asked to open the file creator. */
    fun submit(passphrase: CharArray, confirmation: CharArray) {
        val include = mutableState.value.includeCredentials
        val issue = BackupPassphrase.check(passphrase, confirmation, include)
        confirmation.fill('\u0000')
        if (issue != null) {
            passphrase.fill('\u0000')
            mutableState.update { it.copy(issue = issue) }
        } else {
            wipe()
            held = passphrase
            mutableState.update { it.copy(issue = null, stage = ExportStage.CHOOSING_LOCATION) }
        }
    }

    /** The user picked where to save ([uri]), or closed the file creator (null). */
    fun onLocationChosen(uri: String?) {
        val passphrase = held
        if (uri == null || passphrase == null) {
            wipe()
            mutableState.update { it.copy(stage = ExportStage.FORM) }
            return
        }
        held = null
        val include = mutableState.value.includeCredentials
        mutableState.update { it.copy(stage = ExportStage.WRITING) }
        viewModelScope.launch {
            // The exporter wipes the passphrase when it is done.
            val result = exporter.export(uri, passphrase, include)
            mutableState.update {
                when (result) {
                    is ExportResult.Done -> it.copy(
                        stage = ExportStage.DONE,
                        exportedAccounts = result.accounts,
                        credentialsIncluded = result.credentialsIncluded
                    )

                    is ExportResult.BadPassphrase ->
                        it.copy(stage = ExportStage.FORM, issue = result.issue)

                    ExportResult.WriteFailed -> it.copy(stage = ExportStage.FAILED)
                }
            }
        }
    }

    /** Back to an empty form (leaving the screen, or "Try again"). */
    fun reset() {
        wipe()
        mutableState.value = ExportState()
    }

    override fun onCleared() = wipe()

    private fun wipe() {
        held?.fill('\u0000')
        held = null
    }
}
