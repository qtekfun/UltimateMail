// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import java.io.InputStream
import java.time.ZoneOffset
import java.util.Locale

/** Outbox files in memory: the "files" are map entries, paths look like real ones. */
class FakeOutboxStorage : OutboxFileStorage {
    val files = mutableMapOf<String, ByteArray>()
    val deletedDrafts = mutableListOf<Long>()

    /** When set, [write] fails like a full disk. */
    var failWrites = false
    private var counter = 0

    override fun write(
        draftId: Long,
        displayName: String,
        source: InputStream,
        maxBytes: Long
    ): StoreResult {
        val bytes = source.readBytes()
        return when {
            failWrites -> StoreResult.Failed

            bytes.size > maxBytes -> StoreResult.TooLarge

            else -> {
                val path = "/outbox/$draftId/${++counter}-$displayName"
                files[path] = bytes
                StoreResult.Stored(path, bytes.size.toLong())
            }
        }
    }

    override fun read(path: String): ByteArray? = files[path]

    override fun exists(path: String) = path in files

    override fun delete(path: String) {
        files.remove(path)
    }

    override fun deleteDraft(draftId: Long) {
        deletedDrafts += draftId
        files.keys.removeAll { it.startsWith("/outbox/$draftId/") }
    }
}

/** Picked files by URI, with the size the picker would report. */
class FakeAttachmentSource : AttachmentSource {
    private class Entry(val info: SourceInfo?, val content: ByteArray?)

    private val entries = mutableMapOf<String, Entry>()

    fun put(
        uri: String,
        name: String?,
        mime: String?,
        content: ByteArray,
        reportedSize: Long? = content.size.toLong()
    ) {
        entries[uri] = Entry(SourceInfo(name, mime, reportedSize), content)
    }

    /** Describes fine but cannot be opened, like a grant revoked in between. */
    fun putUnopenable(uri: String, name: String = "x.bin") {
        entries[uri] = Entry(SourceInfo(name, null, 1), null)
    }

    override fun describe(uri: String): SourceInfo? = entries[uri]?.info

    override fun open(uri: String): InputStream? = entries[uri]?.content?.inputStream()
}

/** The English texts of strings.xml, in a fixed time zone. */
val ENGLISH_QUOTES = QuoteTemplates(
    attribution = "On %1\$s, %2\$s wrote:",
    forwardMarker = "---------- Forwarded message ---------",
    fromLabel = "From:",
    dateLabel = "Date:",
    subjectLabel = "Subject:",
    toLabel = "To:",
    ccLabel = "Cc:",
    locale = Locale.US,
    zone = ZoneOffset.UTC
)

/** The Spanish texts of strings.xml. */
val SPANISH_QUOTES = QuoteTemplates(
    attribution = "El %1\$s, %2\$s escribió:",
    forwardMarker = "---------- Mensaje reenviado ---------",
    fromLabel = "De:",
    dateLabel = "Fecha:",
    subjectLabel = "Asunto:",
    toLabel = "Para:",
    ccLabel = "Cc:",
    locale = Locale.forLanguageTag("es-ES"),
    zone = ZoneOffset.UTC
)
