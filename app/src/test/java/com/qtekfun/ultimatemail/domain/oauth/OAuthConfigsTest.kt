// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OAuthConfigsTest {
    private val ids = MemoryClientIds()
    private val configs = OAuthConfigs(ids, "com.example.mail", "built-in")

    @Test
    fun `a client ID entered later applies at once`() {
        assertNull(configs.configFor(AuthType.OAUTH_MICROSOFT))

        ids.savedMicrosoft = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"

        assertEquals(
            "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
            configs.configFor(AuthType.OAUTH_MICROSOFT)?.clientId
        )
    }

    @Test
    fun `the saved Google ID wins over the built-in one`() {
        assertEquals("built-in", configs.configFor(AuthType.OAUTH_GOOGLE)?.clientId)

        ids.savedGoogle = "saved"

        assertEquals("saved", configs.configFor(AuthType.OAUTH_GOOGLE)?.clientId)
    }

    @Test
    fun `passwords and unknown hosts have no OAuth`() {
        assertNull(configs.configFor(AuthType.PASSWORD))
        assertNull(configs.authTypeFor("imap.example.test"))
        assertEquals(AuthType.OAUTH_MICROSOFT, configs.authTypeFor("smtp.office365.com"))
    }
}
