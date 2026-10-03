// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import java.io.File
import java.security.GeneralSecurityException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Reversible stand-in for Keystore: XOR with a fixed key, so ciphertext differs from plaintext. */
private class FakeCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray) = EncryptedSecret(
        plaintext.map {
            (it.toInt() xor KEY).toByte()
        }.toByteArray(),
        ByteArray(12) { 7 }
    )

    override fun decrypt(secret: EncryptedSecret): ByteArray =
        secret.ciphertext.map { (it.toInt() xor KEY).toByte() }.toByteArray()

    private companion object {
        const val KEY = 0x5A
    }
}

private class LostKeyCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray) = EncryptedSecret(plaintext, ByteArray(12))

    override fun decrypt(secret: EncryptedSecret): ByteArray =
        throw GeneralSecurityException("key gone")
}

class CredentialStoreTest {
    @TempDir
    lateinit var dir: File

    private val io = Dispatchers.Unconfined

    private fun store(cipher: SecretCipher = FakeCipher()) =
        CredentialStore(File(dir, "creds"), cipher, io)

    @Test
    fun `saves and loads a password`() = runTest {
        store().save(1, AccountCredentials(password = "app-pässwörd-123"))

        assertEquals("app-pässwörd-123", store().load(1)?.password)
        assertNull(store().load(1)?.oauth)
    }

    @Test
    fun `saves and loads OAuth tokens with their expiry`() = runTest {
        val tokens = OAuthTokens("access-1", "refresh-1", Instant.ofEpochMilli(1_700_000_000_123))

        store().save(2, AccountCredentials(oauth = tokens))

        val loaded = store().load(2)
        assertEquals(tokens, loaded?.oauth)
        assertNull(loaded?.password)
    }

    @Test
    fun `tokens without refresh token or expiry survive the round trip`() = runTest {
        val tokens = OAuthTokens("access-only", null, null)

        store().save(3, AccountCredentials(oauth = tokens))

        assertEquals(tokens, store().load(3)?.oauth)
    }

    @Test
    fun `secrets are only stored encrypted`() = runTest {
        store().save(
            1,
            AccountCredentials(
                "plain-app-password",
                OAuthTokens("plain-access", "plain-refresh", null)
            )
        )

        val raw = File(
            dir,
            "creds"
        ).listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("plain-app-password"))
        assertFalse(raw.contains("plain-access"))
        assertFalse(raw.contains("plain-refresh"))
    }

    @Test
    fun `each account has its own secrets and saving again replaces them`() = runTest {
        store().save(1, AccountCredentials("one"))
        store().save(2, AccountCredentials("two"))

        store().save(1, AccountCredentials("one-new"))

        assertEquals("one-new", store().load(1)?.password)
        assertEquals("two", store().load(2)?.password)
        assertEquals(2, File(dir, "creds").listFiles()!!.size)
    }

    @Test
    fun `deleting removes the secrets and tolerates unknown accounts`() = runTest {
        store().save(1, AccountCredentials("secret"))

        store().delete(1)
        store().delete(99)

        assertNull(store().load(1))
    }

    @Test
    fun `loading an account without secrets gives null`() = runTest {
        assertNull(store().load(5))
    }

    @Test
    fun `secrets that cannot be decrypted any more load as null`() = runTest {
        store().save(1, AccountCredentials("secret"))

        assertNull(store(LostKeyCipher()).load(1))
    }

    @Test
    fun `a truncated file loads as null instead of crashing`() = runTest {
        store().save(1, AccountCredentials("secret"))
        val file = File(dir, "creds").listFiles()!!.single()
        file.writeBytes(file.readBytes().copyOf(6))

        assertNull(store().load(1))
    }

    @Test
    fun `a corrupt length field loads as null`() = runTest {
        File(dir, "creds").mkdirs()
        File(dir, "creds/1.cred").writeBytes(byteArrayOf(0x7F, 0, 0, 0))

        assertNull(store().load(1))
    }

    @Test
    fun `no temporary file is left behind and a failed rename is reported`() = runTest {
        store().save(1, AccountCredentials("secret"))
        assertTrue(File(dir, "creds").listFiles()!!.none { it.name.endsWith(".tmp") })

        // A directory in the place of the target makes the atomic rename fail.
        File(dir, "creds/2.cred").mkdirs()
        File(dir, "creds/2.cred/keep").writeText("x")
        val failure = runCatching { store().save(2, AccountCredentials("secret")) }

        assertTrue(failure.exceptionOrNull() is java.io.IOException)
        assertTrue(File(dir, "creds").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `credentials never print their secrets`() {
        val credentials = AccountCredentials("hunter2", OAuthTokens("tok-a", "tok-r", null))

        assertFalse(credentials.toString().contains("hunter2"))
        assertFalse(credentials.oauth.toString().contains("tok-a"))
    }
}
