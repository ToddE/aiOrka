/*
 * Copyright (c) 2026 PluralFusion INC. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * https://github.com/ToddE/aiorka/blob/main/LICENSE.md
 *
 * See the LICENSE.md file in the root directory for full license terms,
 * including the Additional Use Grant and Change Date.
 */

package org.aiorka

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.aiorka.adapters.*
import org.aiorka.benchmark.BenchmarkConfig
import org.aiorka.benchmark.BenchmarkReport
import org.aiorka.benchmark.BenchmarkRunner
import org.aiorka.credentials.CredentialResolver
import org.aiorka.engine.SelectionEngine
import org.aiorka.models.*
import org.aiorka.monitoring.HeartbeatManager
import org.aiorka.monitoring.HealthMonitor
import org.aiorka.platform.currentTimeMillis
import org.aiorka.resources.ResourceLoader
import org.aiorka.resources.YamlParser

/**
 * The main entry point for the aiOrka library.
 *
 * Usage:
 *   val orka = AiOrka.initialize {
 *       configYaml = myAppConfigYaml   // optional; uses bundled aiOrka.yaml if omitted
 *       apiKeys["ANTHROPIC_API_KEY"] = BuildConfig.ANTHROPIC_KEY
 *   }
 *   val response = orka.execute("fast-chat", listOf(Message.user("Hello!")))
 */
class AiOrka internal constructor(
    private val mergedPolicies: Map<String, SelectionPolicy>,
    private val mergedProviders: Map<String, ProviderConfig>,
    private val selectionEngine: SelectionEngine,
    private val adapters: List<ProviderAdapter>,
    private val heartbeatManager: HeartbeatManager,
    private val healthMonitor: HealthMonitor,
    private val credentialResolver: CredentialResolver,
    private val retryConfig: RetryConfig,
    private val scope: CoroutineScope,
    private val registry: ModelRegistry
) {

    class Builder {
        /** Full YAML string for the user app config. Omit to use the bundled aiOrka.yaml. */
        var configYaml: String? = null

        /** Manually inject API keys keyed by env var name (e.g. "ANTHROPIC_API_KEY" -> "sk-..."). */
        val apiKeys: MutableMap<String, String> = mutableMapOf()

        /** Provide a custom CoroutineScope for heartbeat lifecycle. AiOrka creates one if null. */
        var coroutineScope: CoroutineScope? = null
    }

    companion object {
        /**
         * Initializes the library. Must be called once at application startup.
         * This is a suspend function because it reads bundled resources asynchronously.
         */
        suspend fun initialize(block: Builder.() -> Unit = {}): AiOrka {
            val builder = Builder().apply(block)

            // 1. Load and parse bundled resources
            val registryYaml = ResourceLoader.loadModelsRegistry()
            val defaultPoliciesYaml = ResourceLoader.loadDefaultPolicies()

            val registry = YamlParser.parseModelRegistry(registryYaml)
            val libraryPolicies = YamlParser.parsePoliciesFile(defaultPoliciesYaml)

            // 2. Parse user config (injected or bundled aiOrka.yaml)
            val userConfigYaml = builder.configYaml ?: ResourceLoader.loadDefaultAppConfig()
            val userAppConfig = YamlParser.parseAppConfig(userConfigYaml)

            // 3. Merge: user policies win on key collision per the spec
            val mergedPolicies = libraryPolicies + userAppConfig.policies
            val mergedProviders = userAppConfig.providers

            // 4. Effective global retry config
            val retryConfig = userAppConfig.defaults?.retry ?: RetryConfig()

            // 5. Build infrastructure
            val credentialResolver = CredentialResolver(builder.apiKeys)
            val healthMonitor = HealthMonitor()
            healthMonitor.registerProviders(mergedProviders.keys.toSet())

            // 6. Build shared HTTP client (one instance shared across all adapters)
            val httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

            // 7. Register all adapters
            val adapters: List<ProviderAdapter> = listOf(
                SelfHostedAdapter(httpClient),
                AnthropicAdapter(httpClient),
                OpenAiAdapter(httpClient),
                GeminiAdapter(httpClient),
                DeepSeekAdapter(httpClient)
            )

            // 8. Build SelectionEngine with credential check lambda
            val selectionEngine = SelectionEngine(
                registry = registry,
                healthMonitor = healthMonitor,
                credentialCheck = { _, provider -> credentialResolver.hasCredentials(provider) }
            )

            // 9. Set up lifecycle scope
            val scope = builder.coroutineScope
                ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

            // 10. Start heartbeat if enabled
            val heartbeatManager = HeartbeatManager(
                healthMonitor = healthMonitor,
                adapters = adapters,
                credentialResolver = credentialResolver,
                scope = scope
            )
            userAppConfig.monitoring?.heartbeat?.let { heartbeatConfig ->
                heartbeatManager.start(mergedProviders, heartbeatConfig)
            }

            return AiOrka(
                mergedPolicies = mergedPolicies,
                mergedProviders = mergedProviders,
                selectionEngine = selectionEngine,
                adapters = adapters,
                heartbeatManager = heartbeatManager,
                healthMonitor = healthMonitor,
                credentialResolver = credentialResolver,
                retryConfig = retryConfig,
                scope = scope,
                registry = registry
            )
        }
    }

    /**
     * Execute a request using the named policy.
     * Applies exponential backoff retry as configured. Throws [NoValidProviderException]
     * if no providers survive the selection funnel, or if all retries are exhausted.
     */
    suspend fun execute(
        policyId: String,
        messages: List<Message>
    ): OrkaResponse = withContext(Dispatchers.Default) {
        val policy = mergedPolicies[policyId]
            ?: throw IllegalArgumentException("Unknown policy: '$policyId'")

        val effectiveRetry = policy.retryOverride ?: retryConfig
        var lastException: Exception? = null
        var backoffMs = effectiveRetry.initialBackoffMs

        repeat(effectiveRetry.maxAttempts) { attempt ->
            try {
                val (providerId, provider) = selectionEngine.selectBestProvider(
                    policyId = policyId,
                    policies = mergedPolicies,
                    providers = mergedProviders
                )

                val adapter = adapters.firstOrNull { it.supportsProvider(provider.type) }
                    ?: throw NoValidProviderException(
                        "No adapter registered for provider type '${provider.type}'"
                    )

                val apiKey = credentialResolver.resolve(provider)
                val resolvedHeaders = credentialResolver.resolveHeaders(provider.headersEnv)
                val start = currentTimeMillis()

                return@withContext try {
                    val response = adapter.chat(providerId, provider, messages, apiKey, resolvedHeaders)
                    healthMonitor.recordSuccess(providerId, currentTimeMillis() - start)
                    response
                } catch (e: Exception) {
                    healthMonitor.recordFailure(providerId, effectiveRetry.maxAttempts)
                    throw e
                }

            } catch (e: NoValidProviderException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < effectiveRetry.maxAttempts - 1) {
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, effectiveRetry.maxBackoffMs)
                }
            }
        }

        throw lastException
            ?: NoValidProviderException("All retry attempts exhausted for policy '$policyId'")
    }

    /**
     * Inject or update an API key at runtime by environment variable name.
     * Useful for C/Python/Go/Rust consumers that resolve keys after initialization.
     */
    fun setApiKey(envVarName: String, value: String) {
        credentialResolver.addKey(envVarName, value)
    }

    /**
     * Returns a snapshot of the current health state for all configured providers.
     * Useful for diagnostics and monitoring dashboards.
     */
    fun healthSnapshot() = healthMonitor.snapshot()

    /**
     * Runs every prompt in [config] against each target provider and writes a
     * human-readable Markdown report to [BenchmarkConfig.outputPath] (or a
     * timestamped file in the current directory if omitted).
     *
     * Providers within each prompt are queried in parallel. The returned
     * [BenchmarkReport] contains the raw results for programmatic inspection.
     */
    suspend fun benchmark(config: BenchmarkConfig): BenchmarkReport =
        withContext(Dispatchers.Default) {
            val targetIds = config.providerIds ?: mergedProviders.keys.toList()
            val targetProviders = targetIds.mapNotNull { id ->
                mergedProviders[id]?.let { id to it }
            }.toMap()

            BenchmarkRunner(
                providers = targetProviders,
                adapters = adapters,
                credentialResolver = credentialResolver,
                registry = registry
            ).run(config)
        }

    /**
     * Gracefully shuts down background tasks. Call from application teardown.
     */
    fun shutdown() {
        heartbeatManager.stop()
        scope.cancel()
    }
}
