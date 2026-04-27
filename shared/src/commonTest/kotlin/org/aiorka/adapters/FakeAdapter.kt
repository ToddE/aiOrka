package org.aiorka.adapters

import org.aiorka.models.Message
import org.aiorka.models.OrkaResponse
import org.aiorka.models.ProviderConfig
import org.aiorka.models.ResponseMetadata

/**
 * Test double for ProviderAdapter. Configurable to succeed or fail.
 * Tracks how many times chat() was called for assertion in retry tests.
 */
class FakeAdapter(
    private val responseContent: String = "fake response",
    private val failTimes: Int = 0,
    private val supportedType: String = "fake"
) : ProviderAdapter {

    var callCount = 0
        private set

    override fun supportsProvider(type: String): Boolean = type == supportedType

    var lastResolvedHeaders: Map<String, String> = emptyMap()
        private set

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?,
        resolvedHeaders: Map<String, String>
    ): OrkaResponse {
        callCount++
        lastResolvedHeaders = resolvedHeaders
        if (callCount <= failTimes) {
            throw RuntimeException("Simulated failure #$callCount")
        }
        return OrkaResponse(
            content = responseContent,
            metadata = ResponseMetadata(
                providerId = providerId,
                modelUsed = provider.modelRef,
                durationMs = 42L,
                tokensUsed = 10,
                cost = null
            )
        )
    }
}

/** Always throws, regardless of call count. */
class AlwaysFailAdapter(private val supportedType: String = "fake") : ProviderAdapter {
    override fun supportsProvider(type: String): Boolean = type == supportedType
    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?,
        resolvedHeaders: Map<String, String>
    ): OrkaResponse = throw RuntimeException("Always fails")
}
