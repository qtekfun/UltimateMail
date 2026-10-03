// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.annotation.StringRes
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ReauthResult
import com.qtekfun.ultimatemail.domain.account.ReauthTarget

/** Where the re-authentication flow is: waiting for input, or busy. */
enum class ReauthProgress { IDLE, SIGNING_IN, TESTING }

/** Why the last attempt to sign in again did not work. */
sealed interface ReauthFailure {
    data class Connection(val reason: ConnectionFailure) : ReauthFailure

    /** This build has no connection tester, so nothing could be verified. */
    data object TestUnavailable : ReauthFailure

    data object StorageFailed : ReauthFailure

    data object MissingPassword : ReauthFailure

    /** The user closed the browser or the provider refused the sign-in. */
    data object SignInCancelled : ReauthFailure

    /** The browser part worked but no tokens came back. */
    data object SignInFailed : ReauthFailure

    /** Signed in, but the ID token does not say which address the account has. */
    data object SignInNoAddress : ReauthFailure

    /** The provider signed in another address than the one of this account. */
    data object AddressMismatch : ReauthFailure

    /** The account is gone, or does not use the sign-in method that was tried. */
    data object NotApplicable : ReauthFailure
}

/** Everything the re-authentication screen shows. [loaded] false: Room has not answered yet. */
data class ReauthState(
    val loaded: Boolean = false,
    /** The account to sign in again to; null once loaded means it does not exist (any more). */
    val target: ReauthTarget? = null,
    val password: String = "",
    val clientId: String = "",
    @StringRes val clientIdError: Int? = null,
    /** Set while the screen should open the browser; the screen reports it with onOAuthLaunched. */
    val oauthRequest: OAuthRequest? = null,
    /** True for password accounts at Gmail, which need an app password. */
    val gmailAppPasswordHint: Boolean = false,
    val progress: ReauthProgress = ReauthProgress.IDLE,
    val failure: ReauthFailure? = null
) {
    val busy: Boolean get() = progress != ReauthProgress.IDLE

    /** The provider of an OAuth account; null for password accounts and while loading. */
    val oauthType: AuthType?
        get() = target?.authType?.takeIf { it != AuthType.PASSWORD }
}

internal fun ReauthResult.toFailure(): ReauthFailure? = when (this) {
    ReauthResult.Success -> null
    is ReauthResult.Failure -> ReauthFailure.Connection(reason)
    ReauthResult.TestUnavailable -> ReauthFailure.TestUnavailable
    ReauthResult.StorageFailed -> ReauthFailure.StorageFailed
    ReauthResult.AddressMismatch -> ReauthFailure.AddressMismatch
    ReauthResult.Invalid, ReauthResult.NoAccount -> ReauthFailure.NotApplicable
}

/** Actionable text for a failed attempt; connection problems read the same as in add-account. */
@StringRes
fun ReauthFailure.toMessage(): Int = when (this) {
    is ReauthFailure.Connection -> reason.toMessage()
    ReauthFailure.TestUnavailable -> R.string.reauth_error_test_unavailable
    ReauthFailure.StorageFailed -> R.string.reauth_error_storage_failed
    ReauthFailure.MissingPassword -> R.string.error_password_missing
    ReauthFailure.SignInCancelled -> R.string.error_sign_in_cancelled
    ReauthFailure.SignInFailed -> R.string.error_sign_in_failed
    ReauthFailure.SignInNoAddress -> R.string.error_sign_in_no_address
    ReauthFailure.AddressMismatch -> R.string.reauth_error_address_mismatch
    ReauthFailure.NotApplicable -> R.string.reauth_error_not_applicable
}
