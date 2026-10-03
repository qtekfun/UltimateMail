// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatabaseSchemaTest {
    private val schemas = File("schemas/${UltimateMailDatabase::class.qualifiedName}")

    @Test
    fun `every database version has its exported schema committed`() {
        (1..UltimateMailDatabase.VERSION).forEach { version ->
            assertTrue(File(schemas, "$version.json").isFile, "missing schema for version $version")
        }
    }

    @Test
    fun `the latest exported schema matches the database version`() {
        val latest = File(schemas, "${UltimateMailDatabase.VERSION}.json").readText()

        assertTrue(latest.contains("\"version\": ${UltimateMailDatabase.VERSION},"))
    }

    @Test
    fun `every version after the first is reached by a migration`() {
        val steps = UltimateMailDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()

        assertEquals((2..UltimateMailDatabase.VERSION).map { it - 1 to it }.toSet(), steps)
    }
}
