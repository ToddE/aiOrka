package org.aiorka.benchmark

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.aiorka.adapters.ProviderAdapter
import org.aiorka.credentials.CredentialResolver
import org.aiorka.models.Message
import org.aiorka.models.ModelRegistry
import org.aiorka.models.ProviderConfig
import org.aiorka.platform.currentTimeMillis
import org.aiorka.platform.formatTimestamp
import org.aiorka.platform.writeTextFile

internal class BenchmarkRunner(
    private val providers: Map<String, ProviderConfig>,
    private val adapters: List<ProviderAdapter>,
    private val credentialResolver: CredentialResolver,
    private val registry: ModelRegistry
) {

    suspend fun run(config: BenchmarkConfig): BenchmarkReport {
        val generatedAtMs = currentTimeMillis()
        val results = mutableListOf<ProviderBenchmarkResult>()

        config.prompts.forEachIndexed { promptIdx, prompt ->
            val promptResults = coroutineScope {
                providers.entries.map { (providerId, provider) ->
                    async { runOne(promptIdx, prompt, providerId, provider) }
                }.map { it.await() }
            }
            results.addAll(promptResults)
        }

        val outputPath = config.outputPath
            ?: "aiorka-benchmark-$generatedAtMs.md"

        val report = BenchmarkReport(
            generatedAtMs = generatedAtMs,
            config = config,
            results = results,
            outputPath = outputPath
        )

        writeTextFile(outputPath, MarkdownReporter.render(report))
        return report
    }

    private suspend fun runOne(
        promptIndex: Int,
        prompt: String,
        providerId: String,
        provider: ProviderConfig
    ): ProviderBenchmarkResult {
        val adapter = adapters.firstOrNull { it.supportsProvider(provider.type) }
            ?: return ProviderBenchmarkResult(
                promptIndex = promptIndex,
                prompt = prompt,
                providerId = providerId,
                modelRef = provider.modelRef,
                response = null,
                error = "No adapter registered for provider type '${provider.type}'",
                latencyMs = 0,
                tokensUsed = null,
                estimatedCostUsd = null
            )

        val apiKey = credentialResolver.resolve(provider)
        val resolvedHeaders = credentialResolver.resolveHeaders(provider.headersEnv)
        val messages = listOf(Message.user(prompt))
        val start = currentTimeMillis()

        return try {
            val response = adapter.chat(providerId, provider, messages, apiKey, resolvedHeaders)
            val latencyMs = currentTimeMillis() - start
            val costPer1k = registry.models[provider.modelRef]?.costPer1k ?: 0.0
            val tokens = response.metadata.tokensUsed
            val estimatedCost = if (tokens != null && costPer1k > 0.0)
                tokens * costPer1k / 1000.0 else 0.0

            ProviderBenchmarkResult(
                promptIndex = promptIndex,
                prompt = prompt,
                providerId = providerId,
                modelRef = provider.modelRef,
                response = response.content,
                error = null,
                latencyMs = latencyMs,
                tokensUsed = tokens,
                estimatedCostUsd = estimatedCost
            )
        } catch (e: Exception) {
            ProviderBenchmarkResult(
                promptIndex = promptIndex,
                prompt = prompt,
                providerId = providerId,
                modelRef = provider.modelRef,
                response = null,
                error = e.message ?: "Unknown error",
                latencyMs = currentTimeMillis() - start,
                tokensUsed = null,
                estimatedCostUsd = null
            )
        }
    }
}
