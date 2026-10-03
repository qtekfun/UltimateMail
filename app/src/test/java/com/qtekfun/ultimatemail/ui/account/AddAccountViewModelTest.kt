// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import app.cash.turbine.test
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.account.AccountValidator
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.account.CreateAccountResult
import com.qtekfun.ultimatemail.domain.account.ServerAutodetector
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {
    private val setup = mockk<AccountSetup>()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val validator = AccountValidator()
    private val detector = ServerAutodetector()
    private lateinit var viewModel: AddAccountViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The real rules, so these tests also notice a change in what the domain accepts.
        every { setup.detectServers(any()) } answers { detector.detect(firstArg()) }
        every { setup.validate(any()) } answers { validator.validate(firstArg()) }
        viewModel = AddAccountViewModel(setup, scheduler)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun fillValidForm() {
        viewModel.onTextChange(FormInput.EMAIL, "ana@example.test")
        viewModel.onTextChange(FormInput.PASSWORD, "app-password")
    }

    @Test
    fun `typing an address fills the servers from the autodetection`() {
        viewModel.onTextChange(FormInput.EMAIL, "ana@example.test")

        val state = viewModel.state.value
        assertEquals("imap.example.test", state.imapHost)
        assertEquals("993", state.imapPort)
        assertEquals(ConnectionSecurity.TLS, state.imapSecurity)
        assertEquals("smtp.example.test", state.smtpHost)
        assertEquals("587", state.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, state.smtpSecurity)
        assertEquals(ProviderHint.NONE, state.hint)
    }

    @Test
    fun `Gmail and Microsoft addresses show their hint but stay password accounts`() {
        viewModel.onTextChange(FormInput.EMAIL, "ana@gmail.com")
        assertEquals(ProviderHint.GMAIL, viewModel.state.value.hint)
        assertEquals("imap.gmail.com", viewModel.state.value.imapHost)
        assertEquals(AuthType.PASSWORD, viewModel.state.value.authType)

        viewModel.onTextChange(FormInput.EMAIL, "ana@outlook.com")
        assertEquals(ProviderHint.MICROSOFT, viewModel.state.value.hint)
        assertEquals(AuthType.PASSWORD, viewModel.state.value.authType)

        viewModel.onTextChange(FormInput.EMAIL, "ana@")
        assertEquals(ProviderHint.NONE, viewModel.state.value.hint)
    }

    @Test
    fun `servers edited by hand are not overwritten by autodetection`() {
        viewModel.onTextChange(FormInput.EMAIL, "ana@example.test")
        viewModel.onTextChange(FormInput.IMAP_HOST, "mail.custom.test")

        viewModel.onTextChange(FormInput.EMAIL, "ana@other.test")

        assertEquals("mail.custom.test", viewModel.state.value.imapHost)
        assertTrue(viewModel.state.value.serversEdited)
    }

    @Test
    fun `server fields and security can be edited`() {
        viewModel.onTextChange(FormInput.IMAP_PORT, "143")
        viewModel.onTextChange(FormInput.SMTP_HOST, "smtp.custom.test")
        viewModel.onTextChange(FormInput.SMTP_PORT, "465")
        viewModel.onSecurityChange(AccountInputError.Server.IMAP, ConnectionSecurity.STARTTLS)
        viewModel.onSecurityChange(AccountInputError.Server.SMTP, ConnectionSecurity.TLS)

        val state = viewModel.state.value
        assertEquals("143", state.imapPort)
        assertEquals("smtp.custom.test", state.smtpHost)
        assertEquals("465", state.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, state.imapSecurity)
        assertEquals(ConnectionSecurity.TLS, state.smtpSecurity)
    }

    @Test
    fun `an auth type that is not offered is ignored`() {
        viewModel.onAuthTypeChange(AuthType.OAUTH_GOOGLE)

        assertEquals(AuthType.PASSWORD, viewModel.state.value.authType)
        assertEquals(listOf(AuthType.PASSWORD), viewModel.state.value.availableAuthTypes)
    }

    @Test
    fun `the advanced section toggles`() {
        viewModel.onAdvancedToggle()
        assertTrue(viewModel.state.value.advancedExpanded)
        viewModel.onAdvancedToggle()
        assertFalse(viewModel.state.value.advancedExpanded)
    }

    @Test
    fun `submitting an empty form shows the field errors and tests nothing`() {
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals(
            R.string.error_email_invalid,
            state.errorFor(FormField.EMAIL)?.message
        )
        assertEquals(R.string.error_password_missing, state.errorFor(FormField.PASSWORD)?.message)
        assertEquals(R.string.error_host_invalid, state.errorFor(FormField.IMAP_HOST)?.message)
        // A wrong server field opens the advanced section so the error can be seen.
        assertTrue(state.advancedExpanded)
        assertEquals(AddAccountProgress.IDLE, state.progress)
        coVerify(exactly = 0) { setup.testConnection(any()) }
    }

    @Test
    fun `a valid form tests the connection with the password and creates the account`() = runTest {
        val tested = slot<AccountInput>()
        coEvery { setup.testConnection(capture(tested)) } returns ConnectionTestResult.Success
        coEvery { setup.create(any()) } returns CreateAccountResult.Created(7)
        fillValidForm()

        viewModel.events.test {
            viewModel.submit()
            assertEquals(AddAccountEvent.Created(7), awaitItem())
            verify { scheduler.requestSync(7L, userInitiated = true) }
        }

        assertEquals("ana@example.test", tested.captured.username)
        assertEquals("app-password", tested.captured.credentials.password)
        assertEquals(AddAccountState(), viewModel.state.value)
    }

    @Test
    fun `the username falls back to the address and uses the advanced one when set`() {
        fillValidForm()
        assertEquals("ana@example.test", viewModel.state.value.toInput().username)

        viewModel.onTextChange(FormInput.USERNAME, " ana ")
        assertEquals("ana", viewModel.state.value.toInput().username)
    }

    @Test
    fun `an unparseable port is rejected by the validation`() {
        fillValidForm()
        viewModel.onTextChange(FormInput.IMAP_PORT, "abc")

        viewModel.submit()

        assertEquals(
            R.string.error_port_invalid,
            viewModel.state.value.errorFor(FormField.IMAP_PORT)?.message
        )
    }

    @Test
    fun `progress shows while the connection is tested`() = runTest {
        val gate = CompletableDeferred<ConnectionTestResult>()
        coEvery { setup.testConnection(any()) } coAnswers { gate.await() }
        fillValidForm()

        viewModel.submit()
        assertEquals(AddAccountProgress.TESTING, viewModel.state.value.progress)
        assertTrue(viewModel.state.value.busy)

        // A second tap while busy does not start another test.
        viewModel.submit()
        coVerify(exactly = 1) { setup.testConnection(any()) }

        gate.complete(ConnectionTestResult.Failure(ConnectionFailure.TIMEOUT))
        assertEquals(AddAccountProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `cancelling stops the test and keeps the form`() = runTest {
        val gate = CompletableDeferred<ConnectionTestResult>()
        coEvery { setup.testConnection(any()) } coAnswers { gate.await() }
        fillValidForm()
        viewModel.submit()

        viewModel.cancel()

        assertEquals(AddAccountProgress.IDLE, viewModel.state.value.progress)
        assertEquals("ana@example.test", viewModel.state.value.email)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `each connection failure becomes its own failure state`() = runTest {
        fillValidForm()
        for (reason in ConnectionFailure.entries) {
            coEvery { setup.testConnection(any()) } returns ConnectionTestResult.Failure(reason)

            viewModel.submit()

            assertEquals(AddAccountFailure.Connection(reason), viewModel.state.value.failure)
            assertEquals(AddAccountProgress.IDLE, viewModel.state.value.progress)
        }
    }

    @Test
    fun `typing clears the failure of the last attempt`() = runTest {
        coEvery { setup.testConnection(any()) } returns
            ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED)
        fillValidForm()
        viewModel.submit()

        viewModel.onTextChange(FormInput.PASSWORD, "other")

        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `no connection tester is reported as such and nothing is created`() = runTest {
        coEvery { setup.testConnection(any()) } returns ConnectionTestResult.NotAvailable
        fillValidForm()

        viewModel.submit()

        assertEquals(AddAccountFailure.TestUnavailable, viewModel.state.value.failure)
        coVerify(exactly = 0) { setup.create(any()) }
    }

    @Test
    fun `a storage failure is reported`() = runTest {
        coEvery { setup.testConnection(any()) } returns ConnectionTestResult.Success
        coEvery { setup.create(any()) } returns CreateAccountResult.StorageFailed
        fillValidForm()

        viewModel.submit()

        assertEquals(AddAccountFailure.StorageFailed, viewModel.state.value.failure)
        assertEquals(AddAccountProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `a duplicate account is shown as a form error`() = runTest {
        coEvery { setup.testConnection(any()) } returns ConnectionTestResult.Success
        coEvery { setup.create(any()) } returns
            CreateAccountResult.Invalid(listOf(AccountInputError.DuplicateAccount))
        fillValidForm()

        viewModel.submit()

        assertEquals(
            R.string.error_account_duplicate,
            viewModel.state.value.errorFor(FormField.GENERAL)?.message
        )
        assertEquals(AddAccountProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `reset returns to an empty form`() {
        fillValidForm()

        viewModel.reset()

        assertEquals(AddAccountState(), viewModel.state.value)
    }
}
