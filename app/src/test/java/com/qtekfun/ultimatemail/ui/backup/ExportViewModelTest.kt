// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import com.qtekfun.ultimatemail.domain.backup.BackupExporter
import com.qtekfun.ultimatemail.domain.backup.ExportResult
import com.qtekfun.ultimatemail.domain.backup.PassphraseIssue
import com.qtekfun.ultimatemail.domain.backup.PassphraseStrength
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val exporter = mockk<BackupExporter>()
    private lateinit var viewModel: ExportViewModel

    /** The passphrases the exporter received, as text, and whether credentials were asked. */
    private val received = mutableListOf<Pair<String, Boolean>>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = ExportViewModel(exporter)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun exporterReturns(result: ExportResult) {
        coEvery { exporter.export(any(), any(), any(), any()) } answers {
            received += String(secondArg<CharArray>()) to thirdArg<Boolean>()
            result
        }
    }

    private fun chars(text: String) = text.toCharArray()

    @Test
    fun `typing shows the strength and wipes what it was given`() {
        val typed = chars("Abcdefgh1jkl")

        viewModel.onPassphraseChange(typed)

        assertEquals(PassphraseStrength.STRONG, viewModel.state.value.strength)
        assertTrue(typed.all { it == '\u0000' })
    }

    @Test
    fun `an empty form is refused`() {
        viewModel.submit(chars(""), chars(""))

        assertEquals(PassphraseIssue.EMPTY, viewModel.state.value.issue)
        assertEquals(ExportStage.FORM, viewModel.state.value.stage)
    }

    @Test
    fun `a mismatch is refused and both arrays are wiped`() {
        val pass = chars("one-passphrase")
        val confirm = chars("two-passphrase")

        viewModel.submit(pass, confirm)

        assertEquals(PassphraseIssue.MISMATCH, viewModel.state.value.issue)
        assertTrue(pass.all { it == '\u0000' })
        assertTrue(confirm.all { it == '\u0000' })
    }

    @Test
    fun `credentials need a longer passphrase`() {
        viewModel.onIncludeCredentialsChange(true)

        viewModel.submit(chars("short"), chars("short"))

        assertEquals(PassphraseIssue.TOO_SHORT, viewModel.state.value.issue)
        assertTrue(viewModel.state.value.includeCredentials)
    }

    @Test
    fun `changing the form clears the previous complaint`() {
        viewModel.submit(chars(""), chars(""))

        viewModel.onIncludeCredentialsChange(true)

        assertEquals(null, viewModel.state.value.issue)
    }

    @Test
    fun `a good form asks for a location and then exports with the passphrase`() {
        exporterReturns(ExportResult.Done(2, credentialsIncluded = true))
        viewModel.onIncludeCredentialsChange(true)

        viewModel.submit(chars("correct horse"), chars("correct horse"))
        assertEquals(ExportStage.CHOOSING_LOCATION, viewModel.state.value.stage)

        viewModel.onLocationChosen("content://docs/1")

        assertEquals(listOf("correct horse" to true), received)
        val state = viewModel.state.value
        assertEquals(ExportStage.DONE, state.stage)
        assertEquals(2, state.exportedAccounts)
        assertTrue(state.credentialsIncluded)
        coVerify { exporter.export("content://docs/1", any(), true, any()) }
    }

    @Test
    fun `closing the file creator goes back to the form and wipes the passphrase`() {
        val pass = chars("correct horse")
        viewModel.submit(pass, chars("correct horse"))

        viewModel.onLocationChosen(null)

        assertEquals(ExportStage.FORM, viewModel.state.value.stage)
        assertTrue(pass.all { it == '\u0000' })
        coVerify(exactly = 0) { exporter.export(any(), any(), any(), any()) }
    }

    @Test
    fun `a location without a form accepted first is ignored`() {
        viewModel.onLocationChosen("content://docs/1")

        assertEquals(ExportStage.FORM, viewModel.state.value.stage)
        coVerify(exactly = 0) { exporter.export(any(), any(), any(), any()) }
    }

    @Test
    fun `a file that cannot be written is a failure that can be retried`() {
        exporterReturns(ExportResult.WriteFailed)
        viewModel.submit(chars("correct horse"), chars("correct horse"))

        viewModel.onLocationChosen("content://docs/1")
        assertEquals(ExportStage.FAILED, viewModel.state.value.stage)

        viewModel.reset()
        assertEquals(ExportState(), viewModel.state.value)
    }

    @Test
    fun `a passphrase the exporter rejects returns to the form with the reason`() {
        exporterReturns(ExportResult.BadPassphrase(PassphraseIssue.TOO_SHORT))
        viewModel.submit(chars("correct horse"), chars("correct horse"))

        viewModel.onLocationChosen("content://docs/1")

        assertEquals(ExportStage.FORM, viewModel.state.value.stage)
        assertEquals(PassphraseIssue.TOO_SHORT, viewModel.state.value.issue)
    }

    @Test
    fun `resetting wipes a passphrase that is still held`() {
        val pass = chars("correct horse")
        viewModel.submit(pass, chars("correct horse"))

        viewModel.reset()

        assertTrue(pass.all { it == '\u0000' })
        assertFalse(viewModel.state.value.stage == ExportStage.CHOOSING_LOCATION)
    }
}
