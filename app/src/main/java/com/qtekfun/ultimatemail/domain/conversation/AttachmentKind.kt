// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import java.util.Locale

/** What an attachment is, for its icon and its description for screen readers. */
enum class AttachmentKind {
    IMAGE,
    PDF,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    TEXT,
    ARCHIVE,
    AUDIO,
    VIDEO,
    OTHER;

    companion object {
        private val documents = setOf("doc", "docx", "odt", "rtf", "pages")
        private val spreadsheets = setOf("xls", "xlsx", "ods", "csv", "numbers")
        private val presentations = setOf("ppt", "pptx", "odp", "key")
        private val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
        private val audio = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")
        private val video = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp")
        private val texts = setOf("txt", "md", "log")
        private val archiveTypes = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip"
        )

        /**
         * The kind of an attachment from its MIME type, falling back on the file extension when
         * the sender used a generic type such as `application/octet-stream`.
         */
        fun of(mimeType: String, fileName: String): AttachmentKind {
            val type = mimeType.trim().lowercase(Locale.ROOT)
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return byType(type) ?: byExtension(extension) ?: OTHER
        }

        private fun byType(type: String): AttachmentKind? = when {
            type.startsWith("image/") -> IMAGE
            type.startsWith("audio/") -> AUDIO
            type.startsWith("video/") -> VIDEO
            type == "application/pdf" -> PDF
            type in archiveTypes -> ARCHIVE
            type.contains("spreadsheet") || type.contains("ms-excel") -> SPREADSHEET
            type.contains("presentation") || type.contains("ms-powerpoint") -> PRESENTATION
            type.contains("wordprocessing") || type == "application/msword" -> DOCUMENT
            type.startsWith("text/") -> TEXT
            else -> null
        }

        private fun byExtension(extension: String): AttachmentKind? = when {
            extension == "pdf" -> PDF
            extension in documents -> DOCUMENT
            extension in spreadsheets -> SPREADSHEET
            extension in presentations -> PRESENTATION
            extension in archives -> ARCHIVE
            extension in texts -> TEXT
            extension in images -> IMAGE
            extension in audio -> AUDIO
            extension in video -> VIDEO
            else -> null
        }
    }
}
