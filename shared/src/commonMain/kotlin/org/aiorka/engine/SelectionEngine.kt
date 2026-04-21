package org.aiorka.engine

import org.aiorka.models.*

class SelectionEngine(
    private val registry: ModelRegistry
) {
    fun selectBest(policy: SelectionPolicy, availableProviders: Map<String, Any>): String {
        // Logic to filter and sort based on strategy (least-cost, quality, etc)
        return policy.candidates.first() 
    }
}
