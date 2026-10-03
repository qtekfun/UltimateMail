// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.qtekfun.ultimatemail.domain.compose.AttachmentSource
import com.qtekfun.ultimatemail.domain.compose.SourceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

/** Reads the files the user picks through Android's ContentResolver (RF-07). */
class ContentResolverSource @Inject constructor(@ApplicationContext private val context: Context) :
    AttachmentSource {
    override fun describe(uri: String): SourceInfo? = runCatching {
        val parsed = Uri.parse(uri)
        val type = context.contentResolver.getType(parsed)
        context.contentResolver.query(
            parsed,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            SourceInfo(name ?: parsed.lastPathSegment, type, size)
        }
    }.getOrNull()

    override fun open(uri: String): InputStream? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()
}
