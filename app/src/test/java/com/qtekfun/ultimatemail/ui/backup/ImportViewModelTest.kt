// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.backup.BackupError
import com.qtekfun.ultimatemail.domain.backup.BackupImporter
import com.qtekfun.ultimatemail.domain.backup.BackupPreview
import com.qtekfun.ultimatemail.domain.backup.ImportSummary
import com.qtekfun.ultimatemail.domain.backup.ImportedAccount
import com.qtekfun.ultimatemail.domain.backup.OpenResult
import com.qtekfun.ultimatemail.domain.backup.PreviewEntry
import com.qtekfun.ultimatemail.domain.backup.PreviewStatus
import com.qtekfun.ultimatemail.domain.backup.backupDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val importer = mockk<BackupImporter>()
    private lateinit var viewModel: ImportViewModel

    private fun entry(index: Int, status: PreviewStatus = PreviewStatus.IMPORTABLE) =
        PreviewEntry(index, "user$index@example.test", "User", AuthType.PASSWORD, false, status)

    private val preview = BackupPreview(
        entries = listOf(
            entry(0),
            entry(1, PreviewStatus.DUPLICATE),
            entry(2),
            entry(3, PreviewStatus.INVALID)
        ),
        hasSettings = true,
        document = backupDocument()
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = ImportViewModel(importer)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun opened(): ImportViewModel {
        coEvery { importer.open("content://file", any()) } returns OpenResult.Opened(preview)
        viewModel.onFileChosen("content://file")
        viewModel.open("pass".toCharArray())
        return viewModel
    }

    @Test
    fun `it starts by asking for a file`() {
        assertEquals(ImportStage.PICK, viewModel.state.value.stage)
    }

    @Test
    fun `closing the picker stays on the first step`() {
        viewModel.onFileChosen(null)

        assertEquals(ImportStage.PICK, viewModel.state.value.stage)
    }

    @Test
    fun `choosing a file asks for the passphrase`() {
        viewModel.onFileChosen("content://file")

        assertEquals(ImportStage.PASSPHRASE, viewModel.state.value.stage)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `opening without a chosen file only wipes the passphrase`() {
        val pass = "pass".toCharArray()

        viewModel.open(pass)

        assertEquals(ImportStage.PICK, viewModel.state.value.stage)
        assertEquals(listOf('\u0000', '\u0000', '\u0000', '\u0000'), pass.toList())
    }

    @Test
    fun `a good file shows the preview with the importable accounts ticked`() {
        opened()

        val state = viewModel.state.value
        assertEquals(ImportStage.PREVIEW, state.stage)
        assertEquals(setOf(0, 2), state.selected)
        assertEquals(false, state.importSettings)
    }

    @Test
    fun `a failure goes back to the passphrase step with the reason`() {
        coEvery { importer.open(any(), any()) } returns
            OpenResult.Failed(BackupError.WrongPassphraseOrDamaged)
        viewModel.onFileChosen("content://file")

        viewModel.open("wrong".toCharArray())

        assertEquals(ImportStage.PASSPHRASE, viewModel.state.value.stage)
        assertEquals(BackupError.WrongPassphraseOrDamaged, viewModel.state.value.error)
    }

    @Test
    fun `trying again clears the error`() {
        coEvery { importer.open(any(), any()) } returns OpenResult.Failed(BackupError.Truncated)
        viewModel.onFileChosen("content://file")
        viewModel.open("x".toCharArray())

        viewModel.onFileChosen("content://file")

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `ticking toggles importable accounts only`() {
        opened()

        viewModel.toggle(0)
        assertEquals(setOf(2), viewModel.state.value.selected)
        viewModel.toggle(0)
        assertEquals(setOf(0, 2), viewModel.state.value.selected)
        viewModel.toggle(1)
        viewModel.toggle(3)
        viewModel.toggle(99)
        assertEquals(setOf(0, 2), viewModel.state.value.selected)
    }

    @Test
    fun `importing sends the ticked accounts and the settings choice`() {
        val summary = ImportSummary(
            listOf(ImportedAccount(7, "user0@example.test", needsSignIn = true)),
            skipped = 1,
            failed = 0,
            settingsApplied = true
        )
        coEvery { importer.import(any(), any(), any()) } returns summary
        opened()
        viewModel.toggle(2)
        viewModel.onImportSettingsChange(true)

        viewModel.import()

        coVerify { importer.import(preview, setOf(0), true) }
        val state = viewModel.state.value
        assertEquals(ImportStage.DONE, state.stage)
        assertEquals(summary, state.summary)
        assertNull(state.preview)
    }

    @Test
    fun `importing is only possible from the preview`() {
        viewModel.import()

        assertEquals(ImportStage.PICK, viewModel.state.value.stage)
        coVerify(exactly = 0) { importer.import(any(), any(), any()) }
    }

    @Test
    fun `resetting forgets the file and what was read`() {
        opened()

        viewModel.reset()

        assertEquals(ImportState(), viewModel.state.value)
        viewModel.open("pass".toCharArray())
        assertEquals(ImportStage.PICK, viewModel.state.value.stage)
    }
}
