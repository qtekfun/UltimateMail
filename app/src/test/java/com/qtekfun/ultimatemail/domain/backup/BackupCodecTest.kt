// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BackupCodecTest {
    private fun decode(text: String) = BackupCodec.decode(text.toByteArray())

    private fun decoded(result: DecodeResult) = (result as DecodeResult.Decoded).document

    private fun error(result: DecodeResult) = (result as DecodeResult.Failed).error

    /** A valid file with [account] fields replaced by [accountFields]. */
    private fun file(accountFields: String = "", accounts: String? = null, extra: String = "") =
        """{"format":"ultimatemail-backup","formatVersion":1,"appVersion":"1.0.0",
        "accounts":[${accounts ?: account(accountFields)}]$extra}"""

    private fun account(fields: String = "") =
        """{"email":"ana@example.test","username":"ana","authType":"PASSWORD",
        "imap":{"host":"imap.example.test","port":993,"security":"TLS"},
        "smtp":{"host":"smtp.example.test","port":587,"security":"STARTTLS"}${
            if (fields.isEmpty()) "" else ",$fields"
        }}"""

    @Test
    fun `a full document survives encoding and decoding`() {
        val document = backupDocument(
            accounts = listOf(
                backupAccount(credentials = AccountCredentials(password = "pw")),
                backupAccount(
                    email = "bo@gmail.test",
                    authType = AuthType.OAUTH_GOOGLE,
                    credentials = AccountCredentials(oauth = SAMPLE_TOKENS),
                    oauthClientId = "123-abc.apps.googleusercontent.com"
                ),
                backupAccount(email = "cy@example.test", folders = emptyMap())
            )
        )

        val result = decoded(BackupCodec.decode(BackupCodec.encode(document)))

        assertEquals(document, result)
        assertEquals(document.accounts[1].credentials, result.accounts[1].credentials)
        assertEquals(document.accounts[0].folders, result.accounts[0].folders)
    }

    @Test
    fun `a document without settings or credentials has none of either`() {
        val result = decoded(
            BackupCodec.decode(BackupCodec.encode(backupDocument(settings = null)))
        )

        assertNull(result.settings)
        assertNull(result.accounts.single().credentials)
    }

    @Test
    fun `the encoded text never mentions the language or mail`() {
        val text = String(BackupCodec.encode(backupDocument()))

        assertFalse(text.contains("language", ignoreCase = true))
        assertFalse(text.contains("password"))
    }

    @Test
    fun `a minimal account gets the defaults of a new account`() {
        val account = decoded(decode(file())).accounts.single()

        assertEquals("", account.displayName)
        assertEquals("", account.signature)
        assertTrue(account.signatureEnabled)
        assertTrue(account.signatureBeforeQuote)
        assertEquals(90, account.offlineWindowDays)
        assertTrue(account.downloadForOffline)
        assertTrue(account.folders.isEmpty())
        assertNull(account.credentials)
        assertNull(decoded(decode(file())).settings)
    }

    @Test
    fun `a null offline window means the whole mailbox`() {
        val account = decoded(decode(file("\"offlineWindowDays\":null"))).accounts.single()

        assertNull(account.offlineWindowDays)
    }

    @Test
    fun `fields from a later minor version are ignored`() {
        val result = decode(file("\"somethingNew\":{\"a\":[1,2]}", extra = ",\"future\":true"))

        assertEquals("ana@example.test", decoded(result).accounts.single().email)
    }

    @Test
    fun `a newer format version is refused with its number`() {
        val text = file().replace("\"formatVersion\":1", "\"formatVersion\":2")

        assertEquals(BackupError.UnsupportedVersion(2), error(decode(text)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1"])
    fun `a version below one is malformed`(version: String) {
        val text = file().replace("\"formatVersion\":1", "\"formatVersion\":$version")

        assertEquals(BackupError.Malformed, error(decode(text)))
    }

    @Test
    fun `a missing or wrong marker is malformed`() {
        assertEquals(
            BackupError.Malformed,
            error(decode(file().replace("ultimatemail-backup", "other")))
        )
        assertEquals(
            BackupError.Malformed,
            error(decode("""{"formatVersion":1,"accounts":[]}"""))
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not json at all", "[]", "\"text\"", "null", "{\"format\":1}",
            "{\"format\":\"ultimatemail-backup\",\"formatVersion\":\"1\"}",
            "{\"format\":\"ultimatemail-backup\",\"formatVersion\":1}"
        ]
    )
    fun `hostile or malformed documents are refused with a typed error`(text: String) {
        assertEquals(BackupError.Malformed, error(decode(text)))
    }

    @Test
    fun `invalid utf-8 is malformed and does not crash`() {
        val bytes = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)

        assertEquals(BackupError.Malformed, error(BackupCodec.decode(bytes)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"authType\":\"OTHER\"", "\"authType\":1", "\"signatureEnabled\":\"yes\"",
            "\"downloadForOffline\":1", "\"offlineWindowDays\":0",
            "\"offlineWindowDays\":999999", "\"offlineWindowDays\":\"90\"",
            "\"displayName\":7", "\"folders\":{}", "\"folders\":[1]",
            "\"folders\":[{\"path\":\"\",\"sync\":true}]",
            "\"folders\":[{\"path\":\"a\\u0000b\",\"sync\":true}]",
            "\"folders\":[{\"path\":\"a\",\"sync\":1}]",
            "\"folders\":[{\"path\":\"a\",\"sync\":true},{\"path\":\"a\",\"sync\":false}]",
            "\"credentials\":[]", "\"credentials\":{\"password\":5}",
            "\"credentials\":{\"oauth\":{\"refreshToken\":\"r\"}}",
            "\"credentials\":{\"oauth\":{\"accessToken\":\"a\",\"expiresAt\":-1}}",
            "\"credentials\":{\"oauth\":{\"accessToken\":\"a\",\"expiresAt\":\"soon\"}}",
            "\"oauthClientId\":3"
        ]
    )
    fun `fields of the wrong type or range are malformed`(fields: String) {
        assertEquals(BackupError.Malformed, error(decode(file(fields))))
    }

    @Test
    fun `required account fields are required`() {
        val text = file(accounts = """{"email":"ana@example.test"}""")

        assertEquals(BackupError.Malformed, error(decode(text)))
        assertEquals(BackupError.Malformed, error(decode(file(accounts = "7"))))
    }

    @Test
    fun `bad endpoints are malformed`() {
        val badPort = file().replace("\"port\":993", "\"port\":99999999999")
        val badSecurity = file().replace("\"TLS\"", "\"NONE\"")
        val noHost = file().replace("\"host\":\"imap.example.test\",", "")

        assertEquals(BackupError.Malformed, error(decode(badPort)))
        assertEquals(BackupError.Malformed, error(decode(badSecurity)))
        assertEquals(BackupError.Malformed, error(decode(noHost)))
    }

    @Test
    fun `oversized strings are refused`() {
        assertEquals(
            BackupError.Malformed,
            error(decode(file("\"signature\":\"${"x".repeat(5_000)}\"")))
        )
        assertEquals(
            BackupError.Malformed,
            error(decode(file().replace("ana@example.test", "a".repeat(300) + "@example.test")))
        )
        assertEquals(
            BackupError.Malformed,
            error(decode(file("\"credentials\":{\"password\":\"${"p".repeat(2_000)}\"}")))
        )
    }

    @Test
    fun `too many accounts or folders are refused`() {
        val many = List(51) { account() }.joinToString(",")
        val folders = (0..2_000).joinToString(",") { "{\"path\":\"f$it\",\"sync\":true}" }

        assertEquals(BackupError.Malformed, error(decode(file(accounts = many))))
        assertEquals(BackupError.Malformed, error(decode(file("\"folders\":[$folders]"))))
        val fifty = file(accounts = List(50) { account() }.joinToString(","))
        assertEquals(50, decoded(decode(fifty)).accounts.size)
    }

    @Test
    fun `unknown setting values fall back to the defaults instead of failing`() {
        val settings =
            ""","settings":{"theme":"DARK","density":"HUGE","swipeLeft":7,"amoled":true}"""

        val result = decoded(decode(file(extra = settings))).settings!!

        assertEquals(ThemeMode.DARK, result.theme)
        assertTrue(result.amoled)
        assertEquals(AppSettings().density, result.density)
        assertEquals(AppSettings().swipe.left, result.swipe.left)
    }

    @Test
    fun `settings of the wrong shape are malformed`() {
        assertEquals(BackupError.Malformed, error(decode(file(extra = ",\"settings\":[]"))))
        assertEquals(
            BackupError.Malformed,
            error(decode(file(extra = ",\"settings\":{\"amoled\":\"x\"}")))
        )
    }
}
