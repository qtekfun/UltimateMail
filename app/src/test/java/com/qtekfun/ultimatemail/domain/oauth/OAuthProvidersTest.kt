// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OAuthProvidersTest {
    private val providers = OAuthProviders("client.apps.googleusercontent.com", "com.example.mail")

    @Test
    fun `gmail hosts get the Google configuration with the redirect of the app`() {
        listOf("imap.gmail.com", "SMTP.GMAIL.COM", " imap.googlemail.com ").forEach { host ->
            val lookup = providers.forHost(host)

            assertTrue(lookup is OAuthLookup.Available, host)
            val config = (lookup as OAuthLookup.Available).config
            assertEquals("client.apps.googleusercontent.com", config.clientId)
            assertEquals("com.example.mail:/oauth2redirect", config.redirectUri)
            assertTrue("https://mail.google.com/" in config.scopes)
            assertTrue("email" in config.scopes)
        }
    }

    @Test
    fun `a build without a client ID reports Google as not configured`() {
        val lookup = OAuthProviders("  ", "com.example.mail").forHost("imap.gmail.com")

        assertEquals(OAuthLookup.NotConfigured, lookup)
    }

    @Test
    fun `other hosts sign in with a password`() {
        assertEquals(OAuthLookup.NotOAuth, providers.forHost("imap.example.test"))
    }

    @Test
    fun `the auth type follows the host`() {
        assertEquals(AuthType.OAUTH_GOOGLE, providers.authTypeFor("smtp.gmail.com"))
        assertNull(providers.authTypeFor("imap.example.test"))
    }
}
