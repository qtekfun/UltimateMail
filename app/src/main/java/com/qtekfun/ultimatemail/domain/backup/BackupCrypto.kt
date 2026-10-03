// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * How the key is derived from the passphrase. [iterations] is what new files use; files already
 * written say their own count in the header, which is accepted between [minIterations] and
 * [maxIterations] (a hostile header must neither weaken the key nor hang the phone).
 */
data class KdfPolicy(val iterations: Int, val minIterations: Int, val maxIterations: Int) {
    companion object {
        /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256. */
        const val PRODUCTION_ITERATIONS = 600_000

        private const val PRODUCTION_FLOOR = 100_000
        private const val PRODUCTION_CEILING = 5_000_000

        val PRODUCTION = KdfPolicy(PRODUCTION_ITERATIONS, PRODUCTION_FLOOR, PRODUCTION_CEILING)
    }
}

/** The readable start of a container: everything needed to derive the key again. */
data class BackupHeader(
    val version: Int,
    val kdf: Int,
    val iterations: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
    /** The number of bytes the header takes; the encrypted data follows. */
    val length: Int
) {
    // Arrays: compare by content, so two headers read from the same bytes are equal.
    override fun equals(other: Any?): Boolean = other is BackupHeader &&
        version == other.version && kdf == other.kdf && iterations == other.iterations &&
        salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = 31 * (31 * version + iterations) + salt.contentHashCode()
}

/** The outcome of [BackupCrypto.decrypt]. */
sealed interface DecryptResult {
    class Plain(val bytes: ByteArray) : DecryptResult

    data class Failed(val error: BackupError) : DecryptResult
}

/**
 * The encrypted container of a backup (RF-12), with JDK classes only:
 *
 * `"UMBK" | version u8 | kdf u8 (1 = PBKDF2-HMAC-SHA256) | iterations u32 | saltLen u8 | salt |
 * nonceLen u8 | nonce | ciphertext + 16-byte GCM tag`
 *
 * The key is AES-256, derived from the passphrase with a random 16-byte salt; the nonce is a
 * random 12 bytes. The whole header is the associated data of GCM, so changing any byte of it
 * (the iteration count included) makes the decryption fail like a wrong passphrase does.
 */
class BackupCrypto(
    private val policy: KdfPolicy = KdfPolicy.PRODUCTION,
    private val random: SecureRandom = SecureRandom()
) {
    /** Encrypts [plain]; [passphrase] is not modified (the caller clears it). */
    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = headerBytes(policy.iterations, salt, nonce)
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, policy.iterations, salt, nonce, header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): DecryptResult {
        if (container.size > BackupFormat.MAX_FILE_BYTES) {
            return DecryptResult.Failed(BackupError.TooLarge)
        }
        val header = when (val parsed = readHeader(container)) {
            is HeaderResult.Valid -> parsed.header
            is HeaderResult.Invalid -> return DecryptResult.Failed(parsed.error)
        }
        if (container.size - header.length < TAG_BYTES) {
            return DecryptResult.Failed(BackupError.Truncated)
        }
        return try {
            val aad = container.copyOfRange(0, header.length)
            val cipher = cipher(
                Cipher.DECRYPT_MODE,
                passphrase,
                header.iterations,
                header.salt,
                header.nonce,
                aad
            )
            DecryptResult.Plain(
                cipher.doFinal(container, header.length, container.size - header.length)
            )
        } catch (_: GeneralSecurityException) {
            // A wrong key and a changed file are the same thing to GCM.
            DecryptResult.Failed(BackupError.WrongPassphraseOrDamaged)
        }
    }

    /** Reads and checks the header of [container] without deriving any key. */
    fun readHeader(container: ByteArray): HeaderResult {
        val parsed = parseHeader(container)
        return when {
            parsed is HeaderResult.Valid &&
                parsed.header.iterations !in policy.minIterations..policy.maxIterations ->
                HeaderResult.Invalid(BackupError.UnsupportedKdf)

            else -> parsed
        }
    }

    // Each check leaves early; guard clauses keep the reading flat.
    @Suppress("ReturnCount")
    private fun parseHeader(bytes: ByteArray): HeaderResult {
        if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return HeaderResult.Invalid(BackupError.NotABackup)
        }
        if (bytes.size < FIXED_BYTES) return HeaderResult.Invalid(BackupError.Truncated)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(MAGIC.size)
        val version = buffer.get().toInt() and BYTE_MASK
        if (version != CONTAINER_VERSION) {
            return HeaderResult.Invalid(BackupError.UnsupportedVersion(version))
        }
        val kdf = buffer.get().toInt() and BYTE_MASK
        val iterations = buffer.getInt()
        if (kdf != KDF_PBKDF2_SHA256) return HeaderResult.Invalid(BackupError.UnsupportedKdf)
        val salt = readBlock(buffer) ?: return HeaderResult.Invalid(BackupError.Truncated)
        val nonce = readBlock(buffer) ?: return HeaderResult.Invalid(BackupError.Truncated)
        if (salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES || nonce.size != NONCE_BYTES) {
            return HeaderResult.Invalid(BackupError.UnsupportedKdf)
        }
        return HeaderResult.Valid(
            BackupHeader(version, kdf, iterations, salt, nonce, buffer.position())
        )
    }

    private fun readBlock(buffer: ByteBuffer): ByteArray? {
        if (!buffer.hasRemaining()) return null
        val length = buffer.get().toInt() and BYTE_MASK
        if (buffer.remaining() < length) return null
        return ByteArray(length).also(buffer::get)
    }

    private fun headerBytes(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(FIXED_BYTES + salt.size + nonce.size).apply {
            put(MAGIC)
            put(CONTAINER_VERSION.toByte())
            put(KDF_PBKDF2_SHA256.toByte())
            putInt(iterations)
            put(salt.size.toByte())
            put(salt)
            put(nonce.size.toByte())
            put(nonce)
        }.array()

    @Suppress("LongParameterList") // The cipher needs the whole header worth of inputs.
    private fun cipher(
        mode: Int,
        passphrase: CharArray,
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
        aad: ByteArray
    ): Cipher {
        val keyBytes = deriveKey(passphrase, iterations, salt)
        try {
            return Cipher.getInstance(TRANSFORMATION).apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
                updateAAD(aad)
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, iterations: Int, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        val MAGIC: ByteArray = "UMBK".toByteArray(Charsets.US_ASCII)
        const val CONTAINER_VERSION = 1
        const val KDF_PBKDF2_SHA256 = 1
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12
        private const val MIN_SALT_BYTES = 8
        private const val MAX_SALT_BYTES = 64
        private const val TAG_BYTES = 16
        private const val KEY_BITS = 256
        private const val BYTE_MASK = 0xFF

        // magic, version, kdf, iterations, salt length, nonce length (without salt and nonce)
        private const val FIXED_BYTES = 4 + 1 + 1 + 4 + 1 + 1
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** The outcome of [BackupCrypto.readHeader]. */
sealed interface HeaderResult {
    data class Valid(val header: BackupHeader) : HeaderResult

    data class Invalid(val error: BackupError) : HeaderResult
}
