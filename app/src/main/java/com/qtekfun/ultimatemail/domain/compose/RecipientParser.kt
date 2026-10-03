// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.net.IDN

/** What [RecipientParser.parseList] made of a typed or pasted list of recipients. */
data class ParsedRecipients(
    val valid: List<MailAddress>,
    /** The entries that are not addresses, as typed, so the field can show them as errors. */
    val invalid: List<String>
) {
    override fun toString(): String =
        "ParsedRecipients(valid=${valid.size}, invalid=${invalid.size})"
}

/**
 * Reads, checks and writes the recipients of a message (RF-07), in the subset of RFC 5322 mail
 * clients meet in practice: `address`, `<address>`, `Name <address>`, `"Quoted, Name" <address>`,
 * `address (Name)`, separated by commas or semicolons. Commas inside quotes, angle brackets or
 * comments do not separate.
 *
 * Addresses are checked, not guessed: the local part must be a dot-atom or a quoted string of
 * ASCII, and the domain a name of at least two labels. Internationalized domain names (IDN) are
 * accepted as typed and converted to Punycode only for sending ([toAscii]); a non-ASCII local
 * part would need SMTPUTF8 and is refused. IP-literal domains (`user@[1.2.3.4]`) are refused.
 */
object RecipientParser {
    private const val MAX_LOCAL = 64
    private const val MAX_DOMAIN = 253
    private const val MAX_LABEL = 63
    private const val MAX_ADDRESS = 254
    private const val ATOM_SPECIALS = "!#$%&'*+/=?^_`{|}~-"
    private const val NAME_SPECIALS = "()<>[]:;@\\,.\""

    /** Splits [text] into recipients; empty entries are ignored. */
    fun parseList(text: String): ParsedRecipients {
        val valid = mutableListOf<MailAddress>()
        val invalid = mutableListOf<String>()
        split(text).forEach { entry ->
            val parsed = parse(entry)
            if (parsed == null) invalid += entry.trim() else valid += parsed
        }
        return ParsedRecipients(valid, invalid)
    }

    /** One recipient, or null if [text] is not a valid address with an optional display name. */
    fun parse(text: String): MailAddress? {
        val entry = text.trim()
        val open = lastUnquoted(entry, '<')
        val address: String
        var name: String?
        if (open >= 0 && entry.endsWith(">")) {
            address = entry.substring(open + 1, entry.length - 1).trim()
            name = unquote(entry.substring(0, open).trim())
        } else {
            val comment = entry.indexOf('(')
            address =
                (if (comment > 0 && entry.endsWith(")")) entry.substring(0, comment) else entry)
                    .trim().removePrefix("<").removeSuffix(">").trim()
            name = if (comment > 0 && entry.endsWith(")")) {
                entry.substring(comment + 1, entry.length - 1).trim()
            } else {
                null
            }
        }
        if (!isValid(address)) return null
        name = name?.takeIf { it.isNotBlank() }
        return MailAddress(address, name)
    }

    /** Whether [address] (no display name) can be sent to. */
    fun isValid(address: String): Boolean {
        val at = address.lastIndexOf('@')
        return at > 0 && address.length <= MAX_ADDRESS &&
            validLocal(address.substring(0, at)) && validDomain(address.substring(at + 1))
    }

    /** The address in the form SMTP needs: the domain in Punycode. Null if it is not valid. */
    fun toAscii(address: String): String? {
        if (!isValid(address)) return null
        val at = address.lastIndexOf('@')
        val domain = runCatching { IDN.toASCII(address.substring(at + 1)) }.getOrNull()
        return domain?.let { address.substring(0, at + 1) + it }
    }

    /** [address] ready for SMTP (Punycode domain); the display name is kept. */
    fun toAscii(address: MailAddress): MailAddress? =
        toAscii(address.address)?.let { MailAddress(it, address.name) }

    /** `Name <address>` with the name quoted when it needs to be, or the bare address. */
    fun format(address: MailAddress): String {
        val name = address.name?.trim().orEmpty()
        return when {
            name.isEmpty() -> address.address

            name.any { it in NAME_SPECIALS } ->
                "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <${address.address}>"

            else -> "$name <${address.address}>"
        }
    }

    /** A list as one line, as a field shows it. */
    fun formatList(addresses: List<MailAddress>): String =
        addresses.joinToString(", ", transform = ::format)

    private fun validLocal(local: String): Boolean = when {
        local.length > MAX_LOCAL -> false

        local.startsWith("\"") -> local.length >= 2 && local.endsWith("\"") &&
            local.substring(1, local.length - 1).let { body ->
                var escaped = false
                body.all { c ->
                    val ok = c.code in 0x20..0x7E && (escaped || (c != '"' && c != '\\'))
                    escaped = !escaped && c == '\\'
                    ok
                } && !escaped
            }

        else -> local.split('.').all { atom ->
            atom.isNotEmpty() &&
                atom.all { it.code < 0x80 && (it.isLetterOrDigit() || it in ATOM_SPECIALS) }
        }
    }

    private fun validDomain(domain: String): Boolean {
        val ascii = runCatching { IDN.toASCII(domain) }.getOrNull() ?: return false
        val labels = ascii.split('.')
        return ascii.length <= MAX_DOMAIN && labels.size >= 2 && labels.all(::validLabel) &&
            !labels.last().all { it.isDigit() }
    }

    private fun validLabel(label: String) = label.length in 1..MAX_LABEL &&
        !label.startsWith("-") && !label.endsWith("-") &&
        label.all { it.code < 0x80 && (it.isLetterOrDigit() || it == '-') }

    /** Cuts [text] at commas and semicolons that are not inside quotes, brackets or comments. */
    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = 0
        var comment = 0
        var escaped = false
        for (c in text) {
            val separator =
                !escaped && !quoted && angle == 0 && comment == 0 && (c == ',' || c == ';')
            when {
                separator -> {
                    parts += current.toString()
                    current.clear()
                    continue
                }

                escaped -> escaped = false

                c == '\\' && (quoted || comment > 0) -> escaped = true

                c == '"' && comment == 0 -> quoted = !quoted

                !quoted && c == '<' -> angle++

                !quoted && c == '>' && angle > 0 -> angle--

                !quoted && c == '(' -> comment++

                !quoted && c == ')' && comment > 0 -> comment--
            }
            current.append(c)
        }
        parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** Index of the last [target] outside quotes, or -1. */
    private fun lastUnquoted(text: String, target: Char): Int {
        var quoted = false
        var escaped = false
        var found = -1
        text.forEachIndexed { index, c ->
            when {
                escaped -> escaped = false
                quoted && c == '\\' -> escaped = true
                c == '"' -> quoted = !quoted
                !quoted && c == target -> found = index
            }
        }
        return found
    }

    private fun unquote(raw: String): String? {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length - 1).replace(Regex("\\\\(.)"), "$1")
        }
        return raw.takeIf { it.isNotEmpty() }
    }
}
