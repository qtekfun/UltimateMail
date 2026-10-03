// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.AuthType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PickerModeDetectorTest {
    private fun detect(
        authType: AuthType = AuthType.PASSWORD,
        host: String = "imap.example.test",
        labels: Boolean = false
    ) = PickerModeDetector.detect(authType, host, labels)

    @Test
    fun `an ordinary imap account picks folders`() {
        assertEquals(PickerMode.FOLDERS, detect())
        assertEquals(PickerMode.FOLDERS, detect(AuthType.OAUTH_MICROSOFT, "outlook.office365.com"))
    }

    @Test
    fun `google hosts pick labels whatever the sign in`() {
        listOf("imap.gmail.com", "imap.googlemail.com", "IMAP.GMAIL.COM", " imap.gmail.com ")
            .forEach { assertEquals(PickerMode.LABELS, detect(host = it), it) }
    }

    @Test
    fun `signing in with google picks labels`() {
        assertEquals(PickerMode.LABELS, detect(authType = AuthType.OAUTH_GOOGLE))
    }

    @Test
    fun `label folders on the server pick labels for a custom host`() {
        assertEquals(PickerMode.LABELS, detect(host = "mail.my-domain.test", labels = true))
    }

    @Test
    fun `a host that only looks like google does not`() {
        assertEquals(PickerMode.FOLDERS, detect(host = "imap.gmail.com.evil.test"))
        assertEquals(PickerMode.FOLDERS, detect(host = "gmail.com"))
    }
}
