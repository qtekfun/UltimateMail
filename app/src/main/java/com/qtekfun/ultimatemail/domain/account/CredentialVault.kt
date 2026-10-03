// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

/** Encrypted storage of account secrets, keyed by account id (RF-01). */
interface CredentialVault {
    suspend fun save(accountId: Long, credentials: AccountCredentials)

    /** The stored secrets, or null when there are none or they can no longer be decrypted. */
    suspend fun load(accountId: Long): AccountCredentials?

    suspend fun delete(accountId: Long)
}
