package org.aiorka.core.engine

import org.aiorka.core.models.*

/**
 * The core "Orka" Selection Engine.
 * Responsible for sifting through candidates and applying optimization logic.
 */
class SelectionEngine(
    private val registry: ModelRegistry,
    private val validator: ProviderValidator,
    private val telemetry: TelemetryTracker
) {
    /**
     * Finds the best provider for a given policy.
     */
    suspend fun selectBestProvider(
        policyId: String,
        policies: Map<String, SelectionPolicy>,
        providers: Map<String, ProviderConfig>
    ): ProviderConfig {
        val policy = policies[policyId] ?: throw IllegalArgumentException("Policy $policyId not found")
        
        // 1. Filter by explicit candidates in the policy
        var candidates = policy.candidates.mapNotNull { providers[it] }

        // 2. Capability Filter
        candidates = candidates.filter { provider ->
            val modelMetadata = registry.models[provider.modelRef]
            modelMetadata?.capabilities?.containsAll(policy.requirements.features) ?: false
        }

        // 3. Environmental & Validation Filter (Health Check)
        candidates = candidates.filter { provider ->
            validator.isAlive(provider) && validator.hasValidCredentials(provider)
        }

        if (candidates.isEmpty()) {
            throw NoValidProviderException("No providers met requirements for policy: $policyId")
        }

        // 4. Optimization Strategy
        return when (policy.strategy) {
            Strategy.LEAST_COST -> candidates.minBy { 
                registry.models[it.modelRef]?.costPer1k ?: Double.MAX_VALUE 
            }
            Strategy.FASTEST -> candidates.minBy { 
                telemetry.getMedianLatency(it.id) ?: Double.MAX_VALUE 
            }
            Strategy.QUALITY -> candidates.maxBy { 
                registry.models[it.modelRef]?.scores?.logic ?: 0.0 
            }
        }
    }
}

interface ProviderValidator {
    suspend fun isAlive(provider: ProviderConfig): Boolean
    fun hasValidCredentials(provider: ProviderConfig): Boolean
}

interface TelemetryTracker {
    fun getMedianLatency(providerId: String): Long?
    fun recordAttempt(event: TelemetryEvent)
}