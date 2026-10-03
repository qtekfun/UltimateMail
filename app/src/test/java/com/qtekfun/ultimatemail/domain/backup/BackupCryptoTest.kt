// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupCryptoTest {
    // Few iterations keep the tests fast; production uses KdfPolicy.PRODUCTION.
    private val fast = KdfPolicy(iterations = 1_000, minIterations = 500, maxIterations = 20_000)
    private val crypto = BackupCrypto(fast)
    private val plain = "the configuration".toByteArray()

    private fun pass(text: String = "correct horse") = text.toCharArray()

    private fun failure(result: DecryptResult): BackupError = (result as DecryptResult.Failed).error

    @Test
    fun `what is encrypted decrypts with the same passphrase`() {
        val container = crypto.encrypt(plain, pass())

        val result = crypto.decrypt(container, pass()) as DecryptResult.Plain

        assertArrayEquals(plain, result.bytes)
    }

    @Test
    fun `the container does not contain the plain text and starts with the magic bytes`() {
        val container = crypto.encrypt(plain, pass())

        assertArrayEquals(BackupCrypto.MAGIC, container.copyOfRange(0, 4))
        assertFalse(String(container, Charsets.ISO_8859_1).contains("configuration"))
    }

    @Test
    fun `salt and nonce are random for every file`() {
        val a = (crypto.readHeader(crypto.encrypt(plain, pass())) as HeaderResult.Valid).header
        val b = (crypto.readHeader(crypto.encrypt(plain, pass())) as HeaderResult.Valid).header

        assertEquals(16, a.salt.size)
        assertEquals(12, a.nonce.size)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.nonce.contentEquals(b.nonce))
    }

    @Test
    fun `a wrong passphrase fails cleanly`() {
        val container = crypto.encrypt(plain, pass())

        assertEquals(
            BackupError.WrongPassphraseOrDamaged,
            failure(crypto.decrypt(container, pass("battery staple")))
        )
    }

    @Test
    fun `changing any single byte of the file makes it fail`() {
        val container = crypto.encrypt(plain, pass())

        container.indices.forEach { position ->
            val tampered = container.copyOf().also { it[position] = (it[position] + 1).toByte() }
            val result = crypto.decrypt(tampered, pass())
            // Header bytes may be refused earlier (magic, version, kdf, limits); none may open.
            assertTrue(result is DecryptResult.Failed, "byte $position changed but it opened")
        }
    }

    @Test
    fun `a file cut short fails at every length`() {
        val container = crypto.encrypt(plain, pass())

        (0 until container.size).forEach { length ->
            val result = crypto.decrypt(container.copyOf(length), pass())
            assertTrue(result is DecryptResult.Failed, "length $length opened")
        }
    }

    @Test
    fun `a file cut inside the header is reported as truncated`() {
        val container = crypto.encrypt(plain, pass())

        assertEquals(BackupError.Truncated, failure(crypto.decrypt(container.copyOf(8), pass())))
        assertEquals(BackupError.Truncated, failure(crypto.decrypt(container.copyOf(12), pass())))
        assertEquals(BackupError.Truncated, failure(crypto.decrypt(container.copyOf(20), pass())))
    }

    @Test
    fun `a file with no room for the authentication tag is truncated`() {
        val container = crypto.encrypt(plain, pass())
        val headerLength = (crypto.readHeader(container) as HeaderResult.Valid).header.length

        assertEquals(
            BackupError.Truncated,
            failure(crypto.decrypt(container.copyOf(headerLength + 5), pass()))
        )
    }

    @Test
    fun `other files are not backups`() {
        assertEquals(BackupError.NotABackup, failure(crypto.decrypt(ByteArray(0), pass())))
        assertEquals(
            BackupError.NotABackup,
            failure(crypto.decrypt("{\"accounts\":[]}".toByteArray(), pass()))
        )
    }

    @Test
    fun `the iteration count is read from the header, not from the policy`() {
        val writer = BackupCrypto(KdfPolicy(1_500, 500, 20_000))
        val reader = BackupCrypto(KdfPolicy(2_500, 500, 20_000))
        val container = writer.encrypt(plain, pass())

        val header = (reader.readHeader(container) as HeaderResult.Valid).header
        val result = reader.decrypt(container, pass()) as DecryptResult.Plain

        assertEquals(1_500, header.iterations)
        assertEquals(1, header.kdf)
        assertEquals(1, header.version)
        assertArrayEquals(plain, result.bytes)
    }

    @Test
    fun `iteration counts outside what the policy accepts are refused`() {
        val container = crypto.encrypt(plain, pass())
        fun withIterations(count: Int) = container.copyOf().also {
            ByteBuffer.wrap(it).putInt(6, count)
        }

        assertEquals(
            BackupError.UnsupportedKdf,
            failure(crypto.decrypt(withIterations(1), pass()))
        )
        assertEquals(
            BackupError.UnsupportedKdf,
            failure(crypto.decrypt(withIterations(Int.MAX_VALUE), pass()))
        )
        assertEquals(
            BackupError.UnsupportedKdf,
            failure(crypto.decrypt(withIterations(-5), pass()))
        )
    }

    @Test
    fun `an iteration count inside the limits that was edited fails authentication`() {
        val container = crypto.encrypt(plain, pass())
        val edited = container.copyOf().also { ByteBuffer.wrap(it).putInt(6, 1_001) }

        assertEquals(
            BackupError.WrongPassphraseOrDamaged,
            failure(crypto.decrypt(edited, pass()))
        )
    }

    @Test
    fun `a newer container version and an unknown kdf are refused`() {
        val container = crypto.encrypt(plain, pass())

        val newer = container.copyOf().also { it[4] = 2 }
        val unknownKdf = container.copyOf().also { it[5] = 9 }

        assertEquals(BackupError.UnsupportedVersion(2), failure(crypto.decrypt(newer, pass())))
        assertEquals(BackupError.UnsupportedKdf, failure(crypto.decrypt(unknownKdf, pass())))
    }

    @Test
    fun `odd salt and nonce sizes are refused`() {
        fun container(saltBytes: Int, nonceBytes: Int) = BackupCrypto.MAGIC +
            byteArrayOf(1, 1, 0, 0, 3, 0xE8.toByte(), saltBytes.toByte()) + ByteArray(saltBytes) +
            byteArrayOf(nonceBytes.toByte()) + ByteArray(nonceBytes) + ByteArray(32)

        assertEquals(BackupError.UnsupportedKdf, failure(crypto.decrypt(container(4, 12), pass())))
        assertEquals(BackupError.UnsupportedKdf, failure(crypto.decrypt(container(16, 8), pass())))
        assertEquals(
            BackupError.UnsupportedKdf,
            failure(crypto.decrypt(container(200, 12), pass()))
        )
        // The same framing with sizes in range is only a wrong key, not a refusal.
        assertEquals(
            BackupError.WrongPassphraseOrDamaged,
            failure(crypto.decrypt(container(16, 12), pass()))
        )
    }

    @Test
    fun `a file larger than the limit is refused before anything else`() {
        val huge = ByteArray(BackupFormat.MAX_FILE_BYTES + 1)

        assertEquals(BackupError.TooLarge, failure(crypto.decrypt(huge, pass())))
    }

    @Test
    fun `the passphrase array is left for the caller to wipe and is not changed`() {
        val passphrase = pass()

        crypto.encrypt(plain, passphrase)

        assertArrayEquals(pass(), passphrase)
    }

    @Test
    fun `production settings use at least 600000 iterations of PBKDF2`() {
        assertTrue(KdfPolicy.PRODUCTION_ITERATIONS >= 600_000)
        assertEquals(KdfPolicy.PRODUCTION_ITERATIONS, KdfPolicy.PRODUCTION.iterations)
        assertTrue(KdfPolicy.PRODUCTION.minIterations >= 100_000)
    }

    @Test
    fun `a file written with the default crypto carries the production iteration count`() {
        val container = BackupCrypto().encrypt(plain, pass())

        val header = (BackupCrypto().readHeader(container) as HeaderResult.Valid).header
        val result = BackupCrypto().decrypt(container, pass()) as DecryptResult.Plain

        assertEquals(KdfPolicy.PRODUCTION_ITERATIONS, header.iterations)
        assertArrayEquals(plain, result.bytes)
    }

    @Test
    fun `headers read from the same bytes are equal`() {
        val container = crypto.encrypt(plain, pass())

        val a = (crypto.readHeader(container) as HeaderResult.Valid).header
        val b = (crypto.readHeader(container) as HeaderResult.Valid).header

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(iterations = 7))
    }
}
