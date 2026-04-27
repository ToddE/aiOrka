package org.aiorka.benchmark

/**
 * Configuration for a benchmark run.
 *
 * @param prompts       One or more prompts to send to every provider.
 * @param providerIds   Which providers to test. Null means all configured providers.
 * @param outputPath    Where to write the Markdown report. Null produces a timestamped
 *                      file in the current working directory.
 */
data class BenchmarkConfig(
    val prompts: List<String>,
    val providerIds: List<String>? = null,
    val outputPath: String? = null
)
