package org.aiorka.adapters

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.aiorka.models.Message
import org.aiorka.models.OrkaResponse
import org.aiorka.models.ProviderConfig
import org.aiorka.models.ResponseMetadata
import org.aiorka.platform.currentTimeMillis

private const val BASE_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MAX_TOKENS = 4096

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = MAX_TOKENS,
    val messages: List<AnthropicMessage>
)

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
private data class AnthropicResponse(
    val id: String,
    val model: String,
    val content: List<AnthropicContent>,
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicContent(
    val type: String,
    val text: String? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

class AnthropicAdapter(private val httpClient: HttpClient) : ProviderAdapter {

    override fun supportsProvider(type: String): Boolean = type == "anthropic"

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?,
        resolvedHeaders: Map<String, String>
    ): OrkaResponse {
        requireNotNull(apiKey) { "Anthropic provider '$providerId' requires an API key" }

        val request = AnthropicRequest(
            model = provider.modelRef,
            messages = messages.map { AnthropicMessage(it.role, it.content) }
        )

        val start = currentTimeMillis()
        val httpResponse = httpClient.post(BASE_URL) {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val durationMs = currentTimeMillis() - start

        if (!httpResponse.status.isSuccess()) {
            throw ProviderHttpException(httpResponse.status.value, httpResponse.bodyAsText())
        }

        val response: AnthropicResponse = httpResponse.body()
        val text = response.content.firstOrNull { it.type == "text" }?.text
            ?: throw IllegalStateException("Anthropic response contained no text content")

        val totalTokens = (response.usage?.inputTokens ?: 0) + (response.usage?.outputTokens ?: 0)

        return OrkaResponse(
            content = text,
            metadata = ResponseMetadata(
                providerId = providerId,
                modelUsed = response.model,
                durationMs = durationMs,
                tokensUsed = if (totalTokens > 0) totalTokens else null,
                cost = null
            )
        )
    }
}
