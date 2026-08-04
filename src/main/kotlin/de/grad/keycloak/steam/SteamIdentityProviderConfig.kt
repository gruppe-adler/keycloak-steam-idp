package de.grad.keycloak.steam

import org.keycloak.models.IdentityProviderModel

/**
 * Typed configuration wrapper for the Steam Identity Provider.
 *
 * Delegates all field storage to [IdentityProviderModel]'s built-in copy
 * constructor — no manual field copying required or safe to do.
 */
class SteamIdentityProviderConfig : IdentityProviderModel {

    /** No-arg constructor used by [SteamIdentityProviderFactory.createConfig]. */
    constructor() : super()

    /** Copy constructor used by [SteamIdentityProviderFactory.create]. */
    constructor(model: IdentityProviderModel) : super(model)

    companion object {
        const val STEAM_API_KEY = "steamApiKey"

        const val STEAM_OPENID_ENDPOINT = "https://steamcommunity.com/openid/login"

        const val STEAM_API_BASE = "https://api.steampowered.com"
        const val STEAM_GET_PLAYER_SUMMARIES =
            "$STEAM_API_BASE/ISteamUser/GetPlayerSummaries/v0002/"

        const val OPENID_NS = "http://specs.openid.net/auth/2.0"

        const val PROVIDER_ID = "steam-idp"

        const val CLAIM_STEAM_ID    = "steamId"
        const val CLAIM_USERNAME    = "steamUsername"
        const val CLAIM_AVATAR_URL  = "steamAvatarUrl"
        const val CLAIM_PROFILE_URL = "steamProfileUrl"
        const val CLAIM_REAL_NAME = "steamRealName"
        const val CLAIM_COUNTRY_CODE = "steamCountryCode"
    }

    var steamApiKey: String
        get() = config?.get(STEAM_API_KEY) ?: ""
        set(value) { config[STEAM_API_KEY] = value }
}
