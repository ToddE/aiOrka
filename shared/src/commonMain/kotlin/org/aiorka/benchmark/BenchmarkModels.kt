package org.aiorka.benchmark

/**
 * Result for a single (provider, prompt) pair.
 */
data class ProviderBenchmarkResult(
    val promptIndex: Int,
    val prompt: String,
    val providerId: String,
    val modelRef: String,
    val response: String?,
    val error: String?,
    val latencyMs: Long,
    val tokensUsed: Int?,
    val estimatedCostUsd: Double?
) {
    val succeeded: Boolean get() = error == null
}

/**
 * Aggregate result for a full benchmark run.
 */
data class BenchmarkReport(
    val generatedAtMs: Long,
    val config: BenchmarkConfig,
    val results: List<ProviderBenchmarkResult>,
    val outputPath: String
)
