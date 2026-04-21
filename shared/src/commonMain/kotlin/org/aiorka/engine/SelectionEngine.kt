package org.aiorka.engine

import org.aiorka.models.ModelRegistry
import org.aiorka.models.NoValidProviderException
import org.aiorka.models.ProviderConfig
import org.aiorka.models.SelectionPolicy
import org.aiorka.monitoring.HealthMonitor

enum class Strategy(val key: String) {
    FIRST("first"),
    LEAST_COST("least-cost"),
    FASTEST("fastest"),
    QUALITY("quality");

    companion object {
        fun from(key: String): Strategy =
            entries.firstOrNull { it.key == key } ?: FIRST
    }
}

class SelectionEngine(
    private val registry: ModelRegistry,
    private val healthMonitor: HealthMonitor,
    private val credentialCheck: (providerId: String, provider: ProviderConfig) -> Boolean
) {
    fun selectBestProvider(
        policyId: String,
        policies: Map<String, SelectionPolicy>,
        providers: Map<String, ProviderConfig>
    ): Pair<String, ProviderConfig> {
        val policy = policies[policyId]
            ?: throw IllegalArgumentException("Policy '$policyId' not found in merged policy table")

        // Stage 1: Candidate resolution — policy.selection is an ordered list of provider IDs
        var candidates: List<Pair<String, ProviderConfig>> = policy.selection
            .mapNotNull { providerId -> providers[providerId]?.let { cfg -> providerId to cfg } }

        // Stage 2: Capability filter — required features and minimum context window
        if (policy.requirements.features.isNotEmpty()) {
            candidates = candidates.filter { (_, provider) ->
                val meta = registry.models[provider.modelRef] ?: return@filter false
                meta.capabilities.containsAll(policy.requirements.features)
            }
        }
        candidates = candidates.filter { (_, provider) ->
            val meta = registry.models[provider.modelRef] ?: return@filter false
            meta.contextWindow >= policy.requirements.minContext
        }

        // Stage 2b: Connectivity constraint
        if (policy.requirements.connectivity == "local-only") {
            candidates = candidates.filter { (_, provider) ->
                val meta = registry.models[provider.modelRef] ?: return@filter false
                meta.type == "local"
            }
        }

        // Stage 3: Health and credential validation
        candidates = candidates.filter { (providerId, provider) ->
            healthMonitor.isAlive(providerId) && credentialCheck(providerId, provider)
        }

        if (candidates.isEmpty()) {
            throw NoValidProviderException(
                "No providers met requirements for policy '$policyId'. " +
                "Check provider health, capabilities, context window, and credentials."
            )
        }

        // Stage 4: Optimization strategy
        return when (Strategy.from(policy.strategy)) {
            Strategy.FIRST -> candidates.first()

            Strategy.LEAST_COST -> candidates.minByOrNull { (_, provider) ->
                registry.models[provider.modelRef]?.costPer1k ?: Double.MAX_VALUE
            } ?: candidates.first()

            Strategy.FASTEST -> candidates.minByOrNull { (providerId, _) ->
                healthMonitor.getMedianLatencyMs(providerId) ?: Long.MAX_VALUE
            } ?: candidates.first()

            Strategy.QUALITY -> candidates.maxByOrNull { (_, provider) ->
                registry.models[provider.modelRef]?.scores?.get("logic") ?: 0.0
            } ?: candidates.first()
        }
    }
}
