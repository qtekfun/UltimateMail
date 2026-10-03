// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.account.CreateAccountResult
import com.qtekfun.ultimatemail.domain.oauth.OAuthOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The attempt ended in [failure]: the form is free again and shows why. */
internal fun AddAccountState.failed(failure: AddAccountFailure) =
    copy(progress = AddAccountProgress.IDLE, failure = failure)

/** Shows validation [errors] next to their fields. */
internal fun AddAccountState.withFieldErrors(errors: List<AccountInputError>): AddAccountState {
    val fieldErrors = errors.map { it.toFieldError() }
    return copy(
        progress = AddAccountProgress.IDLE,
        fieldErrors = fieldErrors,
        failure = null,
        // Show the section when a server field is wrong, or the error would be invisible.
        advancedExpanded = advancedExpanded || fieldErrors.any { it.field.isAdvanced }
    )
}

/** The browser sign-in for [request] starts; the screen opens the browser and reports back. */
internal fun AddAccountState.signingIn(request: OAuthRequest) = copy(
    progress = AddAccountProgress.SIGNING_IN,
    fieldErrors = emptyList(),
    failure = null,
    oauthRequest = request
)

internal fun AddAccountState.withMissingClientId() =
    withClientIdError(R.string.error_client_id_missing)

internal fun AddAccountState.withInvalidClientId() = withClientIdError(
    if (oauthType == AuthType.OAUTH_MICROSOFT) {
        R.string.error_client_id_invalid_microsoft
    } else {
        R.string.error_client_id_invalid_google
    }
)

private fun AddAccountState.withClientIdError(message: Int) =
    copy(fieldErrors = listOf(FieldError(FormField.CLIENT_ID, message)), failure = null)

/** The account the provider just signed in: its address from the ID token, OAuth tokens only. */
internal fun AddAccountState.toOAuthInput(
    signedIn: OAuthOutcome.SignedIn,
    authType: AuthType
): AccountInput = toInput().copy(
    email = signedIn.address,
    username = signedIn.address,
    authType = authType,
    credentials = AccountCredentials(oauth = signedIn.tokens)
)

/** Why the browser sign-in did not lead to an account. */
internal fun OAuthOutcome.toFailure(): AddAccountFailure = when (this) {
    OAuthOutcome.Cancelled -> AddAccountFailure.SignInCancelled
    OAuthOutcome.NoAddress -> AddAccountFailure.SignInNoAddress
    else -> AddAccountFailure.SignInFailed
}

/**
 * Tests the connection of [input] and, if it works, creates the account and calls [onCreated];
 * every other outcome becomes state. The steps are the rules of [AccountSetup]; this only maps.
 */
internal suspend fun testAndCreate(
    setup: AccountSetup,
    input: AccountInput,
    state: MutableStateFlow<AddAccountState>,
    onCreated: suspend (Long) -> Unit
) {
    val failure = when (val tested = setup.testConnection(input)) {
        ConnectionTestResult.Success -> null
        is ConnectionTestResult.Failure -> AddAccountFailure.Connection(tested.reason)
        ConnectionTestResult.NotAvailable -> AddAccountFailure.TestUnavailable
    }
    if (failure != null) {
        state.update { it.failed(failure) }
        return
    }
    state.update { it.copy(progress = AddAccountProgress.SAVING) }
    when (val result = setup.create(input)) {
        is CreateAccountResult.Created -> {
            state.update {
                AddAccountState(
                    googleClientId = it.googleClientId,
                    microsoftClientId = it.microsoftClientId
                )
            }
            onCreated(result.accountId)
        }

        is CreateAccountResult.Invalid -> state.update { it.withFieldErrors(result.errors) }

        CreateAccountResult.StorageFailed ->
            state.update { it.failed(AddAccountFailure.StorageFailed) }
    }
}
