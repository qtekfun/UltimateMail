// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

/** In-memory [SyncDepthLog] that the tests can look into. */
class FakeSyncDepthLog : SyncDepthLog {
    val values = mutableMapOf<Long, Int>()

    override fun get(accountId: Long): Int = values[accountId] ?: 0

    override fun put(accountId: Long, days: Int) {
        values[accountId] = days
    }
}
