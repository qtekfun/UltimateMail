// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.domain.compose.StoreResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileOutboxStorageTest {
    @TempDir
    lateinit var root: File

    private fun bytes(vararg values: Int) = ByteArrayInputStream(
        ByteArray(values.size) {
            values[it].toByte()
        }
    )

    @Test
    fun `a file is stored under its draft and can be read back`() {
        val storage = FileOutboxStorage(root)

        val stored = storage.write(7, "report.pdf", bytes(1, 2, 3), 100) as StoreResult.Stored

        assertEquals(File(root, "7/1-report.pdf").absolutePath, stored.path)
        assertEquals(3L, stored.size)
        assertEquals(listOf<Byte>(1, 2, 3), storage.read(stored.path)!!.toList())
        assertTrue(storage.exists(stored.path))
    }

    @Test
    fun `files of one draft are numbered so equal names do not collide`() {
        val storage = FileOutboxStorage(root)

        val first = storage.write(7, "a.txt", bytes(1), 100) as StoreResult.Stored
        val second = storage.write(7, "a.txt", bytes(2), 100) as StoreResult.Stored

        assertEquals("2-a.txt", File(second.path).name)
        assertEquals(listOf(1.toByte()), storage.read(first.path)!!.toList())
        assertEquals(listOf(2.toByte()), storage.read(second.path)!!.toList())
    }

    @Test
    fun `a hostile name cannot leave the folder of its draft`() {
        val storage = FileOutboxStorage(root)

        val stored = storage.write(7, "../../../evil.sh", bytes(1), 100) as StoreResult.Stored

        assertTrue(File(stored.path).canonicalPath.startsWith(File(root, "7").canonicalPath))
        assertFalse(File(root.parentFile, "evil.sh").exists())
    }

    @Test
    fun `more than the allowance keeps nothing`() {
        val storage = FileOutboxStorage(root)

        val result = storage.write(7, "big.bin", bytes(1, 2, 3, 4), maxBytes = 3)

        assertEquals(StoreResult.TooLarge, result)
        assertEquals(emptyList<String>(), File(root, "7").list()!!.toList())
    }

    @Test
    fun `exactly the allowance is accepted and an empty file too`() {
        val storage = FileOutboxStorage(root)

        assertTrue(storage.write(7, "a", bytes(1, 2, 3), maxBytes = 3) is StoreResult.Stored)
        assertEquals(
            0L,
            (storage.write(7, "b", bytes(), maxBytes = 0) as StoreResult.Stored).size
        )
    }

    @Test
    fun `a source that fails half way keeps nothing`() {
        val storage = FileOutboxStorage(root)
        val broken = object : InputStream() {
            private var served = false

            override fun read(): Int = throw IOException("disk on fire")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served) throw IOException("disk on fire")
                served = true
                b[off] = 1
                return 1
            }
        }

        val result = storage.write(7, "a.bin", broken, 100)

        assertEquals(StoreResult.Failed, result)
        assertEquals(emptyList<String>(), File(root, "7").list()!!.toList())
    }

    @Test
    fun `a file can be deleted and one draft's files removed together`() {
        val storage = FileOutboxStorage(root)
        val a = storage.write(7, "a", bytes(1), 10) as StoreResult.Stored
        val b = storage.write(7, "b", bytes(1), 10) as StoreResult.Stored
        val other = storage.write(8, "c", bytes(1), 10) as StoreResult.Stored

        storage.delete(a.path)
        assertFalse(storage.exists(a.path))
        assertTrue(storage.exists(b.path))

        storage.deleteDraft(7)

        assertFalse(storage.exists(b.path))
        assertTrue(storage.exists(other.path))
        storage.deleteDraft(99)
    }

    @Test
    fun `paths outside the outbox are neither read, reported nor deleted`() {
        val storage = FileOutboxStorage(File(root, "outbox"))
        val outside = File(root, "secret.txt").apply { writeText("x") }

        assertNull(storage.read(outside.path))
        assertFalse(storage.exists(outside.path))
        storage.delete(outside.path)
        assertTrue(outside.exists())
        assertNull(storage.read(File(root, "outbox/7/missing").path))
    }
}
