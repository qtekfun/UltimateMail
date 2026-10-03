// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.mockk.every
import io.mockk.mockk

/** In-memory database on the host JVM, using the bundled SQLite build for JVM. */
fun inMemoryDatabase(): UltimateMailDatabase {
    val context = mockk<Context>(relaxed = true)
    every { context.applicationContext } returns context
    return Room.inMemoryDatabaseBuilder<UltimateMailDatabase>(context)
        .setDriver(BundledSQLiteDriver())
        .build()
}
