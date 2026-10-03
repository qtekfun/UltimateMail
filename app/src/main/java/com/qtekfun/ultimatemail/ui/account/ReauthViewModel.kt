// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.ReauthResult
import com.qtekfun.ultimatemail.domain.account.Reauthenticate
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult
import com.qtekfun.ultimatemail.domain.oauth.OAuthOutcome
import com.qtekfun.ultimatemail.domain.oauth.OAuthSignIn
import com.qtekfun.ultimatemail.domain.oauth.OAuthStart
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

/** One-off results of the re-authentication flow. */
sealed interface ReauthEvent {
    /** The new credentials work and are stored; the screen can close. */
    data object SignedIn : ReauthEvent
}

/**
 * State of the re-authentication screen. The rules (testing, replacing the credentials, address
 * check, clearing the sync state) live in [Reauthenticate]; this class turns the form and the
 * browser result into calls and the outcomes into state.
 */
@HiltViewModel
class ReauthViewModel @Inject constructor(
    private val reauth: Reauthenticate,
    private val oauth: OAuthSignIn,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReauthState())
    val state: StateFlow<ReauthState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ReauthEvent>(Channel.BUFFERED)
    val events: Flow<ReauthEvent> = eventChannel.receiveAsFlow()

    private var accountId: Long? = savedState.get<Long>(ACCOUNT_KEY)
    private var job: Job? = null

    /** Loads the account to sign in again to; a new account starts from a blank form. */
    fun show(id: Long) {
        if (accountId == id && mutableState.value.loaded) return
        accountId = id
        savedState[ACCOUNT_KEY] = id
        job?.cancel()
        mutableState.value = ReauthState()
        viewModelScope.launch {
            val target = reauth.target(id)
            mutableState.value = ReauthState(
                loaded = true,
                target = target,
                clientId = target?.let { oauth.savedClientId(it.authType) }.orEmpty(),
                gmailAppPasswordHint = target != null &&
                    target.authType == AuthType.PASSWORD &&
                    oauth.authTypeFor(target.imapHost) == AuthType.OAUTH_GOOGLE
            )
        }
    }

    fun onPasswordChange(value: String) = mutableState.update {
        it.copy(password = value, failure = null)
    }

    fun onClientIdChange(value: String) = mutableState.update {
        it.copy(clientId = value, clientIdError = null, failure = null)
    }

    /** "Sign in" of a password account: tests the login and, if it works, stores the password. */
    fun submitPassword() {
        val current = mutableState.value
        val id = accountId
        if (current.busy || id == null || current.target == null) return
        if (current.password.isEmpty()) {
            mutableState.update { it.copy(failure = ReauthFailure.MissingPassword) }
            return
        }
        job = viewModelScope.launch {
            mutableState.update { it.copy(progress = ReauthProgress.TESTING, failure = null) }
            finish(reauth.withPassword(id, current.password))
        }
    }

    /** "Sign in with Google/Microsoft": checks the client ID, then asks the screen for the browser. */
    fun onSignInClick() {
        val current = mutableState.value
        val authType = current.oauthType
        val target = current.target
        if (current.busy || authType == null || target == null) return
        when (val start = oauth.start(authType, current.clientId)) {
            is OAuthStart.Ready -> mutableState.update {
                it.copy(
                    progress = ReauthProgress.SIGNING_IN,
                    failure = null,
                    clientIdError = null,
                    oauthRequest = OAuthRequest(authType, start.config, loginHint = target.email)
                )
            }

            OAuthStart.InvalidClientId -> mutableState.update {
                it.copy(
                    clientIdError = if (authType == AuthType.OAUTH_MICROSOFT) {
                        R.string.error_client_id_invalid_microsoft
                    } else {
                        R.string.error_client_id_invalid_google
                    },
                    failure = null
                )
            }

            OAuthStart.MissingClientId -> mutableState.update {
                it.copy(clientIdError = R.string.error_client_id_missing, failure = null)
            }
        }
    }

    /** The screen opened the browser for the pending request. */
    fun onOAuthLaunched() = mutableState.update { it.copy(oauthRequest = null) }

    /**
     * The browser sign-in ended. On success the tokens go to [Reauthenticate], which refuses an
     * address other than the account's. A result that arrives after the user cancelled is ignored.
     */
    fun onOAuthResult(result: OAuthBrowserResult) {
        val id = accountId
        if (mutableState.value.progress != ReauthProgress.SIGNING_IN || id == null) return
        when (val outcome = oauth.outcomeOf(result)) {
            is OAuthOutcome.SignedIn -> job = viewModelScope.launch {
                mutableState.update { it.copy(progress = ReauthProgress.TESTING) }
                finish(reauth.withOAuth(id, outcome.address, outcome.tokens))
            }

            OAuthOutcome.Cancelled -> failed(ReauthFailure.SignInCancelled)

            OAuthOutcome.NoAddress -> failed(ReauthFailure.SignInNoAddress)

            OAuthOutcome.Failed -> failed(ReauthFailure.SignInFailed)
        }
    }

    /** Stops a running connection test or a pending browser sign-in; the form stays as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(progress = ReauthProgress.IDLE, oauthRequest = null) }
    }

    private fun failed(failure: ReauthFailure) = mutableState.update {
        it.copy(progress = ReauthProgress.IDLE, failure = failure)
    }

    private suspend fun finish(result: ReauthResult) {
        val failure = result.toFailure()
        if (failure != null) {
            failed(failure)
            return
        }
        // Do not keep the typed password around once it is stored.
        mutableState.update { it.copy(progress = ReauthProgress.IDLE, password = "") }
        eventChannel.send(ReauthEvent.SignedIn)
    }

    private companion object {
        const val ACCOUNT_KEY = "reauthAccountId"
    }
}
