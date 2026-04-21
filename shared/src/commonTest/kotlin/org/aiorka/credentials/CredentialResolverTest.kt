package org.aiorka.credentials

import org.aiorka.models.ProviderConfig
import kotlin.test.*

class CredentialResolverTest {

    private fun provider(apiKeyEnv: String?) = ProviderConfig(
        type = "cloud",
        modelRef = "some-model",
        apiKeyEnv = apiKeyEnv
    )

    // -------------------------------------------------------------------------
    // Local providers (no apiKeyEnv)
    // -------------------------------------------------------------------------

    @Test
    fun `local provider with no apiKeyEnv always has credentials`() {
        val resolver = CredentialResolver()
        assertTrue(resolver.hasCredentials(provider(null)))
    }

    @Test
    fun `local provider with no apiKeyEnv resolves to null`() {
        val resolver = CredentialResolver()
        assertNull(resolver.resolve(provider(null)))
    }

    // -------------------------------------------------------------------------
    // Injected keys
    // -------------------------------------------------------------------------

    @Test
    fun `injected key is resolved by env var name`() {
        val resolver = CredentialResolver(injectedKeys = mapOf("MY_KEY" to "sk-injected"))
        val p = provider("MY_KEY")
        assertEquals("sk-injected", resolver.resolve(p))
    }

    @Test
    fun `provider has credentials when injected key exists`() {
        val resolver = CredentialResolver(injectedKeys = mapOf("MY_KEY" to "sk-injected"))
        assertTrue(resolver.hasCredentials(provider("MY_KEY")))
    }

    @Test
    fun `injected key takes priority over any other source`() {
        // Even if env var were set, injected key should win (tested by verifying the returned value)
        val resolver = CredentialResolver(injectedKeys = mapOf("ANTHROPIC_API_KEY" to "injected-wins"))
        assertEquals("injected-wins", resolver.resolve(provider("ANTHROPIC_API_KEY")))
    }

    // -------------------------------------------------------------------------
    // Missing credentials
    // -------------------------------------------------------------------------

    @Test
    fun `provider without injected key and no env var has no credentials`() {
        val resolver = CredentialResolver(injectedKeys = emptyMap())
        // "DEFINITELY_NOT_SET_XYZ" is assumed not to exist as a real env var in CI
        assertFalse(resolver.hasCredentials(provider("DEFINITELY_NOT_SET_XYZ_12345")))
    }

    @Test
    fun `resolve returns null when env var name not in injected keys and not in env`() {
        val resolver = CredentialResolver(injectedKeys = emptyMap())
        assertNull(resolver.resolve(provider("DEFINITELY_NOT_SET_XYZ_12345")))
    }

    // -------------------------------------------------------------------------
    // Multiple providers
    // -------------------------------------------------------------------------

    @Test
    fun `resolver handles multiple injected keys independently`() {
        val resolver = CredentialResolver(
            injectedKeys = mapOf(
                "OPENAI_API_KEY" to "sk-openai",
                "ANTHROPIC_API_KEY" to "sk-anthropic"
            )
        )
        assertEquals("sk-openai", resolver.resolve(provider("OPENAI_API_KEY")))
        assertEquals("sk-anthropic", resolver.resolve(provider("ANTHROPIC_API_KEY")))
        assertNull(resolver.resolve(provider("GEMINI_API_KEY")))  // not injected
    }
}
