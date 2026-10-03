// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.account.CreateAccountResult
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-off results of the add-account flow. */
sealed interface AddAccountEvent {
    data class Created(val accountId: Long) : AddAccountEvent
}

/**
 * State of the add-account screen. The rules (validation, connection test, creation) live in
 * [AccountSetup]; this class only turns the form into input and the outcomes into state.
 */
@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val setup: AccountSetup,
    private val scheduler: SyncScheduler
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddAccountState())
    val state: StateFlow<AddAccountState> = mutableState.asStateFlow()

    private val eventChannel = Channel<AddAccountEvent>(Channel.BUFFERED)
    val events: Flow<AddAccountEvent> = eventChannel.receiveAsFlow()

    private var job: Job? = null

    fun onTextChange(input: FormInput, value: String) = mutableState.update { current ->
        val changed = current.withText(input, value).edited()
        if (input == FormInput.EMAIL && !current.serversEdited) {
            changed.withSuggestion(setup.detectServers(value))
        } else {
            changed
        }
    }

    fun onSecurityChange(server: AccountInputError.Server, value: ConnectionSecurity) =
        mutableState.update { it.withSecurity(server, value).edited() }

    fun onAuthTypeChange(value: AuthType) = mutableState.update {
        if (value in it.availableAuthTypes) it.copy(authType = value).edited() else it
    }

    fun onAdvancedToggle() = mutableState.update {
        it.copy(advancedExpanded = !it.advancedExpanded)
    }

    /** Validates the form, tests the connection and, if it works, creates the account. */
    fun submit() {
        if (mutableState.value.busy) return
        val input = mutableState.value.toInput()
        val errors = setup.validate(input)
        if (errors.isNotEmpty()) {
            showFieldErrors(errors)
            return
        }
        job = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    progress = AddAccountProgress.TESTING,
                    fieldErrors = emptyList(),
                    failure = null
                )
            }
            when (val result = setup.testConnection(input)) {
                ConnectionTestResult.Success -> create()
                is ConnectionTestResult.Failure -> fail(AddAccountFailure.Connection(result.reason))
                ConnectionTestResult.NotAvailable -> fail(AddAccountFailure.TestUnavailable)
            }
        }
    }

    /** Stops a running connection test; the form stays as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(progress = AddAccountProgress.IDLE) }
    }

    /** Back to an empty form, for the next time the screen opens. */
    fun reset() {
        cancel()
        mutableState.value = AddAccountState()
    }

    private suspend fun create() {
        mutableState.update { it.copy(progress = AddAccountProgress.SAVING) }
        when (val result = setup.create(mutableState.value.toInput())) {
            is CreateAccountResult.Created -> {
                mutableState.value = AddAccountState()
                // First sync right away, so the folders appear without waiting for the periodic one.
                scheduler.requestSync(result.accountId, userInitiated = true)
                eventChannel.send(AddAccountEvent.Created(result.accountId))
            }

            is CreateAccountResult.Invalid -> showFieldErrors(result.errors)

            CreateAccountResult.StorageFailed -> fail(AddAccountFailure.StorageFailed)
        }
    }

    private fun fail(failure: AddAccountFailure) = mutableState.update {
        it.copy(progress = AddAccountProgress.IDLE, failure = failure)
    }

    private fun showFieldErrors(errors: List<AccountInputError>) {
        val fieldErrors = errors.map { it.toFieldError() }
        mutableState.update {
            it.copy(
                progress = AddAccountProgress.IDLE,
                fieldErrors = fieldErrors,
                failure = null,
                // Show the section when a server field is wrong, or the error would be invisible.
                advancedExpanded =
                    it.advancedExpanded || fieldErrors.any { e -> e.field.isAdvanced }
            )
        }
    }
}
