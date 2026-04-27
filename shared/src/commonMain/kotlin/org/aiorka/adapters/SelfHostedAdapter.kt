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
private data class SelfHostedChatRequest(
    val model: String,
    val messages: List<SelfHostedMessage>,
    val stream: Boolean = false,
    val options: Map<String, Int> = emptyMap()
)

@Serializable
private data class SelfHostedMessage(
    val role: String,
    val content: String
)

@Serializable
private data class SelfHostedChatResponse(
    val model: String,
    val message: SelfHostedMessage,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("total_duration") val totalDurationNs: Long? = null
)

class SelfHostedAdapter(private val httpClient: HttpClient) : ProviderAdapter {

    override fun supportsProvider(type: String): Boolean = type == "selfhosted"

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?,
        resolvedHeaders: Map<String, String>
    ): OrkaResponse {
        val baseUrl = provider.endpoint
            ?: throw IllegalArgumentException("Self-hosted provider '$providerId' has no endpoint configured")

        val numCtx = provider.config["num_ctx"]?.toIntOrNull()
        val options = if (numCtx != null) mapOf("num_ctx" to numCtx) else emptyMap()

        val request = SelfHostedChatRequest(
            model = provider.modelRef,
            messages = messages.map { SelfHostedMessage(it.role, it.content) },
            stream = false,
            options = options
        )

        val start = currentTimeMillis()
        val response: SelfHostedChatResponse = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            resolvedHeaders.forEach { (name, value) -> header(name, value) }
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
