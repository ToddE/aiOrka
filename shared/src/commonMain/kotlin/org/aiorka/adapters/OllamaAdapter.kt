package org.aiorka.adapters

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.aiorka.models.Message
import org.aiorka.models.OrkaResponse
import org.aiorka.models.ProviderConfig
import org.aiorka.models.ResponseMetadata
import org.aiorka.platform.currentTimeMillis

@Serializable
private data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val options: Map<String, Int> = emptyMap()
)

@Serializable
private data class OllamaMessage(
    val role: String,
    val content: String
)

@Serializable
private data class OllamaChatResponse(
    val model: String,
    val message: OllamaMessage,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("total_duration") val totalDurationNs: Long? = null
)

class OllamaAdapter(private val httpClient: HttpClient) : ProviderAdapter {

    override fun supportsProvider(type: String): Boolean = type == "ollama"

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?
    ): OrkaResponse {
        val baseUrl = provider.endpoint
            ?: throw IllegalArgumentException("Ollama provider '$providerId' has no endpoint configured")

        val numCtx = provider.config["num_ctx"]?.toIntOrNull()
        val options = if (numCtx != null) mapOf("num_ctx" to numCtx) else emptyMap()

        val request = OllamaChatRequest(
            model = provider.modelRef,
            messages = messages.map { OllamaMessage(it.role, it.content) },
            stream = false,
            options = options
        )

        val start = currentTimeMillis()
        val response: OllamaChatResponse = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        val durationMs = currentTimeMillis() - start

        val totalTokens = (response.promptEvalCount ?: 0) + (response.evalCount ?: 0)

        return OrkaResponse(
            content = response.message.content,
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
