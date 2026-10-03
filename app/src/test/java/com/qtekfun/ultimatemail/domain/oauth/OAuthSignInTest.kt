// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OAuthSignInTest {
    private val guid = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"
    private val google = "123-abc.apps.googleusercontent.com"
    private val ids = MemoryClientIds()
    private val signIn = OAuthSignIn(ids, OAuthConfigs(ids, "com.example.mail", "built-in-google"))

    @Test
    fun `the auth type follows the incoming host`() {
        assertEquals(AuthType.OAUTH_GOOGLE, signIn.authTypeFor("imap.gmail.com"))
        assertEquals(AuthType.OAUTH_MICROSOFT, signIn.authTypeFor("outlook.office365.com"))
        assertNull(signIn.authTypeFor("imap.example.test"))
    }

    @Test
    fun `a valid Microsoft client ID is saved and gives the Microsoft configuration`() {
        val start = signIn.start(AuthType.OAUTH_MICROSOFT, "  $guid ")

        assertEquals(guid, ids.microsoft())
        assertEquals(guid, signIn.savedClientId(AuthType.OAUTH_MICROSOFT))
        val config = (start as OAuthStart.Ready).config
        assertEquals(guid, config.clientId)
        assertTrue(config.tokenEndpoint.contains("microsoftonline"))
    }

    @Test
    fun `a valid Google client ID is saved and gives the Google configuration`() {
        val start = signIn.start(AuthType.OAUTH_GOOGLE, google)

        assertEquals(google, ids.google())
        assertEquals(google, (start as OAuthStart.Ready).config.clientId)
    }

    @Test
    fun `a client ID of the wrong provider is rejected and not saved`() {
        assertEquals(OAuthStart.InvalidClientId, signIn.start(AuthType.OAUTH_MICROSOFT, google))
        assertEquals(OAuthStart.InvalidClientId, signIn.start(AuthType.OAUTH_GOOGLE, guid))
        assertNull(ids.microsoft())
        assertNull(ids.google())
    }

    @Test
    fun `without a client ID Microsoft cannot start`() {
        assertEquals(OAuthStart.MissingClientId, signIn.start(AuthType.OAUTH_MICROSOFT, "  "))
    }

    @Test
    fun `an empty Google ID falls back to the one built into the app`() {
        val start = signIn.start(AuthType.OAUTH_GOOGLE, "")

        assertEquals("built-in-google", (start as OAuthStart.Ready).config.clientId)
    }

    private fun idToken(claims: String): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.toByteArray())
        return "h.$payload.s"
    }

    @Test
    fun `a finished sign-in names the account from the ID token`() {
        val tokens = OAuthTokens("access", "refresh", null)

        val outcome = signIn.outcomeOf(
            OAuthBrowserResult.Success(tokens, idToken("""{"preferred_username":"a@b.test"}"""))
        )

        assertEquals(OAuthOutcome.SignedIn("a@b.test", tokens), outcome)
    }

    @Test
    fun `a sign-in whose token has no address, or no token, has no account`() {
        val tokens = OAuthTokens("access", null, null)

        assertEquals(
            OAuthOutcome.NoAddress,
            signIn.outcomeOf(OAuthBrowserResult.Success(tokens, idToken("""{"sub":"1"}""")))
        )
        assertEquals(
            OAuthOutcome.NoAddress,
            signIn.outcomeOf(OAuthBrowserResult.Success(tokens, null))
        )
    }

    @Test
    fun `cancelled and failed sign-ins keep their meaning`() {
        assertEquals(OAuthOutcome.Cancelled, signIn.outcomeOf(OAuthBrowserResult.Cancelled))
        assertEquals(OAuthOutcome.Failed, signIn.outcomeOf(OAuthBrowserResult.Failed))
    }

    @Test
    fun `passwords have no sign-in`() {
        assertEquals(OAuthStart.MissingClientId, signIn.start(AuthType.PASSWORD, guid))
        assertEquals("", signIn.savedClientId(AuthType.PASSWORD))
        assertEquals("", signIn.savedClientId(AuthType.OAUTH_GOOGLE))
    }
}
