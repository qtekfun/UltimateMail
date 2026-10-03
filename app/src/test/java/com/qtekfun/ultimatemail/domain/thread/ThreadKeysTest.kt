// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ThreadKeysTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "Re: Plan", "RE: Plan", "re:plan", "Fwd: Plan", "FW: Plan", "Fw: Plan",
            "RE[2]: Plan", "Re(3): Plan", "AW: Plan", "SV: Plan", "ENC: Plan", "WG: Plan",
            "TR: Plan", "VS: Plan",
            // Spanish: RV (reenviar), REENVIO/REENVÍO and ENV.
            "RV: Plan", "REENVIO: Plan", "Reenvío: Plan", "ENV: Plan",
            "  Re :   Plan  ", "Re: Fwd: RE[2]: AW: Plan", "Re:Re:Plan"
        ]
    )
    fun `prefixes in english, spanish and other languages are stripped`(subject: String) {
        val normalised = ThreadKeys.subject(subject)
        assertEquals("plan", normalised.text)
        assertTrue(normalised.hadPrefix)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Plan", "Revolution: plan", "Re plan", "Reply: plan", "Environment: x"])
    fun `a word that only starts like a prefix is kept`(subject: String) {
        val normalised = ThreadKeys.subject(subject)
        assertFalse(normalised.hadPrefix)
        assertEquals(subject.lowercase(), normalised.text)
    }

    @Test
    fun `whitespace collapses, case folds and unicode is compatible-folded`() {
        assertEquals("a b c", ThreadKeys.subject("  A \t B   C\n").text)
        assertEquals("reunión áé", ThreadKeys.subject("Re: REUNIÓN ÁÉ").text)
        // The ligature and the full-width letters fold to plain text (NFKC).
        assertEquals("office", ThreadKeys.subject("oﬃce").text)
        assertEquals("plan", ThreadKeys.subject("ＰＬＡＮ").text)
        // A decomposed accent equals the precomposed one.
        assertEquals(
            ThreadKeys.subject("Café").text,
            ThreadKeys.subject("Café").text
        )
    }

    @Test
    fun `an absent or prefix-only subject becomes empty`() {
        assertEquals("", ThreadKeys.subject(null).text)
        assertEquals("", ThreadKeys.subject("Re:").text)
        assertTrue(ThreadKeys.subject("Re:").hadPrefix)
    }

    @Test
    fun `message ids lose brackets and case`() {
        assertEquals("a@b.test", ThreadKeys.messageId("<A@B.test>"))
        assertEquals("a@b.test", ThreadKeys.messageId("  a@B.TEST "))
        assertEquals("a@b.test", ThreadKeys.messageId("< a@b.test >"))
    }

    @Test
    fun `garbage message ids are rejected`() {
        assertNull(ThreadKeys.messageId(null))
        assertNull(ThreadKeys.messageId(""))
        assertNull(ThreadKeys.messageId("   "))
        assertNull(ThreadKeys.messageId("<>"))
        assertNull(ThreadKeys.messageId("two words@x"))
        assertNull(ThreadKeys.messageId("<<a@b>>"))
        assertNull(ThreadKeys.messageId("a<b@c"))
        assertNull(ThreadKeys.messageId("<" + "x".repeat(513) + ">"))
        assertEquals("x".repeat(512), ThreadKeys.messageId("x".repeat(512)))
    }

    @Test
    fun `a negative window is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ThreadConfig(-1) }
        assertEquals(0L, ThreadConfig(0).subjectWindowMillis)
        assertEquals(2_592_000_000L, ThreadConfig().subjectWindowMillis)
    }
}
