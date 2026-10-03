// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType

/** Gets new OAuth2 tokens from the provider. The AppAuth implementation arrives with T02. */
interface OAuthTokenSource {
    suspend fun refresh(authType: AuthType, refreshToken: String): OAuthRefreshResult
}

sealed interface OAuthRefreshResult {
    data class Refreshed(val tokens: OAuthTokens) : OAuthRefreshResult

    /** The user revoked access: ask them to sign in again, keeping local data (RF-01). */
    data object Revoked : OAuthRefreshResult

    /** Network or server trouble; try again later. */
    data object TemporaryFailure : OAuthRefreshResult

    /** No OAuth implementation is installed in this build. */
    data object Unavailable : OAuthRefreshResult
}
