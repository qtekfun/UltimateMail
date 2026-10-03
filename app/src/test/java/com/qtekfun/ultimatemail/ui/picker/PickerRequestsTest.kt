// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import app.cash.turbine.test
import com.qtekfun.ultimatemail.domain.inbox.MessageHandle
import com.qtekfun.ultimatemail.domain.picker.MessageRef
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.ui.inbox.DialogMovePickerLauncher
import com.qtekfun.ultimatemail.ui.inbox.MovePickerRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PickerRequestsTest {
    private val requests = PickerRequests()

    @Test
    fun `nothing is asked for at first, a request stays until it is closed`() = runTest {
        requests.request.test {
            assertNull(awaitItem())
            val asked = PickerRequest(1, listOf(MessageRef("INBOX", 5)))
            requests.open(asked)
            assertEquals(asked, awaitItem())
            requests.close()
            assertNull(awaitItem())
        }
    }

    @Test
    fun `the launcher turns the picked messages into a picker request`() {
        DialogMovePickerLauncher(requests).open(
            MovePickerRequest(
                accountId = 3,
                messages = listOf(
                    MessageHandle(id = 10, accountId = 3, folderPath = "INBOX", uid = 7),
                    MessageHandle(id = 11, accountId = 3, folderPath = "INBOX", uid = 8)
                )
            )
        )

        assertEquals(
            PickerRequest(3, listOf(MessageRef("INBOX", 7), MessageRef("INBOX", 8))),
            requests.request.value
        )
    }
}
