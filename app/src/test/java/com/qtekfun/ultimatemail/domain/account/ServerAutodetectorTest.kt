// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ServerAutodetectorTest {
    private val detector = ServerAutodetector()

    @ParameterizedTest
    @ValueSource(
        strings = ["ana@gmail.com", "ana@googlemail.com", "Ana@GMAIL.COM", " ana@gmail.com "]
    )
    fun `google domains use gmail servers with OAuth`(email: String) {
        val suggestion = detector.detect(email)!!

        assertEquals(AuthType.OAUTH_GOOGLE, suggestion.authType)
        assertEquals(ServerEndpoint("imap.gmail.com", 993, ConnectionSecurity.TLS), suggestion.imap)
        assertEquals(
            ServerEndpoint("smtp.gmail.com", 587, ConnectionSecurity.STARTTLS),
            suggestion.smtp
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["a@outlook.com", "a@hotmail.com", "a@live.com", "a@office365.com"])
    fun `microsoft domains use office365 servers with OAuth`(email: String) {
        val suggestion = detector.detect(email)!!

        assertEquals(AuthType.OAUTH_MICROSOFT, suggestion.authType)
        assertEquals(
            ServerEndpoint("outlook.office365.com", 993, ConnectionSecurity.TLS),
            suggestion.imap
        )
        assertEquals(
            ServerEndpoint("smtp.office365.com", 587, ConnectionSecurity.STARTTLS),
            suggestion.smtp
        )
    }

    @Test
    fun `other domains guess imap and smtp hosts with a password`() {
        val suggestion = detector.detect("ana@Example.ORG")!!

        assertEquals(AuthType.PASSWORD, suggestion.authType)
        assertEquals(
            ServerEndpoint("imap.example.org", 993, ConnectionSecurity.TLS),
            suggestion.imap
        )
        assertEquals(
            ServerEndpoint("smtp.example.org", 587, ConnectionSecurity.STARTTLS),
            suggestion.smtp
        )
    }

    @Test
    fun `an invalid address has no suggestion`() {
        assertNull(detector.detect("not-an-address"))
    }
}
