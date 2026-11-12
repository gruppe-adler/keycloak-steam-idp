package de.grad.keycloak.steam

import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_AVATAR_URL
import de.grad.keycloak.steam.SteamIdentityProviderConfig.Companion.CLAIM_STEAM_ID
import de.grad.keycloak.steam.SteamUserAttributeMapper.Companion.STEAM_ATTRIBUTE_KEY
import de.grad.keycloak.steam.SteamUserAttributeMapper.Companion.USER_ATTRIBUTE_KEY
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.models.IdentityProviderMapperModel
import org.keycloak.models.UserModel

class SteamUserAttributeMapperTest {

    private val mapper = SteamUserAttributeMapper()

    private fun mapperModel(steamAttr: String?, userAttr: String?) = IdentityProviderMapperModel().apply {
        config = buildMap {
            steamAttr?.let { put(STEAM_ATTRIBUTE_KEY, it) }
            userAttr?.let { put(USER_ATTRIBUTE_KEY, it) }
        }.toMutableMap()
    }

    private fun context(vararg attrs: Pair<String, String>) =
        BrokeredIdentityContext(SteamIdentityProviderConfig().apply { isEnabled = true }).apply {
            attrs.forEach { (k, v) -> setUserAttribute(k, v) }
        }

    @Test
    fun `is only offered for the Steam provider`() {
        assertArrayEquals(arrayOf(SteamIdentityProviderConfig.PROVIDER_ID), mapper.compatibleProviders)
    }

    @Test
    fun `offers every Steam claim as an option`() {
        val steamAttrProp = mapper.configProperties.single { it.name == STEAM_ATTRIBUTE_KEY }
        assertEquals(SteamUserAttributeMapper.STEAM_ATTRIBUTES, steamAttrProp.options)
    }

    @Test
    fun `preprocess copies the Steam claim into the configured attribute`() {
        val ctx = context(CLAIM_STEAM_ID to "76561197960435530")

        mapper.preprocessFederatedIdentity(mockk(), mockk(), mapperModel(CLAIM_STEAM_ID, "steam_id"), ctx)

        assertEquals("76561197960435530", ctx.getUserAttribute("steam_id"))
    }

    @Test
    fun `preprocess ignores claims the identity does not have`() {
        val ctx = context(CLAIM_STEAM_ID to "76561197960435530")

        mapper.preprocessFederatedIdentity(mockk(), mockk(), mapperModel(CLAIM_AVATAR_URL, "avatar"), ctx)

        assertNull(ctx.getUserAttribute("avatar"))
    }

    @Test
    fun `preprocess ignores an incomplete mapper config`() {
        val ctx = context(CLAIM_STEAM_ID to "76561197960435530")

        mapper.preprocessFederatedIdentity(mockk(), mockk(), mapperModel(CLAIM_STEAM_ID, null), ctx)

        assertEquals(mapOf(CLAIM_STEAM_ID to listOf("76561197960435530")), ctx.attributes)
    }

    @Test
    fun `update sets the user attribute from the Steam claim`() {
        val user: UserModel = mockk(relaxed = true)

        mapper.updateBrokeredUser(
            mockk(), mockk(), user,
            mapperModel(CLAIM_AVATAR_URL, "avatar"),
            context(CLAIM_AVATAR_URL to "https://avatars/full.jpg"),
        )

        verify { user.setSingleAttribute("avatar", "https://avatars/full.jpg") }
    }

    @Test
    fun `update removes the user attribute when the Steam claim is gone`() {
        val user: UserModel = mockk(relaxed = true)

        mapper.updateBrokeredUser(mockk(), mockk(), user, mapperModel(CLAIM_AVATAR_URL, "avatar"), context())

        verify { user.removeAttribute("avatar") }
    }

    @Test
    fun `update leaves the user alone when the mapper config is incomplete`() {
        val user: UserModel = mockk(relaxed = true)

        mapper.updateBrokeredUser(
            mockk(), mockk(), user,
            mapperModel(null, "avatar"),
            context(CLAIM_AVATAR_URL to "https://avatars/full.jpg"),
        )

        confirmVerified(user)
    }
}
