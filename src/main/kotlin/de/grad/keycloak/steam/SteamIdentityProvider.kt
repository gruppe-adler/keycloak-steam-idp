package de.grad.keycloak.steam

import jakarta.enterprise.inject.Vetoed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.broker.provider.AbstractIdentityProvider
import org.keycloak.broker.provider.AuthenticationRequest
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.broker.provider.UserAuthenticationIdentityProvider.AuthenticationCallback
import org.keycloak.events.Errors
import org.keycloak.events.EventBuilder
import org.keycloak.events.EventType
import org.keycloak.models.FederatedIdentityModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.models.UserSessionModel
import org.keycloak.services.ErrorPage
import org.keycloak.services.messages.Messages
import org.keycloak.sessions.AuthenticationSessionModel
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class SteamIdentityProvider(
    session: KeycloakSession,
    config: SteamIdentityProviderConfig,
) : org.keycloak.broker.provider.AbstractIdentityProvider<SteamIdentityProviderConfig>(session, config) {

    private val log       = Logger.getLogger(SteamIdentityProvider::class.java)
    private val apiClient = SteamApiClient(config.steamApiKey)

    override fun performLogin(request: AuthenticationRequest): Response {
        val returnTo = "${request.redirectUri}?state=${enc(request.state.encoded)}"
        val steamUrl = buildSteamOpenIdUrl(returnTo, request.redirectUri)
        log.debugf("Steam OpenID redirect -> %s", steamUrl)
        return Response.seeOther(URI.create(steamUrl)).build()
    }

    /**
     * Returns [Endpoint] as the JAX-RS sub-resource.
     *
     * Endpoint is a **nested class** so Quarkus CDI does not pick it up as a
     * bean candidate — the same pattern used by all built-in Keycloak providers
     * (TwitterIdentityProvider.Endpoint, SAMLEndpoint, etc.).
     *
     * @Path on [Endpoint] is still required for Quarkus RESTEasy Reactive to
     * index the class and dispatch the incoming GET to [Endpoint.authResponse].
     */
    override fun callback(
        realm: RealmModel,
        callback: AuthenticationCallback,
        event: EventBuilder,
    ): Any = Endpoint(session, callback, event, this)

    @Deprecated("Deprecated in Java")
    override fun retrieveToken(session: KeycloakSession, identity: FederatedIdentityModel): Response =
        Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"token_retrieval_not_supported\"}")
            .type(MediaType.APPLICATION_JSON)
            .build()

    override fun retrieveToken(
        session: KeycloakSession,
        identity: FederatedIdentityModel,
        userSession: UserSessionModel,
        user: UserModel,
    ): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity("{\"error\":\"token_retrieval_not_supported\"}")
        .type(MediaType.APPLICATION_JSON)
        .build()

    override fun updateBrokeredUser(
        session: KeycloakSession?,
        realm: RealmModel?,
        user: UserModel?,
        context: BrokeredIdentityContext?
    ) {
        if (user == null) {
            return
        }

        context?.attributes?.forEach { (key, value) ->
            if (value.isNotEmpty()) {
                user.setAttribute(key, value)
            }
        }
    }

    // ── Nested JAX-RS endpoint — invisible to Quarkus CDI scanning ───────────

    /**
     * Handles Steam's OpenID return redirect.
     *
     * Nested inside [SteamIdentityProvider] so Quarkus CDI does not treat it
     * as a bean and attempt constructor injection of its parameters.
     *
     * @Path is required for Quarkus RESTEasy Reactive sub-resource dispatch.
     */
    @Vetoed
    @Path("/broker/{${SteamIdentityProviderConfig.PROVIDER_ID}}/endpoint")
    class Endpoint(
        private val session: KeycloakSession,
        private val callback: AuthenticationCallback,
        private val event: EventBuilder,
        private val provider: SteamIdentityProvider,
    ) {
        private val log = Logger.getLogger(Endpoint::class.java)

        @GET
        fun authResponse(
            @QueryParam("state")                 state: String?,
            @QueryParam("openid.mode")           mode: String?,
            @QueryParam("openid.ns")             ns: String?,
            @QueryParam("openid.op_endpoint")    opEndpoint: String?,
            @QueryParam("openid.claimed_id")     claimedId: String?,
            @QueryParam("openid.identity")       identity: String?,
            @QueryParam("openid.return_to")      returnTo: String?,
            @QueryParam("openid.response_nonce") nonce: String?,
            @QueryParam("openid.assoc_handle")   assocHandle: String?,
            @QueryParam("openid.signed")         signed: String?,
            @QueryParam("openid.sig")            sig: String?,
        ): Response {
            log.debugf("Steam callback: mode=%s state=%s claimedId=%s", mode, state, claimedId)

            if (mode == "cancel") {
                log.info("User cancelled Steam login")
                return callback.cancelled(provider.config)
            }
            if (mode != "id_res") {
                log.warnf("Unexpected openid.mode=%s", mode)
                return errorPage(null, Messages.UNEXPECTED_ERROR_HANDLING_RESPONSE)
            }
            if (state == null) {
                log.error("Missing state param in Steam callback")
                return errorPage(null, Messages.INVALID_REQUEST)
            }

            val authSession: AuthenticationSessionModel = try {
                callback.getAndVerifyAuthenticationSession(state)
            } catch (ex: Exception) {
                log.errorf(ex, "getAndVerifyAuthenticationSession failed, state=%s", state)
                return errorPage(null, Messages.INVALID_REQUEST)
            }

            session.context.authenticationSession = authSession

            val params = buildMap {
                fun add(k: String, v: String?) { if (v != null) put(k, v) }
                add("openid.mode",           mode)
                add("openid.ns",             ns)
                add("openid.op_endpoint",    opEndpoint)
                add("openid.claimed_id",     claimedId)
                add("openid.identity",       identity)
                add("openid.return_to",      returnTo)
                add("openid.response_nonce", nonce)
                add("openid.assoc_handle",   assocHandle)
                add("openid.signed",         signed)
                add("openid.sig",            sig)
            }

            return try {
                val identityCtx = provider.validateAndBuildIdentity(params, authSession)

                event.event(EventType.IDENTITY_PROVIDER_LOGIN)
                    .detail("identity_provider", SteamIdentityProviderConfig.PROVIDER_ID)
                    .detail("identity_provider_identity", identityCtx.brokerUserId)

                callback.authenticated(identityCtx)

            } catch (ex: WebApplicationException) {
                log.warnf(ex, "WebApplicationException in Steam callback")
                ex.response
            } catch (ex: IdentityBrokerException) {
                log.errorf(ex, "Steam broker error")
                event.event(EventType.IDENTITY_PROVIDER_LOGIN).error(Errors.IDENTITY_PROVIDER_ERROR)
                errorPage(authSession, Messages.UNEXPECTED_ERROR_HANDLING_RESPONSE)
            } catch (ex: Exception) {
                log.errorf(ex, "Unexpected error in Steam callback")
                event.event(EventType.IDENTITY_PROVIDER_LOGIN).error(Errors.IDENTITY_PROVIDER_ERROR)
                errorPage(authSession, Messages.UNEXPECTED_ERROR_HANDLING_RESPONSE)
            }
        }

        private fun errorPage(authSession: AuthenticationSessionModel?, msg: String): Response =
            ErrorPage.error(session, authSession, Response.Status.BAD_GATEWAY, msg)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun validateAndBuildIdentity(
        params: Map<String, String>,
        authSession: AuthenticationSessionModel,
    ): BrokeredIdentityContext {
        val opEndpoint = params["openid.op_endpoint"]
        if (opEndpoint != SteamIdentityProviderConfig.STEAM_OPENID_ENDPOINT) {
            log.warnf("Rejecting assertion: unexpected openid.op_endpoint=%s", opEndpoint)
            throw IdentityBrokerException("Unexpected OpenID provider endpoint")
        }

        verifyOpenIdAssertion(params)

        val claimedId = params["openid.claimed_id"]
            ?: throw IdentityBrokerException("Missing openid.claimed_id")

        val steamId = claimedId.substringAfterLast('/')
        require(steamId.matches(Regex("\\d{17}"))) {
            "Unexpected claimed_id format: $claimedId"
        }
        log.infof("Steam assertion verified, steamId=%s", steamId)

        val summary = if (config.steamApiKey.isNotBlank()) {
            apiClient.getPlayerSummary(steamId)
                .also { if (it == null) log.warnf("No Steam summary for steamId=%s", steamId) }
        } else {
            log.warn("No Steam API key configured")
            null
        }

        return BrokeredIdentityContext(config).also { ctx ->
            ctx.id                = steamId
            ctx.idp               = this
            ctx.brokerUserId      = steamId
            ctx.username          = summary?.personaName ?: "SteamID_$steamId"
            ctx.authenticationSession = authSession

            ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_STEAM_ID, steamId)
            summary?.let { s ->
                ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_USERNAME,    s.personaName)
                ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_AVATAR_URL,  s.avatarFull)
                ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_PROFILE_URL, s.profileUrl)
                ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_REAL_NAME, s.realName)
                ctx.setUserAttribute(SteamIdentityProviderConfig.CLAIM_COUNTRY_CODE, s.countryCode)

                s.realName?.let { name ->
                    val parts = name.split(' ', limit = 2)
                    ctx.firstName = parts[0]
                    ctx.lastName  = parts.getOrNull(1) ?: ""
                }
            }
        }
    }

    private fun buildSteamOpenIdUrl(returnTo: String, realmBase: String): String = buildString {
        append(SteamIdentityProviderConfig.STEAM_OPENID_ENDPOINT)
        append("?openid.ns=").append(enc(SteamIdentityProviderConfig.OPENID_NS))
        append("&openid.mode=checkid_setup")
        append("&openid.return_to=").append(enc(returnTo))
        append("&openid.realm=").append(enc(realmBase))
        append("&openid.identity=").append(enc("http://specs.openid.net/auth/2.0/identifier_select"))
        append("&openid.claimed_id=").append(enc("http://specs.openid.net/auth/2.0/identifier_select"))
    }

    private fun verifyOpenIdAssertion(params: Map<String, String>) {
        val body = params.toMutableMap()
            .apply { this["openid.mode"] = "check_authentication" }
            .entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        val req = HttpRequest.newBuilder()
            .uri(URI.create(SteamIdentityProviderConfig.STEAM_OPENID_ENDPOINT))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(req, HttpResponse.BodyHandlers.ofString())

        if (resp.statusCode() != 200)
            throw IdentityBrokerException("Steam check_authentication HTTP ${resp.statusCode()}")
        if (!resp.body().contains("is_valid:true")) {
            log.warnf("Steam assertion invalid: %s", resp.body())
            throw IdentityBrokerException("Steam OpenID assertion is invalid")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}