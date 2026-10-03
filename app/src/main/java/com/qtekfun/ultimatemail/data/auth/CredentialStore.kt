// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Stores the secrets of each account in its own file, encrypted with [SecretCipher]: plaintext
 * only ever exists in memory. [directory] should live outside backups (`noBackupFilesDir`), so
 * ciphertext never leaves the device.
 */
class CredentialStore(
    private val directory: File,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher
) : CredentialVault {

    override suspend fun save(accountId: Long, credentials: AccountCredentials) {
        withContext(io) {
            val secret = cipher.encrypt(CredentialCodec.encode(credentials))
            directory.mkdirs()
            val target = fileOf(accountId)
            val temp = File(directory, "${target.name}$TEMP_SUFFIX")
            temp.writeBytes(CredentialCodec.frame(secret))
            // Rename is atomic: a crash never leaves a half-written credentials file.
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Could not store credentials")
            }
        }
    }

    override suspend fun load(accountId: Long): AccountCredentials? = withContext(io) {
        val file = fileOf(accountId)
        if (!file.isFile) return@withContext null
        try {
            val secret = CredentialCodec.unframe(file.readBytes())
            CredentialCodec.decode(cipher.decrypt(secret))
        } catch (_: GeneralSecurityException) {
            // The key is gone (e.g. app data restored on another device): sign in again.
            null
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun delete(accountId: Long) {
        withContext(io) { fileOf(accountId).delete() }
    }

    private fun fileOf(accountId: Long) = File(directory, "$accountId$SUFFIX")

    private companion object {
        const val SUFFIX = ".cred"
        const val TEMP_SUFFIX = ".tmp"
    }
}
