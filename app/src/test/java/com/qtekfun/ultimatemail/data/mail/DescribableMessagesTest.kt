// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.StoreClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DescribableMessagesTest {
    @Test
    fun `a message the server cannot describe is left out and the others stay`() {
        val result = listOf(1, 2, 3, 4).mapDescribable {
            if (it == 2) throw MessagingException("envelope") else it * 10
        }

        assertEquals(listOf(10, 30, 40), result)
    }

    @Test
    fun `a closed folder is the connection going away and is not hidden`() {
        assertThrows(FolderClosedException::class.java) {
            listOf(1).mapDescribable<Int, Int> { throw FolderClosedException(null) }
        }
    }

    @Test
    fun `a closed store is not hidden either`() {
        assertThrows(StoreClosedException::class.java) {
            listOf(1).mapDescribable<Int, Int> { throw StoreClosedException(null) }
        }
    }

    @Test
    fun `an empty batch maps to nothing`() {
        assertEquals(emptyList<Int>(), emptyList<Int>().mapDescribable { it })
    }
}
