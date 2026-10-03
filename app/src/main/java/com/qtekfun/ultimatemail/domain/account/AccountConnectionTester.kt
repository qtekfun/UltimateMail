// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

/** Checks that the servers of an account accept the given settings and credentials. */
interface AccountConnectionTester {
    suspend fun test(input: AccountInput): ConnectionTestResult
}

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult

    data class Failure(val reason: ConnectionFailure) : ConnectionTestResult

    /** No tester is installed in this build, so nothing was verified. */
    data object NotAvailable : ConnectionTestResult
}

enum class ConnectionFailure {
    HOST_UNREACHABLE,
    TLS_ERROR,
    AUTHENTICATION_FAILED,
    TIMEOUT,
    UNKNOWN
}
