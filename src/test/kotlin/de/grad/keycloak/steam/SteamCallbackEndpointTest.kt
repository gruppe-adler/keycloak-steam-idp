package de.grad.keycloak.steam

import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.STEAM_OPENID_ENDPOINT
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.UserAuthenticationIdentityProvider.AuthenticationCallback
import org.keycloak.events.Errors
import org.keycloak.events.EventBuilder
import org.keycloak.events.EventType
import org.keycloak.models.KeycloakSession
import org.keycloak.services.ErrorPage
import org.keycloak.services.messages.Messages
import org.keycloak.sessions.AuthenticationSessionModel

class SteamCallbackEndpointTest {

    private val openId = FakeHttpServer().apply { body = "ns:http://specs.openid.net/auth/2.0\nis_valid:true\n" }
    private val session: KeycloakSession = mockk(relaxed = true)
    private val authSession: AuthenticationSessionModel = mockk()
    private val callback: AuthenticationCallback = mockk()
    private val event: EventBuilder = mockk(relaxed = true)
    private val config = SteamIdentityProviderConfig().apply { alias = "steam"; isEnabled = true }
    private val provider = SteamIdentityProvider(session, config, SteamApiClient(""), openId.url)
    private val endpoint = provider.callback(mockk(), callback, event) as SteamIdentityProvider.Endpoint

    private val errorResponse: Response = mockk()
    private val errorMessage = slot<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(ErrorPage::class)
        every { ErrorPage.error(any(), any(), any(), capture(errorMessage), *anyVararg()) } returns errorResponse
        every { event.event(any()) } returns event
        every { event.detail(any(), any<String>()) } returns event
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ErrorPage::class)
        openId.close()
    }

    private fun respond(
        mode: String? = "id_res",
        state: String? = "state.tab.client",
        opEndpoint: String? = STEAM_OPENID_ENDPOINT,
        claimedId: String? = "https://steamcommunity.com/openid/id/$STEAM_ID",
    ): Response = endpoint.authResponse(
        state = state,
        mode = mode,
        ns = "http://specs.openid.net/auth/2.0",
        opEndpoint = opEndpoint,
        claimedId = claimedId,
        identity = claimedId,
        returnTo = "https://kc.example/realms/grad/broker/steam/endpoint?state=$state",
        nonce = "2026-11-12T10:00:00Zabc",
        assocHandle = "1234567890",
        signed = "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle",
        sig = "c2lnbmF0dXJl",
    )

    @Test
    fun `cancelled login is passed to the callback`() {
        val cancelled: Response = mockk()
        every { callback.cancelled(config) } returns cancelled

        assertSame(cancelled, respond(mode = "cancel"))
    }

    @Test
    fun `unexpected openid mode shows an error page`() {
        assertSame(errorResponse, respond(mode = "setup_needed"))
        assertEquals(Messages.UNEXPECTED_ERROR_HANDLING_RESPONSE, errorMessage.captured)
        verify(exactly = 0) { callback.getAndVerifyAuthenticationSession(any()) }
    }

    @Test
    fun `missing state shows an error page`() {
        assertSame(errorResponse, respond(state = null))
        assertEquals(Messages.INVALID_REQUEST, errorMessage.captured)
        verify(exactly = 0) { callback.getAndVerifyAuthenticationSession(any()) }
    }

    @Test
    fun `invalid state shows an error page without verifying the assertion`() {
        every { callback.getAndVerifyAuthenticationSession(any()) } throws IllegalStateException("expired")

        assertSame(errorResponse, respond())
        assertEquals(Messages.INVALID_REQUEST, errorMessage.captured)
        assertEquals(0, openId.requests.size)
    }

    @Test
    fun `valid assertion authenticates the Steam identity`() {
        val authenticated: Response = mockk()
        val ctx = slot<BrokeredIdentityContext>()
        every { callback.getAndVerifyAuthenticationSession("state.tab.client") } returns authSession
        every { callback.authenticated(capture(ctx)) } returns authenticated

        assertSame(authenticated, respond())

        assertEquals(STEAM_ID, ctx.captured.brokerUserId)
        assertSame(authSession, ctx.captured.authenticationSession)
        verify { session.context.authenticationSession = authSession }
        verify { event.event(EventType.IDENTITY_PROVIDER_LOGIN) }
        verify { event.detail("identity_provider_identity", STEAM_ID) }
    }

    @Test
    fun `assertion Steam rejects shows an error page and logs an error event`() {
        openId.body = "ns:http://specs.openid.net/auth/2.0\nis_valid:false\n"
        every { callback.getAndVerifyAuthenticationSession(any()) } returns authSession

        assertSame(errorResponse, respond())

        assertEquals(Messages.UNEXPECTED_ERROR_HANDLING_RESPONSE, errorMessage.captured)
        verify { event.error(Errors.IDENTITY_PROVIDER_ERROR) }
        verify(exactly = 0) { callback.authenticated(any()) }
    }

    @Test
    fun `assertion from a foreign OpenID provider is rejected`() {
        every { callback.getAndVerifyAuthenticationSession(any()) } returns authSession

        assertSame(errorResponse, respond(opEndpoint = "https://evil.example/openid"))

        verify { event.error(Errors.IDENTITY_PROVIDER_ERROR) }
        verify(exactly = 0) { callback.authenticated(any()) }
        assertEquals(0, openId.requests.size)
    }

    @Test
    fun `malformed claimed_id shows an error page`() {
        every { callback.getAndVerifyAuthenticationSession(any()) } returns authSession

        assertSame(errorResponse, respond(claimedId = "https://steamcommunity.com/openid/id/robin"))

        verify { event.error(Errors.IDENTITY_PROVIDER_ERROR) }
        verify(exactly = 0) { callback.authenticated(any()) }
    }

    @Test
    fun `WebApplicationException response is returned as-is`() {
        val response = Response.status(Response.Status.CONFLICT).build()
        every { callback.getAndVerifyAuthenticationSession(any()) } returns authSession
        every { callback.authenticated(any()) } throws WebApplicationException(response)

        assertSame(response, respond())
    }

    companion object {
        const val STEAM_ID = "76561197960435530"
    }
}
