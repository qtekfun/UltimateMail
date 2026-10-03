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
}
