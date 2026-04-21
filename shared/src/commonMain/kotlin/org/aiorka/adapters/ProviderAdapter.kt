package org.aiorka.adapters

import org.aiorka.models.Message
import org.aiorka.models.OrkaResponse
import org.aiorka.models.ProviderConfig

interface ProviderAdapter {
    suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?
    ): OrkaResponse

    fun supportsProvider(type: String): Boolean
}

class ProviderHttpException(val statusCode: Int, val body: String) :
    Exception("Provider HTTP error $statusCode: $body")
