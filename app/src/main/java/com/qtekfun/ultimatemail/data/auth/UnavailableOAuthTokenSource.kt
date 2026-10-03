// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import javax.inject.Inject

/** Placeholder binding until T02 adds the AppAuth implementation. */
class UnavailableOAuthTokenSource @Inject constructor() : OAuthTokenSource {
    override suspend fun refresh(authType: AuthType, refreshToken: String): OAuthRefreshResult =
        OAuthRefreshResult.Unavailable
}
