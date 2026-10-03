// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import java.util.Locale

internal sealed interface HtmlToken {
    /** Text exactly as written (character references are not decoded). */
    data class Text(val raw: String) : HtmlToken

    /** A start tag; [attributes] keeps the first of any repeated name, values still undecoded. */
    data class Start(val name: String, val attributes: Map<String, String>) : HtmlToken

    data class End(val name: String) : HtmlToken
}

/**
 * Tolerant HTML tokenizer: it never throws and always moves forward, so any input (unclosed
 * tags, stray angle brackets, binary junk) is read in time linear in its length. Comments,
 * doctypes, CDATA and processing instructions produce no tokens. A tag cut off by the end of the
 * input is dropped, as browsers do.
 */
internal class HtmlTokenizer(private val input: String) {
    private var pos = 0

    /** The next token, or null at the end of the input. */
    fun next(): HtmlToken? {
        var token: HtmlToken? = null
        while (token == null && pos < input.length) {
            token = if (input[pos] == '<') markup() else text()
        }
        return token
    }

    /**
     * Consumes the content of a raw-text element (script, style, textarea...) whose start tag
     * was just read, up to and including its end tag, and returns that content.
     */
    fun rawText(name: String): String {
        val start = pos
        var close = input.indexOf("</", pos)
        while (close >= 0 && !isEndTagAt(input, close, name)) close = input.indexOf("</", close + 2)
        pos = if (close < 0) input.length else afterTag(input, close)
        return input.substring(start, if (close < 0) input.length else close)
    }

    /** Skips an element whose start tag was just read, with everything inside it. */
    fun skipElement(name: String) {
        var depth = 1
        while (depth > 0) {
            when (val token = next()) {
                null -> depth = 0
                is HtmlToken.Start -> if (token.name == name) depth++
                is HtmlToken.End -> if (token.name == name) depth--
                is HtmlToken.Text -> Unit
            }
        }
    }

    /** Skips the rest of the input. */
    fun skipAll() {
        pos = input.length
    }

    private fun text(): HtmlToken {
        val end = input.indexOf('<', pos).let { if (it < 0) input.length else it }
        val token = HtmlToken.Text(input.substring(pos, end))
        pos = end
        return token
    }

    /** Reads what starts with `<`; null when it produced no token (comment and the like). */
    private fun markup(): HtmlToken? {
        val after = input.getOrNull(pos + 1)
        var token: HtmlToken? = null
        when {
            input.startsWith("<!--", pos) -> pos = afterComment(input, pos)

            after == '!' || after == '?' -> pos = afterTag(input, pos)

            after == '/' -> token = endTag()

            after != null && isAsciiLetter(after) -> token = startTag()

            else -> {
                pos++
                token = HtmlToken.Text("<")
            }
        }
        return token
    }

    private fun endTag(): HtmlToken? {
        val nameStart = pos + 2
        val isTag = input.getOrNull(nameStart)?.let(::isAsciiLetter) == true
        var token: HtmlToken? = null
        if (isTag) {
            val name = readName(input, nameStart)
            val reader = AttributeReader(input, nameStart + name.length)
            reader.read()
            pos = reader.position
            token = HtmlToken.End(name.lowercase(Locale.ROOT))
        } else {
            pos = afterTag(input, pos)
        }
        return token
    }

    private fun startTag(): HtmlToken? {
        val name = readName(input, pos + 1)
        val reader = AttributeReader(input, pos + 1 + name.length)
        val attributes = reader.read()
        pos = reader.position
        return attributes?.let { HtmlToken.Start(name.lowercase(Locale.ROOT), it) }
    }
}

private const val COMMENT_OPEN = "<!--"

private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

internal fun isNameEnd(c: Char) = c.isWhitespace() || c == '/' || c == '>'

private fun readName(input: String, from: Int): String {
    var end = from
    while (end < input.length && !isNameEnd(input[end])) end++
    return input.substring(from, end)
}

/** True when an end tag named [name] starts at [at] (the `<` of `</name`). */
private fun isEndTagAt(input: String, at: Int, name: String): Boolean {
    val after = input.getOrNull(at + 2 + name.length)
    return input.regionMatches(at + 2, name, 0, name.length, ignoreCase = true) &&
        (after == null || after.isWhitespace() || after == '/' || after == '>')
}

/** Index just after the next `>` at or after [at], or the end of the input. */
private fun afterTag(input: String, at: Int): Int {
    val close = input.indexOf('>', at)
    return if (close < 0) input.length else close + 1
}

/** Index just after the comment that starts at [at], closed by `-->` or `--!>`. */
private fun afterComment(input: String, at: Int): Int {
    if (input.startsWith("<!-->", at) || input.startsWith("<!--->", at)) return afterTag(input, at)
    var dashes = input.indexOf("--", at + COMMENT_OPEN.length)
    while (dashes >= 0 && !closesComment(input, dashes)) dashes = input.indexOf("--", dashes + 1)
    return if (dashes < 0) input.length else afterTag(input, dashes)
}

private fun closesComment(input: String, at: Int) =
    input.startsWith(">", at + 2) || input.startsWith("!>", at + 2)

/** Reads the attributes of a tag, from just after its name up to the closing `>`. */
internal class AttributeReader(private val input: String, private var pos: Int) {
    /** Where reading stopped: after the `>`, or at the end of the input. */
    val position: Int get() = pos

    /** The attributes; null when the input ends inside the tag. */
    fun read(): Map<String, String>? {
        val attributes = LinkedHashMap<String, String>()
        var closed = false
        while (!closed && pos < input.length) {
            skipWhile { it.isWhitespace() || it == '/' }
            if (pos < input.length && input[pos] == '>') {
                pos++
                closed = true
            } else if (pos < input.length) {
                val name = readName()
                attributes.putIfAbsent(name.lowercase(Locale.ROOT), readValue())
            }
        }
        return if (closed) attributes else null
    }

    private fun readName(): String {
        val start = pos
        // A leading `=` belongs to the name, which also guarantees progress.
        pos++
        while (pos < input.length && !isNameEnd(input[pos]) && input[pos] != '=') pos++
        return input.substring(start, pos)
    }

    private fun readValue(): String {
        skipWhile { it.isWhitespace() }
        var value = ""
        if (pos < input.length && input[pos] == '=') {
            pos++
            skipWhile { it.isWhitespace() }
            value = if (pos < input.length && (input[pos] == '"' || input[pos] == '\'')) {
                quoted(input[pos])
            } else {
                val start = pos
                skipWhile { !it.isWhitespace() && it != '>' }
                input.substring(start, pos)
            }
        }
        return value
    }

    private fun quoted(quote: Char): String {
        val close = input.indexOf(quote, pos + 1)
        val end = if (close < 0) input.length else close
        val value = input.substring(pos + 1, end)
        pos = if (close < 0) input.length else close + 1
        return value
    }

    private fun skipWhile(predicate: (Char) -> Boolean) {
        while (pos < input.length && predicate(input[pos])) pos++
    }
}
