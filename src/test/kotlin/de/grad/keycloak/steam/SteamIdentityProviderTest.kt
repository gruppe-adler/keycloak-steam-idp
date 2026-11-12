package de.grad.keycloak.steam

import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_AVATAR_URL
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_COUNTRY_CODE
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_PROFILE_URL
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_REAL_NAME
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_STEAM_ID
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_USERNAME
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.STEAM_OPENID_ENDPOINT
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.broker.provider.AuthenticationRequest
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.models.KeycloakSession
import org.keycloak.models.UserModel
import org.keycloak.sessions.AuthenticationSessionModel

class SteamIdentityProviderTest {

    private val openId = FakeHttpServer().apply { body = "ns:http://specs.openid.net/auth/2.0\nis_valid:true\n" }
    private val webApi = FakeHttpServer().apply {
        body = """{"response":{"players":[{"steamid":"$STEAM_ID","personaname":"Robin",
            "avatarfull":"https://avatars/full.jpg","profileurl":"https://steamcommunity.com/id/robin/",
            "realname":"Robin Walker","loccountrycode":"US"}]}}"""
    }
    private val session: KeycloakSession = mockk(relaxed = true)
    private val authSession: AuthenticationSessionModel = mockk()

    @AfterEach
    fun tearDown() {
        openId.close()
        webApi.close()
    }

    private fun provider(apiKey: String = "secret-key") =
        SteamIdentityProvider(
            session,
            SteamIdentityProviderConfig().apply { alias = "steam"; isEnabled = true; steamApiKey = apiKey },
            SteamApiClient(apiKey, webApi.url),
            openId.url,
        )

    private fun assertion(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf<String, String?>(
            "openid.ns" to "http://specs.openid.net/auth/2.0",
            "openid.mode" to "id_res",
            "openid.op_endpoint" to STEAM_OPENID_ENDPOINT,
            "openid.claimed_id" to "https://steamcommunity.com/openid/id/$STEAM_ID",
            "openid.identity" to "https://steamcommunity.com/openid/id/$STEAM_ID",
            "openid.return_to" to "https://kc.example/realms/grad/broker/steam/endpoint?state=abc",
            "openid.response_nonce" to "2026-11-12T10:00:00Zabc",
            "openid.assoc_handle" to "1234567890",
            "openid.signed" to "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle",
            "openid.sig" to "c2lnbmF0dXJl",
        )
        overrides.forEach { (k, v) -> base[k] = v }
        @Suppress("UNCHECKED_CAST")
        return base.filterValues { it != null } as Map<String, String>
    }

    // ── performLogin ─────────────────────────────────────────────────────────

    @Test
    fun `performLogin redirects to Steam OpenID with state in return_to`() {
        val redirectUri = "https://kc.example/realms/grad/broker/steam/endpoint"
        val request: AuthenticationRequest = mockk {
            every { this@mockk.redirectUri } returns redirectUri
            every { state } returns mockk { every { encoded } returns "state.tab.client" }
        }

        val response = provider().performLogin(request)

        assertEquals(303, response.status)
        val location = response.location
        assertEquals("https://steamcommunity.com/openid/login", "${location.scheme}://${location.host}${location.path}")
        val query = FakeHttpServer.decodeForm(location.rawQuery)
        assertEquals("checkid_setup", query["openid.mode"])
        assertEquals("http://specs.openid.net/auth/2.0", query["openid.ns"])
        assertEquals("$redirectUri?state=state.tab.client", query["openid.return_to"])
        assertEquals(redirectUri, query["openid.realm"])
        assertEquals("http://specs.openid.net/auth/2.0/identifier_select", query["openid.identity"])
        assertEquals("http://specs.openid.net/auth/2.0/identifier_select", query["openid.claimed_id"])
    }

    // ── assertion verification ───────────────────────────────────────────────

    @Test
    fun `rejects assertions from a foreign OpenID provider without contacting Steam`() {
        assertThrows<IdentityBrokerException> {
            provider().validateAndBuildIdentity(assertion("openid.op_endpoint" to "https://evil.example/openid"), authSession)
        }
        assertTrue(openId.requests.isEmpty())
    }

    @Test
    fun `rejects assertions with no op_endpoint`() {
        assertThrows<IdentityBrokerException> {
            provider().validateAndBuildIdentity(assertion("openid.op_endpoint" to null), authSession)
        }
        assertTrue(openId.requests.isEmpty())
    }

    @Test
    fun `verifies the assertion with Steam via check_authentication`() {
        val params = assertion()

        provider().validateAndBuildIdentity(params, authSession)

        val req = openId.requests.single()
        assertEquals("POST", req.method)
        assertEquals(params + ("openid.mode" to "check_authentication"), req.form)
    }

    @Test
    fun `rejects assertions Steam reports as invalid`() {
        openId.body = "ns:http://specs.openid.net/auth/2.0\nis_valid:false\n"

        assertThrows<IdentityBrokerException> { provider().validateAndBuildIdentity(assertion(), authSession) }
        assertTrue(webApi.requests.isEmpty())
    }

    @Test
    fun `rejects assertions when check_authentication fails`() {
        openId.status = 500

        assertThrows<IdentityBrokerException> { provider().validateAndBuildIdentity(assertion(), authSession) }
    }

    @Test
    fun `rejects a missing claimed_id`() {
        assertThrows<IdentityBrokerException> {
            provider().validateAndBuildIdentity(assertion("openid.claimed_id" to null), authSession)
        }
    }

    @Test
    fun `rejects a claimed_id that does not end in a 64-bit Steam ID`() {
        assertThrows<IllegalArgumentException> {
            provider().validateAndBuildIdentity(
                assertion("openid.claimed_id" to "https://steamcommunity.com/openid/id/robin"),
                authSession,
            )
        }
    }

    // ── identity building ────────────────────────────────────────────────────

    @Test
    fun `builds the identity from the Steam profile`() {
        val ctx = provider().validateAndBuildIdentity(assertion(), authSession)

        assertEquals(STEAM_ID, ctx.id)
        assertEquals(STEAM_ID, ctx.brokerUserId)
        assertEquals("robin", ctx.username) // Keycloak lower-cases usernames
        assertEquals("Robin", ctx.firstName)
        assertEquals("Walker", ctx.lastName)
        assertSame(authSession, ctx.authenticationSession)
        assertEquals(STEAM_ID, ctx.getUserAttribute(CLAIM_STEAM_ID))
        assertEquals("Robin", ctx.getUserAttribute(CLAIM_USERNAME))
        assertEquals("https://avatars/full.jpg", ctx.getUserAttribute(CLAIM_AVATAR_URL))
        assertEquals("https://steamcommunity.com/id/robin/", ctx.getUserAttribute(CLAIM_PROFILE_URL))
        assertEquals("Robin Walker", ctx.getUserAttribute(CLAIM_REAL_NAME))
        assertEquals("US", ctx.getUserAttribute(CLAIM_COUNTRY_CODE))

        assertEquals(STEAM_ID, webApi.requests.single().query["steamids"])
    }

    @Test
    fun `single-word real name becomes the first name`() {
        webApi.body = """{"response":{"players":[{"steamid":"$STEAM_ID","personaname":"Robin","realname":"Robin"}]}}"""

        val ctx = provider().validateAndBuildIdentity(assertion(), authSession)

        assertEquals("Robin", ctx.firstName)
        assertEquals("", ctx.lastName)
    }

    @Test
    fun `without an API key the identity falls back to the Steam ID`() {
        val ctx = provider(apiKey = "").validateAndBuildIdentity(assertion(), authSession)

        assertEquals("steamid_$STEAM_ID", ctx.username)
        assertEquals(STEAM_ID, ctx.getUserAttribute(CLAIM_STEAM_ID))
        assertEquals(null, ctx.getUserAttribute(CLAIM_USERNAME))
        assertTrue(webApi.requests.isEmpty())
    }

    @Test
    fun `when the Steam Web API fails the identity falls back to the Steam ID`() {
        webApi.status = 503

        val ctx = provider().validateAndBuildIdentity(assertion(), authSession)

        assertEquals(STEAM_ID, ctx.brokerUserId)
        assertEquals("steamid_$STEAM_ID", ctx.username)
    }

    // ── updateBrokeredUser ───────────────────────────────────────────────────

    @Test
    fun `updateBrokeredUser copies brokered attributes onto the user`() {
        val user: UserModel = mockk(relaxed = true)
        val ctx = BrokeredIdentityContext(SteamIdentityProviderConfig().apply { isEnabled = true }).apply {
            setUserAttribute(CLAIM_STEAM_ID, STEAM_ID)
            setUserAttribute(CLAIM_USERNAME, "Robin")
        }

        provider().updateBrokeredUser(session, null, user, ctx)

        verify { user.setAttribute(CLAIM_STEAM_ID, listOf(STEAM_ID)) }
        verify { user.setAttribute(CLAIM_USERNAME, listOf("Robin")) }
    }

    @Test
    fun `updateBrokeredUser tolerates a missing user`() {
        provider().updateBrokeredUser(session, null, null, BrokeredIdentityContext(SteamIdentityProviderConfig().apply { isEnabled = true }))
    }

    companion object {
        const val STEAM_ID = "76561197960435530"
    }
}
