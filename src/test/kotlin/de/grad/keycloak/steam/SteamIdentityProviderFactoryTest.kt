package de.grad.keycloak.steam

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.keycloak.broker.provider.IdentityProviderFactory
import org.keycloak.broker.provider.IdentityProviderMapper
import org.keycloak.models.IdentityProviderModel
import org.keycloak.provider.ProviderConfigProperty

class SteamIdentityProviderFactoryTest {

    private val factory = SteamIdentityProviderFactory()

    @Test
    fun `provider id matches what the mapper is compatible with`() {
        assertEquals("steam-idp", factory.id)
        assertEquals(listOf(factory.id), SteamUserAttributeMapper().compatibleProviders.toList())
    }

    @Test
    fun `createConfig returns a Steam config`() {
        assertInstanceOf(SteamIdentityProviderConfig::class.java, factory.createConfig())
    }

    @Test
    fun `create carries the stored API key into the provider`() {
        val model = IdentityProviderModel().apply {
            alias = "steam"
            config = mutableMapOf(SteamIdentityProviderConfig.STEAM_API_KEY to "secret-key")
        }

        val provider = factory.create(mockk(relaxed = true), model)

        assertEquals("secret-key", provider.config.steamApiKey)
        assertEquals("steam", provider.config.alias)
    }

    @Test
    fun `API key is a password field in the admin console`() {
        val prop = factory.configProperties.single()
        assertEquals(SteamIdentityProviderConfig.STEAM_API_KEY, prop.name)
        assertEquals(ProviderConfigProperty.PASSWORD, prop.type)
    }

    @Test
    fun `API key defaults to empty`() {
        assertEquals("", SteamIdentityProviderConfig().steamApiKey)
    }

    /**
     * Reads the `META-INF/services` registrations directly: a plain [java.util.ServiceLoader]
     * would also try to load Keycloak's built-in providers, whose dependencies are not on
     * the test classpath.
     */
    private fun registered(spi: Class<*>): List<Class<*>> =
        javaClass.classLoader.getResources("META-INF/services/${spi.name}").toList()
            .flatMap { it.readText().lines() }
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("de.grad.keycloak.steam.") }
            .map { Class.forName(it) }

    @Test
    fun `factory and mapper are registered for service discovery`() {
        assertEquals(listOf(SteamIdentityProviderFactory::class.java), registered(IdentityProviderFactory::class.java))
        assertEquals(listOf(SteamUserAttributeMapper::class.java), registered(IdentityProviderMapper::class.java))
    }
}
