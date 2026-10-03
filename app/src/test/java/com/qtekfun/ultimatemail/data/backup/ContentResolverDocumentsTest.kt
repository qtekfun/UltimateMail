// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.backup

import android.content.ContentResolver
import android.net.Uri
import com.qtekfun.ultimatemail.domain.backup.DocumentRead
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContentResolverDocumentsTest {
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val documents = ContentResolverDocuments(resolver, Dispatchers.Unconfined)

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse("content://docs/1") } returns uri
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `writing truncates the document and stores the bytes`() = runTest {
        val out = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns out

        val written = documents.write("content://docs/1", byteArrayOf(1, 2, 3))

        assertTrue(written)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun `a document that cannot be opened or written is a failure, not a crash`() = runTest {
        every { resolver.openOutputStream(uri, "wt") } returns null
        assertFalse(documents.write("content://docs/1", byteArrayOf(1)))

        every { resolver.openOutputStream(uri, "wt") } throws IOException("gone")
        assertFalse(documents.write("content://docs/1", byteArrayOf(1)))

        every { resolver.openOutputStream(uri, "wt") } throws SecurityException("revoked")
        assertFalse(documents.write("content://docs/1", byteArrayOf(1)))
    }

    @Test
    fun `reading returns the bytes of a document within the limit`() = runTest {
        val content = ByteArray(20_000) { (it % 251).toByte() }
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(content)

        val read = documents.read("content://docs/1", maxBytes = 20_000)

        assertArrayEquals(content, (read as DocumentRead.Bytes).bytes)
    }

    @Test
    fun `a document one byte over the limit is too large and is not read to the end`() = runTest {
        val consumed = intArrayOf(0)
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = 0.also { consumed[0]++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed[0] += len
                return len
            }
        }

        val read = documents.read("content://docs/1", maxBytes = 100)

        assertEquals(DocumentRead.TooLarge, read)
        assertTrue(consumed[0] <= 101 + 8 * 1024)
    }

    @Test
    fun `a document of exactly the limit is accepted`() = runTest {
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(ByteArray(100))

        val read = documents.read("content://docs/1", maxBytes = 100)

        assertEquals(100, (read as DocumentRead.Bytes).bytes.size)
    }

    @Test
    fun `a document that cannot be opened or read is unreadable`() = runTest {
        every { resolver.openInputStream(uri) } returns null
        assertEquals(DocumentRead.Unreadable, documents.read("content://docs/1", 100))

        every { resolver.openInputStream(uri) } throws SecurityException("revoked")
        assertEquals(DocumentRead.Unreadable, documents.read("content://docs/1", 100))

        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        }
        assertEquals(DocumentRead.Unreadable, documents.read("content://docs/1", 100))
    }
}
