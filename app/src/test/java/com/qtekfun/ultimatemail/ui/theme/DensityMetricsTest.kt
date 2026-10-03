// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DensityMetricsTest {
    private val comfortable = DisplayDensity.COMFORTABLE.metrics()
    private val default = DisplayDensity.DEFAULT.metrics()
    private val compact = DisplayDensity.COMPACT.metrics()

    @Test
    fun `every size shrinks as the density goes from comfortable to compact`() {
        assertTrue(comfortable.drawerRowHeight > default.drawerRowHeight)
        assertTrue(default.drawerRowHeight > compact.drawerRowHeight)
        assertTrue(comfortable.listRowVerticalPadding > default.listRowVerticalPadding)
        assertTrue(default.listRowVerticalPadding > compact.listRowVerticalPadding)
        assertTrue(comfortable.listRowMinHeight > default.listRowMinHeight)
        assertTrue(default.listRowMinHeight > compact.listRowMinHeight)
    }

    @Test
    fun `the comfortable and default levels keep the 48dp touch target`() {
        assertTrue(comfortable.drawerRowHeight >= 48.dp)
        assertTrue(default.drawerRowHeight >= 48.dp)
    }

    @Test
    fun `the default is tighter than the old fixed 56dp menu rows`() {
        assertEquals(DisplayDensity.DEFAULT, AppSettings().density)
        assertTrue(default.drawerRowHeight < 56.dp)
    }
}
