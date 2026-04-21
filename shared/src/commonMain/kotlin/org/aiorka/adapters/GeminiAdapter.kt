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

private const val BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models"

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>
)

@Serializable
private data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>
)

@Serializable
private data class GeminiPart(
    val text: String
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsage? = null,
    val modelVersion: String? = null
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent,
    @SerialName("finishReason") val finishReason: String? = null
)

@Serializable
private data class GeminiUsage(
    @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @SerialName("totalTokenCount") val totalTokenCount: Int = 0
)

class GeminiAdapter(private val httpClient: HttpClient) : ProviderAdapter {

    override fun supportsProvider(type: String): Boolean = type == "gemini"

    override suspend fun chat(
        providerId: String,
        provider: ProviderConfig,
        messages: List<Message>,
        apiKey: String?
    ): OrkaResponse {
        requireNotNull(apiKey) { "Gemini provider '$providerId' requires an API key" }

        // Map standard roles: "assistant" → "model" for Gemini's API
        val contents = messages.map { msg ->
            GeminiContent(
                role = if (msg.role == "assistant") "model" else msg.role,
                parts = listOf(GeminiPart(msg.content))
            )
        }

        val request = GeminiRequest(contents = contents)
        val url = "$BASE_URL/${provider.modelRef}:generateContent?key=$apiKey"

        val start = currentTimeMillis()
        val httpResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val durationMs = currentTimeMillis() - start

        if (!httpResponse.status.isSuccess()) {
            throw ProviderHttpException(httpResponse.status.value, httpResponse.bodyAsText())
        }

        val response: GeminiResponse = httpResponse.body()
        val text = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw IllegalStateException("Gemini response contained no candidate text")

        val totalTokens = response.usageMetadata?.totalTokenCount ?: 0
        val modelUsed = response.modelVersion ?: provider.modelRef

        return OrkaResponse(
            content = text,
            metadata = ResponseMetadata(
                providerId = providerId,
                modelUsed = modelUsed,
                durationMs = durationMs,
                tokensUsed = if (totalTokens > 0) totalTokens else null,
                cost = null
            )
        )
    }
}
