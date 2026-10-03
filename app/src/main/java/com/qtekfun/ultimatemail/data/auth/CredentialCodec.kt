// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.Instant

/** The binary layouts of the plaintext secrets and of the encrypted file around them. */
internal object CredentialCodec {
    private const val MAX_FIELD_BYTES = 1 shl 20

    fun encode(credentials: AccountCredentials): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeNullable(credentials.password) { writeText(it) }
            writeNullable(credentials.oauth) { oauth ->
                writeText(oauth.accessToken)
                writeNullable(oauth.refreshToken) { writeText(it) }
                writeNullable(oauth.expiresAt) { writeLong(it.toEpochMilli()) }
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): AccountCredentials =
        DataInputStream(ByteArrayInputStream(bytes)).run {
            val password = readNullable { readText() }
            val oauth = readNullable {
                val access = readText()
                val refresh = readNullable { readText() }
                val expiresAt = readNullable { Instant.ofEpochMilli(readLong()) }
                OAuthTokens(access, refresh, expiresAt)
            }
            AccountCredentials(password, oauth)
        }

    fun frame(secret: EncryptedSecret): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeBlock(secret.iv)
            writeBlock(secret.ciphertext)
        }
    }.toByteArray()

    fun unframe(bytes: ByteArray): EncryptedSecret =
        DataInputStream(ByteArrayInputStream(bytes)).run {
            val iv = readBlock()
            EncryptedSecret(ciphertext = readBlock(), iv = iv)
        }

    private fun DataOutputStream.writeBlock(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBlock(): ByteArray {
        val size = readInt()
        if (size !in 0..MAX_FIELD_BYTES) throw IOException("Corrupt credentials")
        return ByteArray(size).also { readFully(it) }
    }

    private fun DataOutputStream.writeText(text: String) =
        writeBlock(text.toByteArray(Charsets.UTF_8))

    private fun DataInputStream.readText(): String = readBlock().toString(Charsets.UTF_8)

    private fun <T : Any> DataOutputStream.writeNullable(value: T?, write: (T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) write(value)
    }

    private fun <T : Any> DataInputStream.readNullable(read: () -> T): T? =
        if (readBoolean()) read() else null
}
