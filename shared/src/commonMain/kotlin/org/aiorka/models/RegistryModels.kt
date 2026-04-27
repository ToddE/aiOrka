package org.aiorka.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level container for the global model registry.
 */
@Serializable
data class ModelRegistry(
    val models: Map<String, ModelMetadata>
)

@Serializable
data class ModelMetadata(
    val type: String,
    val capabilities: List<String> = emptyList(),
    @SerialName("cost_per_1k") val costPer1k: Double = 0.0,
    @SerialName("context_window") val contextWindow: Int = 4096,
    val scores: Map<String, Double> = emptyMap()
)

/**
 * Top-level container for application-specific configuration (aiOrka.yaml).
 */
@Serializable
data class AppConfig(
    val monitoring: MonitoringConfig? = null,
    val defaults: DefaultsConfig? = null,
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val policies: Map<String, SelectionPolicy> = emptyMap()
)

@Serializable
data class MonitoringConfig(
    val heartbeat: HeartbeatConfig? = null,
    val telemetry: TelemetryConfig? = null
)

@Serializable
data class HeartbeatConfig(
    val enabled: Boolean = false,
    @SerialName("interval_ms") val intervalMs: Long = 300000,
    @SerialName("timeout_ms") val timeoutMs: Long = 3000,
    @SerialName("failure_threshold") val failureThreshold: Int = 2,
    @SerialName("minimal_prompt") val minimalPrompt: String = "."
)

@Serializable
data class TelemetryConfig(
    val enabled: Boolean = false,
    @SerialName("sample_rate") val sampleRate: Double = 1.0
)

@Serializable
data class DefaultsConfig(
    val retry: RetryConfig? = null,
    val caching: CachingConfig? = null
)

@Serializable
data class RetryConfig(
    @SerialName("max_attempts") val maxAttempts: Int = 3,
    @SerialName("initial_backoff_ms") val initialBackoffMs: Long = 500,
    @SerialName("max_backoff_ms") val maxBackoffMs: Long = 5000,
    val strategy: String = "exponential"
)

@Serializable
data class CachingConfig(
    val enabled: Boolean = false,
    @SerialName("ttl_ms") val ttlMs: Long = 3600000
)

@Serializable
data class ProviderConfig(
    val type: String,
    @SerialName("model_ref") val modelRef: String,
    val endpoint: String? = null,
    @SerialName("api_key_env") val apiKeyEnv: String? = null,
    val description: String? = null,
    val config: Map<String, String> = emptyMap(),
    @SerialName("headers_env") val headersEnv: Map<String, String> = emptyMap()
)

@Serializable
data class SelectionPolicy(
    val description: String? = null,
    val strategy: String,
    val requirements: PolicyRequirements = PolicyRequirements(),
    val selection: List<String> = emptyList(),
    @SerialName("retry_override") val retryOverride: RetryConfig? = null,
    val caching: CachingConfig? = null
)

@Serializable
data class PolicyRequirements(
    val features: List<String> = emptyList(),
    @SerialName("min_context") val minContext: Int = 4096,
    val connectivity: String = "any" // "any" or "local-only"
)

/**
 * Standard Message format for execution.
 */
@Serializable
data class Message(
    val role: String,
    val content: String
) {
    companion object {
        fun user(content: String) = Message("user", content)
        fun assistant(content: String) = Message("assistant", content)
        fun system(content: String) = Message("system", content)
    }
}

/**
 * The unified response object returned by aiOrka.
 */
@Serializable
data class OrkaResponse(
    val content: String,
    val metadata: ResponseMetadata
)

@Serializable
data class ResponseMetadata(
    val providerId: String,
    val modelUsed: String,
    val durationMs: Long,
    val tokensUsed: Int? = null,
    val cost: Double? = null
)

class NoValidProviderException(message: String) : Exception(message)