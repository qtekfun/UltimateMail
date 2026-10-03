// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import javax.inject.Inject

/** Placeholder binding until the mail client (T07) can implement the connection test. */
class UnavailableConnectionTester @Inject constructor() : AccountConnectionTester {
    override suspend fun test(input: AccountInput): ConnectionTestResult =
        ConnectionTestResult.NotAvailable
}
