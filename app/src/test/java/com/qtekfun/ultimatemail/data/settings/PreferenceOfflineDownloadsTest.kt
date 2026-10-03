// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferenceOfflineDownloadsTest {
    private val store = FakePreferenceStore()
    private val downloads = PreferenceOfflineDownloads(store)

    @Test
    fun `it is on until the user turns it off, per account`() {
        assertEquals(true, downloads.isEnabled(1))

        downloads.setEnabled(1, false)

        assertEquals(false, downloads.isEnabled(1))
        assertEquals(true, downloads.isEnabled(2))
    }

    @Test
    fun `observing follows changes of that account only`() = runTest {
        downloads.observe(1).test {
            assertEquals(true, awaitItem())

            downloads.setEnabled(2, false)
            downloads.setEnabled(1, false)

            assertEquals(false, awaitItem())
            downloads.setEnabled(1, true)
            assertEquals(true, awaitItem())
        }
    }
}
