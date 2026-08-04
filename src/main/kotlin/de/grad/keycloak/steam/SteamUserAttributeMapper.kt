package de.grad.keycloak.steam

import org.keycloak.broker.provider.AbstractIdentityProviderMapper
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.models.IdentityProviderMapperModel
import org.keycloak.models.IdentityProviderSyncMode
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigProperty.LIST_TYPE
import org.keycloak.provider.ProviderConfigProperty.STRING_TYPE

/**
 * Attribute mapper that copies Steam profile claims into Keycloak user attributes.
 *
 * Visible in Admin Console under:
 *   Identity Providers → Steam → Mappers → Add mapper → Steam Attribute Importer
 */
class SteamUserAttributeMapper : org.keycloak.broker.provider.AbstractIdentityProviderMapper() {

    companion object {
        const val MAPPER_ID           = "steam-user-attribute-idp-mapper"
        const val STEAM_ATTRIBUTE_KEY = "steamAttribute"
        const val USER_ATTRIBUTE_KEY  = "userAttribute"

        val STEAM_ATTRIBUTES = listOf(
            SteamIdentityProviderConfig.CLAIM_STEAM_ID,
            SteamIdentityProviderConfig.CLAIM_USERNAME,
            SteamIdentityProviderConfig.CLAIM_AVATAR_URL,
            SteamIdentityProviderConfig.CLAIM_PROFILE_URL,
            SteamIdentityProviderConfig.CLAIM_REAL_NAME,
            SteamIdentityProviderConfig.CLAIM_COUNTRY_CODE,
        )
    }

    private val configProperties: List<ProviderConfigProperty> = listOf(
        ProviderConfigProperty().apply {
            name     = STEAM_ATTRIBUTE_KEY
            label    = "Steam Attribute"
            helpText = "The Steam attribute to import."
            type     = LIST_TYPE
            options  = STEAM_ATTRIBUTES
        },
        ProviderConfigProperty().apply {
            name     = USER_ATTRIBUTE_KEY
            label    = "User Attribute Name"
            helpText = "Name of the Keycloak user attribute to set."
            type     = STRING_TYPE
        },
    )

    override fun getId(): String = MAPPER_ID
    override fun getDisplayType(): String = "Steam Attribute Importer"
    override fun getDisplayCategory(): String = "Steam Identity Provider"
    override fun getHelpText(): String =
        "Imports a Steam profile attribute into a Keycloak user attribute."

    override fun getCompatibleProviders(): Array<String> =
        arrayOf(SteamIdentityProviderConfig.PROVIDER_ID)

    override fun getConfigProperties(): List<ProviderConfigProperty> = configProperties

    override fun preprocessFederatedIdentity(
        session: KeycloakSession,
        realm: RealmModel,
        mapperModel: IdentityProviderMapperModel,
        context: BrokeredIdentityContext,
    ) {
        val steamAttr = mapperModel.config[STEAM_ATTRIBUTE_KEY] ?: return
        val userAttr  = mapperModel.config[USER_ATTRIBUTE_KEY]  ?: return
        val value     = context.getUserAttribute(steamAttr)      ?: return
        context.setUserAttribute(userAttr, value)
    }

    override fun updateBrokeredUser(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel,
        mapperModel: IdentityProviderMapperModel,
        context: BrokeredIdentityContext,
    ) {
        val steamAttr = mapperModel.config[STEAM_ATTRIBUTE_KEY] ?: return
        val userAttr  = mapperModel.config[USER_ATTRIBUTE_KEY]  ?: return
        val value     = context.getUserAttribute(steamAttr)
        if (value != null) user.setSingleAttribute(userAttr, value)
        else user.removeAttribute(userAttr)
    }
}
