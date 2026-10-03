// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.account.ReauthResult
import com.qtekfun.ultimatemail.domain.account.ReauthTarget
import com.qtekfun.ultimatemail.domain.account.Reauthenticate
import com.qtekfun.ultimatemail.domain.oauth.MemoryClientIds
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult
import com.qtekfun.ultimatemail.domain.oauth.OAuthConfigs
import com.qtekfun.ultimatemail.domain.oauth.OAuthSignIn
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReauthViewModelTest {
    private val reauth = mockk<Reauthenticate>()
    private val clientIds = MemoryClientIds()
    private val guid = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"
    private val tokens = OAuthTokens("access", "refresh", null)

    private fun newViewModel(saved: SavedStateHandle = SavedStateHandle()) = ReauthViewModel(
        reauth,
        OAuthSignIn(clientIds, OAuthConfigs(clientIds, "com.example.mail", "")),
        saved
    )

    private fun target(authType: AuthType = AuthType.PASSWORD, host: String = "imap.example.test") =
        ReauthTarget(7, "ana@example.test", authType, host)

    private fun idToken(claims: String): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.toByteArray())
        return "h.$payload.s"
    }

    private fun success(address: String = "ana@example.test") = OAuthBrowserResult.Success(
        tokens,
        idToken("""{"email":"$address"}""")
    )

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun shown(
        target: ReauthTarget? = target(),
        saved: SavedStateHandle = SavedStateHandle()
    ): ReauthViewModel {
        coEvery { reauth.target(7) } returns target
        return newViewModel(saved).also { it.show(7) }
    }

    @Test
    fun `before the account is shown nothing is loaded`() {
        val state = newViewModel().state.value

        assertFalse(state.loaded)
        assertNull(state.target)
    }

    @Test
    fun `an existing account is shown with its address and server`() {
        val viewModel = shown()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertEquals(target(), state.target)
        assertNull(state.oauthType)
        assertFalse(state.gmailAppPasswordHint)
    }

    @Test
    fun `an account that does not exist is loaded as not found`() {
        val state = shown(target = null).state.value

        assertTrue(state.loaded)
        assertNull(state.target)
    }

    @Test
    fun `a password account at Gmail gets the app password hint`() {
        val state = shown(target(host = "imap.gmail.com")).state.value

        assertTrue(state.gmailAppPasswordHint)
    }

    @Test
    fun `an empty password is not sent anywhere`() {
        val viewModel = shown()

        viewModel.submitPassword()

        assertEquals(ReauthFailure.MissingPassword, viewModel.state.value.failure)
        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        coVerify(exactly = 0) { reauth.withPassword(any(), any()) }
    }

    @Test
    fun `a good password signs in, reports it and forgets the typed password`() = runTest {
        coEvery { reauth.withPassword(7, "new-pw") } returns ReauthResult.Success
        val viewModel = shown()
        viewModel.onPasswordChange("new-pw")

        viewModel.events.test {
            viewModel.submitPassword()

            assertEquals(ReauthEvent.SignedIn, awaitItem())
        }
        assertEquals("", viewModel.state.value.password)
        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `testing shows progress and cancel stops it with the form intact`() = runTest {
        val gate = CompletableDeferred<ReauthResult>()
        coEvery { reauth.withPassword(7, "pw") } coAnswers { gate.await() }
        val viewModel = shown()
        viewModel.onPasswordChange("pw")

        viewModel.submitPassword()
        assertEquals(ReauthProgress.TESTING, viewModel.state.value.progress)
        assertTrue(viewModel.state.value.busy)

        viewModel.cancel()

        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        assertEquals("pw", viewModel.state.value.password)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `a second submit while testing is ignored`() = runTest {
        val gate = CompletableDeferred<ReauthResult>()
        coEvery { reauth.withPassword(7, "pw") } coAnswers { gate.await() }
        val viewModel = shown()
        viewModel.onPasswordChange("pw")

        viewModel.submitPassword()
        viewModel.submitPassword()

        coVerify(exactly = 1) { reauth.withPassword(7, "pw") }
    }

    @Test
    fun `each connection failure shows the same message as adding an account`() = runTest {
        val expected = mapOf(
            ConnectionFailure.AUTHENTICATION_FAILED to R.string.error_connection_auth,
            ConnectionFailure.HOST_UNREACHABLE to R.string.error_connection_unreachable,
            ConnectionFailure.TLS_ERROR to R.string.error_connection_tls,
            ConnectionFailure.TIMEOUT to R.string.error_connection_timeout,
            ConnectionFailure.UNKNOWN to R.string.error_connection_unknown
        )
        val viewModel = shown()
        viewModel.onPasswordChange("pw")

        expected.forEach { (reason, message) ->
            coEvery { reauth.withPassword(7, "pw") } returns ReauthResult.Failure(reason)
            viewModel.submitPassword()

            assertEquals(ReauthFailure.Connection(reason), viewModel.state.value.failure)
            assertEquals(message, viewModel.state.value.failure?.toMessage(), "$reason")
            assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        }
    }

    @Test
    fun `storage trouble and a missing tester are reported, and typing clears them`() = runTest {
        val viewModel = shown()
        viewModel.onPasswordChange("pw")

        coEvery { reauth.withPassword(7, "pw") } returns ReauthResult.StorageFailed
        viewModel.submitPassword()
        assertEquals(ReauthFailure.StorageFailed, viewModel.state.value.failure)

        coEvery { reauth.withPassword(7, "pw") } returns ReauthResult.TestUnavailable
        viewModel.submitPassword()
        assertEquals(ReauthFailure.TestUnavailable, viewModel.state.value.failure)

        viewModel.onPasswordChange("pw2")
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `an OAuth account asks for the client ID before opening the browser`() {
        val viewModel = shown(target(AuthType.OAUTH_MICROSOFT, "outlook.office365.com"))
        assertEquals(AuthType.OAUTH_MICROSOFT, viewModel.state.value.oauthType)

        viewModel.onSignInClick()
        assertEquals(R.string.error_client_id_missing, viewModel.state.value.clientIdError)
        assertNull(viewModel.state.value.oauthRequest)

        viewModel.onClientIdChange("not a guid")
        viewModel.onSignInClick()
        assertEquals(
            R.string.error_client_id_invalid_microsoft,
            viewModel.state.value.clientIdError
        )
        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `the saved client ID is prefilled and the browser opens with the address as hint`() {
        clientIds.savedMicrosoft = guid
        val viewModel = shown(target(AuthType.OAUTH_MICROSOFT, "outlook.office365.com"))
        assertEquals(guid, viewModel.state.value.clientId)

        viewModel.onSignInClick()

        val request = viewModel.state.value.oauthRequest
        assertNotNull(request)
        assertEquals("ana@example.test", request?.loginHint)
        assertEquals(AuthType.OAUTH_MICROSOFT, request?.authType)
        assertEquals(guid, request?.config?.clientId)
        assertEquals(ReauthProgress.SIGNING_IN, viewModel.state.value.progress)

        viewModel.onOAuthLaunched()
        assertNull(viewModel.state.value.oauthRequest)
        assertEquals(ReauthProgress.SIGNING_IN, viewModel.state.value.progress)
    }

    private fun signingIn(): ReauthViewModel {
        clientIds.savedGoogle = "123-abc.apps.googleusercontent.com"
        val viewModel = shown(target(AuthType.OAUTH_GOOGLE, "imap.gmail.com"))
        viewModel.onSignInClick()
        viewModel.onOAuthLaunched()
        return viewModel
    }

    @Test
    fun `a finished browser sign in hands address and tokens to the use case`() = runTest {
        coEvery { reauth.withOAuth(7, "ana@example.test", tokens) } returns ReauthResult.Success
        val viewModel = signingIn()

        viewModel.events.test {
            viewModel.onOAuthResult(success())

            assertEquals(ReauthEvent.SignedIn, awaitItem())
        }
        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `a different address is refused with its own message`() = runTest {
        coEvery { reauth.withOAuth(7, "other@example.test", tokens) } returns
            ReauthResult.AddressMismatch
        val viewModel = signingIn()

        viewModel.onOAuthResult(success("other@example.test"))

        assertEquals(ReauthFailure.AddressMismatch, viewModel.state.value.failure)
        assertEquals(
            R.string.reauth_error_address_mismatch,
            viewModel.state.value.failure?.toMessage()
        )
        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
    }

    @Test
    fun `a cancelled or failed browser sign in is reported and nothing is stored`() {
        val cases = mapOf(
            OAuthBrowserResult.Cancelled to ReauthFailure.SignInCancelled,
            OAuthBrowserResult.Failed to ReauthFailure.SignInFailed,
            OAuthBrowserResult.Success(tokens, null) to ReauthFailure.SignInNoAddress
        )

        cases.forEach { (result, failure) ->
            val viewModel = signingIn()
            viewModel.onOAuthResult(result)

            assertEquals(failure, viewModel.state.value.failure, "$result")
            assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        }
        coVerify(exactly = 0) { reauth.withOAuth(any(), any(), any()) }
    }

    @Test
    fun `a browser result that arrives after cancelling is ignored`() {
        val viewModel = signingIn()

        viewModel.cancel()
        viewModel.onOAuthResult(success())

        assertEquals(ReauthProgress.IDLE, viewModel.state.value.progress)
        assertNull(viewModel.state.value.failure)
        coVerify(exactly = 0) { reauth.withOAuth(any(), any(), any()) }
    }

    @Test
    fun `a browser result without a sign in in progress is ignored`() {
        val viewModel = shown()

        viewModel.onOAuthResult(success())

        assertNull(viewModel.state.value.failure)
        coVerify(exactly = 0) { reauth.withOAuth(any(), any(), any()) }
    }
}
