// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult
import com.qtekfun.ultimatemail.domain.oauth.OAuthOutcome
import com.qtekfun.ultimatemail.domain.oauth.OAuthSignIn
import com.qtekfun.ultimatemail.domain.oauth.OAuthStart
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
    private val scheduler: SyncScheduler,
    private val oauth: OAuthSignIn
) : ViewModel() {
    private val mutableState = MutableStateFlow(blankState)
    val state: StateFlow<AddAccountState> = mutableState.asStateFlow()

    private val eventChannel = Channel<AddAccountEvent>(Channel.BUFFERED)
    val events: Flow<AddAccountEvent> = eventChannel.receiveAsFlow()

    private var job: Job? = null

    /** The provider of the browser sign-in in progress; survives screen rotation. */
    private var signingInAs: AuthType? = null

    private val onCreated: suspend (Long) -> Unit = { accountId ->
        // First sync right away, so the folders appear without waiting for the periodic one.
        scheduler.requestSync(accountId, userInitiated = true)
        eventChannel.send(AddAccountEvent.Created(accountId))
    }

    fun onTextChange(input: FormInput, value: String) = mutableState.update { current ->
        val changed = current.withText(input, value).edited()
        val suggested = if (input == FormInput.EMAIL && !current.serversEdited) {
            changed.withSuggestion(setup.detectServers(value))
        } else {
            changed
        }
        suggested.copy(oauthType = oauth.authTypeFor(suggested.imapHost))
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
            mutableState.update { it.withFieldErrors(errors) }
            return
        }
        job = viewModelScope.launch {
            mutableState.update { it.copy(progress = AddAccountProgress.TESTING).edited() }
            testAndCreate(setup, input, mutableState, onCreated)
        }
    }

    /** "Sign in with Google/Microsoft": checks the client ID, then asks the screen for the browser. */
    fun onSignInClick() {
        val current = mutableState.value
        val authType = current.oauthType
        if (current.busy || authType == null) return
        when (val start = oauth.start(authType, current.clientIdText)) {
            is OAuthStart.Ready -> {
                signingInAs = authType
                mutableState.update { it.signingIn(OAuthRequest(authType, start.config)) }
            }

            OAuthStart.InvalidClientId -> mutableState.update { it.withInvalidClientId() }

            OAuthStart.MissingClientId -> mutableState.update { it.withMissingClientId() }
        }
    }

    /** The screen opened the browser for the pending request. */
    fun onOAuthLaunched() = mutableState.update { it.copy(oauthRequest = null) }

    /**
     * The browser sign-in ended. On success the account is the one named by the ID token; it is
     * tested with an XOAUTH2 login and created like a password account. A result that arrives
     * after the user cancelled is ignored.
     */
    fun onOAuthResult(result: OAuthBrowserResult) {
        val authType = signingInAs
        if (mutableState.value.progress != AddAccountProgress.SIGNING_IN || authType == null) return
        when (val outcome = oauth.outcomeOf(result)) {
            is OAuthOutcome.SignedIn -> {
                val input = mutableState.value.toOAuthInput(outcome, authType)
                val errors = setup.validate(input)
                if (errors.isEmpty()) {
                    job = viewModelScope.launch {
                        mutableState.update {
                            it.copy(progress = AddAccountProgress.TESTING, email = input.email)
                                .edited()
                        }
                        testAndCreate(setup, input, mutableState, onCreated)
                    }
                } else {
                    mutableState.update { it.withFieldErrors(errors) }
                }
            }

            else -> mutableState.update { it.failed(outcome.toFailure()) }
        }
    }

    /** Stops a running connection test; the form stays as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(progress = AddAccountProgress.IDLE, oauthRequest = null) }
    }

    /** Back to an empty form, for the next time the screen opens. */
    fun reset() {
        cancel()
        mutableState.value = blankState
    }

    /** An empty form, with the client IDs the user saved earlier. */
    private val blankState: AddAccountState
        get() = AddAccountState(
            googleClientId = oauth.savedClientId(AuthType.OAUTH_GOOGLE),
            microsoftClientId = oauth.savedClientId(AuthType.OAUTH_MICROSOFT)
        )
}
