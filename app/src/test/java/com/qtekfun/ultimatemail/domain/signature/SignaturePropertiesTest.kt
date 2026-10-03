// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.signature

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Property-style tests: many seeded random bodies built from tricky lines (quotes at several
 * depths, delimiters inside quotes, look-alikes, forward headers, mixed line endings) must keep
 * the invariants of the signature logic.
 */
class SignaturePropertiesTest {
    private val ana = SignatureSettings("Ana\nCEO")
    private val work = SignatureSettings("Work Inc.\n+34 600 000 000")
    private val none = SignatureSettings("")

    private val pieces = listOf(
        "Hello", "", "", "Thanks, see below:", "On Mon, Bob wrote:", "> quoted", ">> deeper", ">",
        "> -- ", "> > -- ", "> Bob", "  indented", "---", "--x", "- - ", "-- not a delimiter",
        "---------- Forwarded message ---------", "ünïcode — texto", "a line with trailing  "
    )

    private fun randomBody(random: Random): String {
        val crlf = random.nextBoolean()
        val lines = List(random.nextInt(0, 12)) { pieces.random(random) }
        val endings = lines.map { if (crlf || random.nextInt(8) == 0) "\r\n" else "\n" }
        val body = lines.indices.joinToString("") { lines[it] + endings[it] }
        return if (random.nextBoolean()) body.removeSuffix("\n").removeSuffix("\r") else body
    }

    private fun show(body: String) = body.replace("\r", "\\r").replace("\n", "\\n")

    private fun contentLines(body: String) =
        body.split("\r\n", "\n").map { it.trimEnd() }.filter { it.isNotEmpty() }

    private fun forEachBody(check: (body: String, kind: ComposeKind, below: Boolean) -> Unit) {
        val random = Random(20260503)
        repeat(ITERATIONS) {
            val body = randomBody(random)
            ComposeKind.entries.forEach { kind ->
                listOf(false, true).forEach { below -> check(body, kind, below) }
            }
        }
    }

    @Test
    fun `random bodies without a signature of their own have none to begin with`() {
        forEachBody { body, _, _ -> assertTrue(unquotedDelimiters(body) == 0, show(body)) }
    }

    @Test
    fun `applying is idempotent and adds exactly one delimiter`() = forEachBody {
            body,
            kind,
            below
        ->
        val settings = ana.copy(beforeQuote = !below)

        val once = SignatureEditor.apply(body, kind, settings)

        assertEquals(once, SignatureEditor.apply(once, kind, settings), show(body))
        assertEquals(1, unquotedDelimiters(once), show(body))
        if (!body.contains(
                "Forwarded message"
            )
        ) {
            assertTrue(SignatureEditor.hasSignature(once), show(body))
        }
    }

    @Test
    fun `a disabled or empty signature never changes the body`() = forEachBody { body, kind, _ ->
        assertEquals(body, SignatureEditor.apply(body, kind, none))
        assertEquals(body, SignatureEditor.apply(body, kind, ana.copy(enabled = false)))
        assertEquals(body, SignatureEditor.replace(body, null, none, kind))
    }

    @Test
    fun `the user's text and quote are never touched`() = forEachBody { body, kind, below ->
        val settings = ana.copy(beforeQuote = !below)

        val applied = SignatureEditor.apply(body, kind, settings)
        val switched = SignatureEditor.replace(applied, settings, work, kind)

        val sigLines = listOf("--") + contentLines(ana.text)
        assertEquals(contentLines(body), contentLines(applied) - sigLines.toSet(), show(body))
        assertEquals(
            contentLines(body),
            contentLines(switched) - (listOf("--") + contentLines(work.text)).toSet(),
            body
        )
    }

    @Test
    fun `removing the signature leaves the original text, up to blank lines`() = forEachBody {
            body,
            kind,
            below
        ->
        val settings = ana.copy(beforeQuote = !below)

        val removed = SignatureEditor.replace(
            SignatureEditor.apply(body, kind, settings),
            settings,
            none,
            kind
        )

        assertEquals(contentLines(body), contentLines(removed), show(body))
        assertEquals(0, unquotedDelimiters(removed), show(body))
    }

    @Test
    fun `switching equals applying the new account, and switching back restores the draft`() =
        forEachBody { body, kind, below ->
            val a = ana.copy(beforeQuote = !below)
            val w = work.copy(beforeQuote = !below)
            val draft = SignatureEditor.apply(body, kind, a)

            val switched = SignatureEditor.replace(draft, a, w, kind)

            assertEquals(SignatureEditor.apply(body, kind, w), switched, show(body))
            assertEquals(draft, SignatureEditor.replace(switched, w, a, kind), show(body))
            assertEquals(1, unquotedDelimiters(switched), show(body))
        }

    @Test
    fun `line endings of an all-CRLF body are all CRLF afterwards`() {
        val random = Random(7)
        repeat(ITERATIONS) {
            val body = List(random.nextInt(1, 10)) {
                pieces.random(random)
            }.joinToString("\r\n", postfix = "\r\n")

            val result = SignatureEditor.apply(body, ComposeKind.REPLY, ana)

            assertTrue(!Regex("(?<!\r)\n").containsMatchIn(result), show(body))
        }
    }

    private companion object {
        const val ITERATIONS = 400
    }
}
