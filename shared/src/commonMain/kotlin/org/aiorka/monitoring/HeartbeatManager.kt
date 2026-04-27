package org.aiorka.monitoring

import kotlinx.coroutines.*
import org.aiorka.adapters.ProviderAdapter
import org.aiorka.credentials.CredentialResolver
import org.aiorka.models.HeartbeatConfig
import org.aiorka.models.Message
import org.aiorka.models.ProviderConfig
import org.aiorka.platform.currentTimeMillis

class HeartbeatManager(
    private val healthMonitor: HealthMonitor,
    private val adapters: List<ProviderAdapter>,
    private val credentialResolver: CredentialResolver,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    fun start(providers: Map<String, ProviderConfig>, config: HeartbeatConfig) {
        if (!config.enabled) return
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(config.intervalMs)
                pingAll(providers, config)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pingAll(
        providers: Map<String, ProviderConfig>,
        config: HeartbeatConfig
    ) = coroutineScope {
        providers.entries.map { (providerId, provider) ->
            async(Dispatchers.Default) {
                pingOne(providerId, provider, config)
            }
        }.awaitAll()
    }

    private suspend fun pingOne(
        providerId: String,
        provider: ProviderConfig,
        config: HeartbeatConfig
    ) {
        val adapter = adapters.firstOrNull { it.supportsProvider(provider.type) } ?: return
        val apiKey = credentialResolver.resolve(provider)
        val resolvedHeaders = credentialResolver.resolveHeaders(provider.headersEnv)
        val pingMessages = listOf(Message.user(config.minimalPrompt))

        val start = currentTimeMillis()
        val success = try {
            withTimeout(config.timeoutMs) {
                adapter.chat(providerId, provider, pingMessages, apiKey, resolvedHeaders)
            }
            true
        } catch (_: Exception) {
            false
        }
        val elapsed = currentTimeMillis() - start

        if (success) {
            healthMonitor.recordSuccess(providerId, elapsed)
        } else {
            healthMonitor.recordFailure(providerId, config.failureThreshold)
        }
    }
}
