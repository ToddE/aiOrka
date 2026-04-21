package org.aiorka.monitoring

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HealthStatus(
    val isAlive: Boolean = true,
    val consecutiveFailures: Int = 0,
    val lastLatencyMs: Long? = null,
    val latencySamples: List<Long> = emptyList()
)

class HealthMonitor {
    private val mutex = Mutex()
    private val statusMap = mutableMapOf<String, HealthStatus>()

    suspend fun recordSuccess(providerId: String, latencyMs: Long) = mutex.withLock {
        val current = statusMap[providerId] ?: HealthStatus()
        val samples = (current.latencySamples + latencyMs).takeLast(20)
        statusMap[providerId] = HealthStatus(
            isAlive = true,
            consecutiveFailures = 0,
            lastLatencyMs = latencyMs,
            latencySamples = samples
        )
    }

    suspend fun recordFailure(providerId: String, failureThreshold: Int) = mutex.withLock {
        val current = statusMap[providerId] ?: HealthStatus()
        val failures = current.consecutiveFailures + 1
        statusMap[providerId] = current.copy(
            isAlive = failures < failureThreshold,
            consecutiveFailures = failures
        )
    }

    fun isAlive(providerId: String): Boolean =
        statusMap[providerId]?.isAlive ?: true

    fun getMedianLatencyMs(providerId: String): Long? {
        val samples = statusMap[providerId]?.latencySamples ?: return null
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    suspend fun registerProviders(providerIds: Set<String>) = mutex.withLock {
        providerIds.forEach { id ->
            if (!statusMap.containsKey(id)) {
                statusMap[id] = HealthStatus(isAlive = true)
            }
        }
    }

    fun snapshot(): Map<String, HealthStatus> = statusMap.toMap()
}
