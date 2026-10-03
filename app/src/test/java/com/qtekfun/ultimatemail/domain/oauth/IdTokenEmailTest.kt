// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IdTokenEmailTest {
    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "header.${encoder.encodeToString(payload.toByteArray())}.signature"
    }

    @Test
    fun `reads the email claim`() {
        val jwt = token("""{"sub":"1","email":"ana@gmail.test","email_verified":true}""")

        assertEquals("ana@gmail.test", IdTokenEmail.from(jwt))
    }

    @Test
    fun `tolerates spaces around the colon`() {
        assertEquals("a@b.test", IdTokenEmail.from(token("""{"email" : "a@b.test"}""")))
    }

    @Test
    fun `returns null when there is no token, no payload, bad base64 or no claim`() {
        assertNull(IdTokenEmail.from(null))
        assertNull(IdTokenEmail.from("only-one-part"))
        assertNull(IdTokenEmail.from("header.%%%not-base64%%%.signature"))
        assertNull(IdTokenEmail.from(token("""{"sub":"1"}""")))
    }

    @Test
    fun `falls back to preferred_username when there is no email claim`() {
        val jwt = token("""{"sub":"1","preferred_username":"ana@contoso.test"}""")

        assertEquals("ana@contoso.test", IdTokenEmail.from(jwt))
    }

    @Test
    fun `the email claim wins over preferred_username`() {
        val jwt = token("""{"preferred_username":"upn@contoso.test","email":"ana@contoso.test"}""")

        assertEquals("ana@contoso.test", IdTokenEmail.from(jwt))
    }

    @Test
    fun `a preferred_username that is not an address is not used`() {
        assertNull(IdTokenEmail.from(token("""{"preferred_username":"ana"}""")))
    }
}
