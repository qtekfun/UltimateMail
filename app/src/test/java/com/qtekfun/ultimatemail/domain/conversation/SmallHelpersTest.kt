// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentIdsTest {
    @Test
    fun `the header form and the html form normalize to the same id`() {
        assertEquals("img1@host", ContentIds.normalize("<Img1@Host>"))
        assertEquals("img1@host", ContentIds.normalize("cid:IMG1@host"))
        assertEquals("a b@host", ContentIds.normalize("cid:a%20b@host"))
    }

    @Test
    fun `a plus sign stays a plus sign`() {
        assertEquals("a+b@host", ContentIds.normalize("cid:a+b@host"))
    }

    @Test
    fun `nothing left means no id`() {
        assertNull(ContentIds.normalize("<>"))
        assertNull(ContentIds.normalize("cid:"))
    }

    @Test
    fun `the ids an html refers to are collected`() {
        val html = "<img src=\"cid:One@x\"><img src='cid:two@x'><a href=\"https://e.test\">x</a>"

        assertEquals(setOf("one@x", "two@x"), ContentIds.referencedIn(html))
    }
}

class AttachmentKindTest {
    @Test
    fun `the mime type decides first`() {
        assertEquals(AttachmentKind.IMAGE, AttachmentKind.of("image/png", "x.bin"))
        assertEquals(AttachmentKind.PDF, AttachmentKind.of("application/pdf", "x"))
        assertEquals(AttachmentKind.AUDIO, AttachmentKind.of("audio/mpeg", "x"))
        assertEquals(AttachmentKind.VIDEO, AttachmentKind.of("video/mp4", "x"))
        assertEquals(AttachmentKind.ARCHIVE, AttachmentKind.of("application/zip", "x"))
        assertEquals(AttachmentKind.TEXT, AttachmentKind.of("text/plain", "x"))
        assertEquals(
            AttachmentKind.SPREADSHEET,
            AttachmentKind.of(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "x"
            )
        )
        assertEquals(
            AttachmentKind.PRESENTATION,
            AttachmentKind.of("application/vnd.ms-powerpoint", "x")
        )
        assertEquals(AttachmentKind.DOCUMENT, AttachmentKind.of("application/msword", "x"))
    }

    @Test
    fun `a generic type falls back on the extension`() {
        assertEquals(
            AttachmentKind.PDF,
            AttachmentKind.of("application/octet-stream", "Report.PDF")
        )
        assertEquals(
            AttachmentKind.DOCUMENT,
            AttachmentKind.of("application/octet-stream", "a.docx")
        )
        assertEquals(AttachmentKind.IMAGE, AttachmentKind.of("application/octet-stream", "a.jpeg"))
        assertEquals(AttachmentKind.ARCHIVE, AttachmentKind.of("", "a.7z"))
        assertEquals(AttachmentKind.OTHER, AttachmentKind.of("application/octet-stream", "a.xyz"))
        assertEquals(AttachmentKind.OTHER, AttachmentKind.of("application/octet-stream", "noext"))
    }
}

class FileSizeFormatterTest {
    private val us = Locale.US

    @Test
    fun `sizes are written in the largest fitting unit`() {
        assertEquals("0 B", FileSizeFormatter.format(0, us))
        assertEquals("812 B", FileSizeFormatter.format(812, us))
        assertEquals("1.0 KB", FileSizeFormatter.format(1024, us))
        assertEquals("1.5 KB", FileSizeFormatter.format(1536, us))
        assertEquals("120 KB", FileSizeFormatter.format(120L * 1024, us))
        assertEquals("1.4 MB", FileSizeFormatter.format((1.4 * 1024 * 1024).toLong(), us))
        assertEquals("2.0 GB", FileSizeFormatter.format(2L * 1024 * 1024 * 1024, us))
    }

    @Test
    fun `the decimal separator follows the locale`() {
        assertEquals("1,5 KB", FileSizeFormatter.format(1536, Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun `a negative size reads as zero`() {
        assertEquals("0 B", FileSizeFormatter.format(-5, us))
    }
}

class AttachmentFileNamesTest {
    @Test
    fun `a plain name is kept`() {
        assertEquals("Report 2024.pdf", AttachmentFileNames.safe("Report 2024.pdf", "attachment"))
    }

    @Test
    fun `directory parts and special characters are removed`() {
        assertEquals(
            ".._.._etc_passwd".trimStart('.'),
            AttachmentFileNames.safe("../../etc/passwd", "f")
        )
        assertEquals("a_b_c.txt", AttachmentFileNames.safe("a:b*c.txt", "f"))
        assertEquals("x_y", AttachmentFileNames.safe("x\u0000y", "f"))
    }

    @Test
    fun `a leading dot is dropped so the file is not hidden`() {
        assertEquals("secret", AttachmentFileNames.safe(".secret", "f"))
    }

    @Test
    fun `nothing usable falls back`() {
        assertEquals("attachment", AttachmentFileNames.safe(null, "attachment"))
        assertEquals("attachment", AttachmentFileNames.safe("   ", "attachment"))
        assertEquals("attachment", AttachmentFileNames.safe("...", "attachment"))
    }

    @Test
    fun `a very long name is cut but keeps its extension`() {
        val result = AttachmentFileNames.safe("a".repeat(300) + ".pdf", "f")

        assertEquals(100, result.length)
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun `a very long name with a very long extension is still cut`() {
        val result = AttachmentFileNames.safe("a." + "b".repeat(300), "f")

        assertEquals(100, result.length)
    }
}

class RecipientSummaryTest {
    private val me = setOf("ana@example.test")

    @Test
    fun `only the owner is addressed`() {
        val summary = RecipientSummary.of(listOf("Ana@Example.test"), emptyList(), me)

        assertTrue(summary.onlyMe)
    }

    @Test
    fun `the owner and others`() {
        val summary = RecipientSummary.of(
            listOf("ana@example.test", "bob@example.test"),
            listOf("carol@example.test"),
            me
        )

        assertTrue(summary.includesMe)
        assertFalse(summary.onlyMe)
        assertEquals(listOf("bob@example.test", "carol@example.test"), summary.names)
        assertEquals(0, summary.hiddenCount)
    }

    @Test
    fun `others beyond the limit are counted`() {
        val summary = RecipientSummary.of(
            listOf("a@x.test", "b@x.test", "c@x.test", "d@x.test"),
            emptyList(),
            me
        )

        assertFalse(summary.includesMe)
        assertEquals(listOf("a@x.test", "b@x.test"), summary.names)
        assertEquals(2, summary.hiddenCount)
    }

    @Test
    fun `duplicates and blanks count once or not at all`() {
        val summary = RecipientSummary.of(
            listOf("bob@x.test", "BOB@x.test", " "),
            listOf("bob@x.test"),
            me
        )

        assertEquals(listOf("bob@x.test"), summary.names)
    }
}

class FolderTargetsTest {
    private fun folders() = listOf(
        FolderEntity(1, "INBOX", "INBOX", FolderRole.INBOX),
        FolderEntity(1, "Archive", "Archive", FolderRole.ARCHIVE),
        FolderEntity(1, "Trash", "Trash", FolderRole.TRASH),
        FolderEntity(1, "Work", "Work", FolderRole.OTHER)
    )

    @Test
    fun `from the inbox both actions are available`() {
        val targets = FolderTargets.resolve(folders(), "INBOX")

        assertEquals("Archive", targets.archivePath)
        assertEquals("Trash", targets.trashPath)
        assertTrue(targets.canArchive)
        assertTrue(targets.canDelete)
    }

    @Test
    fun `all mail is the archive of an account without an archive folder`() {
        val gmail = listOf(
            FolderEntity(1, "INBOX", "INBOX", FolderRole.INBOX),
            FolderEntity(1, "[Gmail]/All Mail", "All Mail", FolderRole.ALL_MAIL),
            FolderEntity(1, "[Gmail]/Trash", "Trash", FolderRole.TRASH)
        )

        assertEquals("[Gmail]/All Mail", FolderTargets.resolve(gmail, "INBOX").archivePath)
    }

    @Test
    fun `an account without the special folders cannot archive or delete`() {
        val targets = FolderTargets.resolve(
            listOf(FolderEntity(1, "INBOX", "INBOX", FolderRole.INBOX)),
            "INBOX"
        )

        assertFalse(targets.canArchive)
        assertFalse(targets.canDelete)
        assertNull(targets.archivePath)
        assertNull(targets.trashPath)
    }

    @Test
    fun `in the archive there is nothing to archive and in the trash nothing to delete`() {
        val inArchive = FolderTargets.resolve(folders(), "Archive")
        val inTrash = FolderTargets.resolve(folders(), "Trash")

        assertFalse(inArchive.canArchive)
        assertTrue(inArchive.canDelete)
        assertFalse(inTrash.canArchive)
        assertFalse(inTrash.canDelete)
    }
}
