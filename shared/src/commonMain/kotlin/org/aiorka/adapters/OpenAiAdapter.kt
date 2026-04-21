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

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>
)

@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: String
)

@Serializable
internal data class OpenAiChatResponse(
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null
)

@Serializable
internal data class OpenAiChoice(
    val message: OpenAiMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("total_tokens") val totalTokens: Int = 0
)

open class OpenAiAdapter(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.openai.com/v1/chat/completions",
    private val providerType: String = "openai"
) : ProviderAdapter {

    override fun supportsProvider(type: String): Boolean = type == providerType

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?
    ): OrkaResponse {
        requireNotNull(apiKey) { "Provider '$providerId' requires an API key" }

        val request = OpenAiChatRequest(
            model = provider.modelRef,
            messages = messages.map { OpenAiMessage(it.role, it.content) }
        )

        val start = currentTimeMillis()
        val httpResponse = httpClient.post(baseUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val durationMs = currentTimeMillis() - start

        if (!httpResponse.status.isSuccess()) {
            throw ProviderHttpException(httpResponse.status.value, httpResponse.bodyAsText())
        }

        val response: OpenAiChatResponse = httpResponse.body()
        val content = response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("OpenAI-compatible response contained no choices")

        val totalTokens = response.usage?.totalTokens ?: 0

        return OrkaResponse(
            content = content,
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
