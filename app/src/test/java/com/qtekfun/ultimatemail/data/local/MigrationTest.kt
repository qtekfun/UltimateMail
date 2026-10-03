// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationTest {
    private val schemas = File("schemas/${UltimateMailDatabase::class.qualifiedName}")

    /** The CREATE TABLE of the message entity as exported for [version]. */
    private fun messageTableSql(version: Int): String {
        val line = File(schemas, "$version.json").readLines().first {
            "\"createSql\"" in it && "`gmailMessageId`" in it
        }
        return line.substringAfter("\"createSql\": \"").substringBeforeLast("\"")
            .replace("\${TABLE_NAME}", "message")
    }

    private fun columns(connection: SQLiteConnection): Map<String, String> =
        connection.prepare("PRAGMA table_info(message)").use { statement ->
            buildMap {
                while (statement.step()) {
                    // name -> "type|notnull|default"
                    put(
                        statement.getText(1),
                        "${statement.getText(2)}|${statement.getLong(3)}|" +
                            if (statement.isNull(4)) "" else statement.getText(4)
                    )
                }
            }
        }

    private fun declared(sql: String) =
        Regex("`(\\w+)` (?:INTEGER|TEXT)").findAll(sql.substringBefore("FOREIGN KEY"))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `migrating 1 to 2 adds the reply headers and keeps the stored messages`() = runTest {
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.use {
            connection.execSQL(messageTableSql(1))
            connection.execSQL(
                "INSERT INTO message (accountId, folderPath, uid, threadId, subject, senderName, " +
                    "senderAddress, toAddresses, ccAddresses, sentAt, snippet, seen, flagged, " +
                    "answered, " +
                    "draft, hasAttachments, size, labels, pendingSync) VALUES " +
                    "(1, 'INBOX', 7, 't', 's', 'n', 'a', '', '', 0, '', 0, 0, 0, 0, 0, 0, '', 0)"
            )

            MIGRATION_1_2.migrate(connection)

            val after = columns(connection)
            assertEquals(declared(messageTableSql(2)), after.keys)
            assertEquals("TEXT|0|", after.getValue("inReplyTo"))
            assertEquals("TEXT|1|''", after.getValue("referenceIds"))
            connection.prepare("SELECT uid, inReplyTo, referenceIds FROM message").use {
                assertTrue(it.step())
                assertEquals(7L, it.getLong(0))
                assertTrue(it.isNull(1))
                assertEquals("", it.getText(2))
            }
        }
    }

    private fun attachmentTableSql(version: Int): String {
        val line = File(schemas, "$version.json").readLines().first {
            "\"createSql\"" in it && "`partId`" in it
        }
        return line.substringAfter("\"createSql\": \"").substringBeforeLast("\"")
            .replace("\${TABLE_NAME}", "attachment")
    }

    private fun attachmentColumns(connection: SQLiteConnection): Map<String, String> =
        connection.prepare("PRAGMA table_info(attachment)").use { statement ->
            buildMap {
                while (statement.step()) {
                    put(
                        statement.getText(1),
                        "${statement.getText(2)}|${statement.getLong(3)}|" +
                            if (statement.isNull(4)) "" else statement.getText(4)
                    )
                }
            }
        }

    @Test
    fun `migrating 2 to 3 adds the content id and keeps the stored attachments`() = runTest {
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.use {
            connection.execSQL(attachmentTableSql(2))
            connection.execSQL(
                "INSERT INTO attachment (messageId, partId, fileName, mimeType, size, state) " +
                    "VALUES (4, '2', 'a.pdf', 'application/pdf', 10, 'REMOTE')"
            )

            MIGRATION_2_3.migrate(connection)

            val after = attachmentColumns(connection)
            val declared = Regex("`(\\w+)` (?:INTEGER|TEXT)")
                .findAll(attachmentTableSql(3).substringBefore("FOREIGN KEY"))
                .map { it.groupValues[1] }.toSet()
            assertEquals(declared, after.keys)
            assertEquals("TEXT|0|", after.getValue("contentId"))
            assertEquals("INTEGER|1|0", after.getValue("inline"))
            connection.prepare("SELECT fileName, contentId, inline FROM attachment").use {
                assertTrue(it.step())
                assertEquals("a.pdf", it.getText(0))
                assertTrue(it.isNull(1))
                assertEquals(0L, it.getLong(2))
            }
        }
    }

    private fun tableOf(sql: String) =
        if ("`kind`" in sql || "index_draft_" in sql) "draft" else "outgoing_attachment"

    /** The CREATE statements of the draft tables in the exported schema of [version]. */
    private fun draftTableStatements(version: Int): List<String> {
        fun isDraftTable(line: String) =
            "`kind`" in line || "`draftId` INTEGER NOT NULL, `displayName`" in line
        fun isDraftIndex(line: String) =
            "index_draft_" in line || "index_outgoing_attachment_" in line
        return File(schemas, "$version.json").readLines()
            .filter { "\"createSql\"" in it && (isDraftTable(it) || isDraftIndex(it)) }
            .map { line ->
                val sql = line.substringAfter("\"createSql\": \"").substringBeforeLast("\"")
                val table = tableOf(sql)
                sql.replace("\${TABLE_NAME}", table)
            }
    }

    private fun rows(connection: SQLiteConnection, sql: String, columns: List<Int>): List<String> =
        connection.prepare(sql).use { statement ->
            buildList {
                while (statement.step()) {
                    add(columns.joinToString("|") { statement.getText(it) })
                }
            }
        }

    /** Columns, indices and foreign keys of [table], in a form two databases can be compared by. */
    private fun describe(connection: SQLiteConnection, table: String): List<String> =
        rows(connection, "PRAGMA table_info($table)", listOf(1, 2, 3)) +
            rows(connection, "PRAGMA index_list($table)", listOf(1, 2)).sorted() +
            rows(connection, "PRAGMA foreign_key_list($table)", listOf(2, 3, 4, 6))

    @Test
    fun `migrating 3 to 4 creates the draft tables exactly as the exported schema`() = runTest {
        val migrated = BundledSQLiteDriver().open(":memory:")
        val expected = BundledSQLiteDriver().open(":memory:")
        migrated.use {
            expected.use {
                val statements = draftTableStatements(4)
                assertEquals(5, statements.size)
                statements.forEach { expected.execSQL(it) }

                MIGRATION_3_4.migrate(migrated)

                listOf("draft", "outgoing_attachment").forEach {
                    assertEquals(describe(expected, it), describe(migrated, it))
                }
            }
        }
    }

    @Test
    fun `after migrating 3 to 4 removing a draft removes its attachments`() = runTest {
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.use {
            connection.execSQL("CREATE TABLE account (id INTEGER PRIMARY KEY AUTOINCREMENT)")
            connection.execSQL("INSERT INTO account DEFAULT VALUES")
            MIGRATION_3_4.migrate(connection)
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL(
                "INSERT INTO draft (`key`, accountId, kind, toAddresses, ccAddresses, " +
                    "bccAddresses, subject, body, createdAt, updatedAt) " +
                    "VALUES ('k', 1, 'NEW', '', '', '', '', '', 0, 0)"
            )
            connection.execSQL(
                "INSERT INTO outgoing_attachment " +
                    "(draftId, displayName, mimeType, size, filePath) " +
                    "VALUES (1, 'a.pdf', 'application/pdf', 3, '/x/a.pdf')"
            )

            connection.execSQL("DELETE FROM draft")

            connection.prepare("SELECT COUNT(*) FROM outgoing_attachment").use {
                assertTrue(it.step())
                assertEquals(0L, it.getLong(0))
            }
        }
    }
}
