// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DraftResolverTest {
    private fun draft(base: String?, dirty: Boolean) = LocalDraft(1, "draft-1", base, dirty)

    @Test
    fun `edited here and untouched on the server is uploaded`() {
        assertEquals(
            DraftDecision.UploadLocal,
            DraftResolver.resolve(draft("v1", dirty = true), "v1")
        )
    }

    @Test
    fun `edited here and never uploaded is uploaded`() {
        assertEquals(
            DraftDecision.UploadLocal,
            DraftResolver.resolve(draft(null, dirty = true), null)
        )
    }

    @Test
    fun `edited here and deleted elsewhere is uploaded again`() {
        assertEquals(
            DraftDecision.UploadLocal,
            DraftResolver.resolve(draft("v1", dirty = true), null)
        )
    }

    @Test
    fun `edited on both sides keeps both versions and warns`() {
        assertEquals(
            DraftDecision.KeepBoth(SyncNotice.DraftConflict(1, "draft-1")),
            DraftResolver.resolve(draft("v1", dirty = true), "v2")
        )
    }

    @Test
    fun `a first upload racing another device's draft keeps both`() {
        assertEquals(
            DraftDecision.KeepBoth(SyncNotice.DraftConflict(1, "draft-1")),
            DraftResolver.resolve(draft(null, dirty = true), "v9")
        )
    }

    @Test
    fun `unedited here follows the server`() {
        assertEquals(
            DraftDecision.AcceptServer,
            DraftResolver.resolve(draft("v1", dirty = false), "v2")
        )
    }

    @Test
    fun `unedited here and deleted elsewhere is dropped`() {
        assertEquals(
            DraftDecision.DiscardLocal,
            DraftResolver.resolve(draft("v1", dirty = false), null)
        )
    }
}
