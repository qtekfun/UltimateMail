// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.backup

import android.content.ContentResolver
import android.net.Uri
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.backup.DocumentRead
import com.qtekfun.ultimatemail.domain.backup.DocumentSink
import com.qtekfun.ultimatemail.domain.backup.DocumentSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads and writes the documents the user picked with the system file picker and creator, via
 * the Storage Access Framework. The app never asks for storage permissions: the grant of the
 * picker covers exactly that one document.
 */
class ContentResolverDocuments @Inject constructor(
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher
) : DocumentSink,
    DocumentSource {
    override suspend fun write(uri: String, bytes: ByteArray): Boolean = withContext(io) {
        try {
            // "wt" truncates: a shorter backup must not leave the tail of an older file behind.
            resolver.openOutputStream(Uri.parse(uri), "wt")?.use { it.write(bytes) } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun read(uri: String, maxBytes: Int): DocumentRead = withContext(io) {
        try {
            val stream = resolver.openInputStream(Uri.parse(uri))
                ?: return@withContext DocumentRead.Unreadable
            stream.use {
                // One byte more than the limit tells "exactly full" from "too large".
                // (readNBytes would do, but it needs Android 13.)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (out.size() <= maxBytes) {
                    val count = it.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                if (out.size() > maxBytes) {
                    DocumentRead.TooLarge
                } else {
                    DocumentRead.Bytes(out.toByteArray())
                }
            }
        } catch (_: IOException) {
            DocumentRead.Unreadable
        } catch (_: SecurityException) {
            DocumentRead.Unreadable
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
    }
}
