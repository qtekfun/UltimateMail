// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.AuthType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountListingTest {
    @Test
    fun `lists accounts in the order they were added and follows new ones`() = runTest {
        val db = inMemoryDatabase()
        try {
            val listing = AccountListing(db)
            val first = db.accountDao().insert(account("a@example.test"))

            listing.observe().test {
                assertEquals(
                    listOf(AccountSummary(first, "a@example.test", "Ana", AuthType.PASSWORD)),
                    awaitItem()
                )
                val second = db.accountDao().insert(account("b@example.test"))
                assertEquals(listOf(first, second), awaitItem().map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            db.close()
        }
    }
}
