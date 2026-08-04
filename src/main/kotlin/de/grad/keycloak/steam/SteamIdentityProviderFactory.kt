package de.grad.keycloak.steam

import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.broker.provider.IdentityProvider
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigProperty.STRING_TYPE
import org.keycloak.provider.ProviderConfigProperty.PASSWORD
import org.keycloak.provider.ProviderConfigProperty.LIST_TYPE
import java.util.Collections

/**
 * Keycloak SPI factory for the Steam identity provider.
 *
 * Discovered via META-INF/services/org.keycloak.broker.provider.IdentityProviderFactory.
 *
 * Notes on AbstractIdentityProviderFactory in KC 24:
 *  - No-arg constructor only (provider ID/name are NOT constructor params)
 *  - getId() must be overridden and return the provider alias string
 *  - createConfig() must return IdentityProviderModel (or a subclass)
 *  - create(session, model) builds the provider instance
 */
class SteamIdentityProviderFactory : org.keycloak.broker.provider.AbstractIdentityProviderFactory<SteamIdentityProvider>() {

    override fun getId(): String = SteamIdentityProviderConfig.PROVIDER_ID

    override fun getName(): String = "Steam identity provider"

    override fun createConfig(): IdentityProviderModel =
        SteamIdentityProviderConfig()

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): SteamIdentityProvider = SteamIdentityProvider(session, SteamIdentityProviderConfig(model))


    override fun getConfigProperties(): List<ProviderConfigProperty> = listOf(
        ProviderConfigProperty().apply {
            name     = SteamIdentityProviderConfig.STEAM_API_KEY
            label    = "Steam Web API Key"
            helpText = "Your Steam Web API key from https://steamcommunity.com/dev/apikey. " +
                       "Required to fetch usernames and avatars."
            type     = PASSWORD
        },
    )
}
