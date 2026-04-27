package org.aiorka.benchmark

import kotlin.test.*

class MarkdownReporterTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun successResult(
        promptIndex: Int = 0,
        prompt: String = "Hello",
        providerId: String = "provider-a",
        modelRef: String = "model-a",
        latencyMs: Long = 500L,
        tokensUsed: Int = 100,
        estimatedCostUsd: Double = 0.0003
    ) = ProviderBenchmarkResult(
        promptIndex = promptIndex,
        prompt = prompt,
        providerId = providerId,
        modelRef = modelRef,
        response = "Response from $providerId",
        error = null,
        latencyMs = latencyMs,
        tokensUsed = tokensUsed,
        estimatedCostUsd = estimatedCostUsd
    )

    private fun failedResult(
        promptIndex: Int = 0,
        prompt: String = "Hello",
        providerId: String = "dead-provider"
    ) = ProviderBenchmarkResult(
        promptIndex = promptIndex,
        prompt = prompt,
        providerId = providerId,
        modelRef = "some-model",
        response = null,
        error = "HTTP 401 Unauthorized",
        latencyMs = 12L,
        tokensUsed = null,
        estimatedCostUsd = null
    )

    private fun report(vararg results: ProviderBenchmarkResult): BenchmarkReport =
        BenchmarkReport(
            generatedAtMs = 1_000_000_000L,
            config = BenchmarkConfig(
                prompts = results.map { it.prompt }.distinct(),
                providerIds = results.map { it.providerId }.distinct()
            ),
            results = results.toList(),
            outputPath = "test-output.md"
        )

    // ── Header ────────────────────────────────────────────────────────────────

    @Test
    fun `report starts with h1 title`() {
        val md = MarkdownReporter.render(report(successResult()))
        assertTrue(md.startsWith("# aiOrka Benchmark Report"), "Expected H1 title")
    }

    @Test
    fun `header lists provider count and names`() {
        val md = MarkdownReporter.render(
            report(successResult(providerId = "alpha"), successResult(providerId = "beta"))
        )
        assertTrue(md.contains("2"), "Expected provider count")
        assertTrue(md.contains("alpha"))
        assertTrue(md.contains("beta"))
    }

    @Test
    fun `header shows prompt count`() {
        val results = listOf(
            successResult(promptIndex = 0, prompt = "Prompt A", providerId = "p1"),
            successResult(promptIndex = 1, prompt = "Prompt B", providerId = "p1")
        )
        val md = MarkdownReporter.render(
            BenchmarkReport(1_000_000_000L, BenchmarkConfig(listOf("Prompt A", "Prompt B")), results, "out.md")
        )
        assertTrue(md.contains("Prompts:** 2"))
    }

    // ── Performance table ─────────────────────────────────────────────────────

    @Test
    fun `performance table contains provider id and model`() {
        val md = MarkdownReporter.render(report(successResult(providerId = "fast-server", modelRef = "qwen3.5:9b")))
        assertTrue(md.contains("fast-server"))
        assertTrue(md.contains("qwen3.5:9b"))
    }

    @Test
    fun `performance table contains latency`() {
        val md = MarkdownReporter.render(report(successResult(latencyMs = 1337L)))
        assertTrue(md.contains("1337ms"))
    }

    @Test
    fun `performance table shows checkmark for success and X for failure`() {
        val md = MarkdownReporter.render(report(successResult(), failedResult()))
        assertTrue(md.contains("✓"))
        assertTrue(md.contains("✗"))
    }

    // ── Cost formatting ───────────────────────────────────────────────────────

    @Test
    fun `zero cost shows as free`() {
        val md = MarkdownReporter.render(report(successResult(estimatedCostUsd = 0.0)))
        assertTrue(md.contains("free"))
    }

    @Test
    fun `small cost formats with dollar sign and six decimals`() {
        val md = MarkdownReporter.render(report(successResult(estimatedCostUsd = 0.000276)))
        assertTrue(md.contains("\$0.000276"), "Expected \$0.000276 but got:\n$md")
    }

    @Test
    fun `larger cost formats correctly`() {
        val md = MarkdownReporter.render(report(successResult(estimatedCostUsd = 1.5)))
        assertTrue(md.contains("\$1."), "Expected dollar amount > \$1")
    }

    // ── Responses section ─────────────────────────────────────────────────────

    @Test
    fun `response content appears in output`() {
        val result = successResult(providerId = "my-provider").copy(response = "The answer is 42.")
        val md = MarkdownReporter.render(report(result))
        assertTrue(md.contains("The answer is 42."))
    }

    @Test
    fun `failed provider shows error message instead of response`() {
        val md = MarkdownReporter.render(report(failedResult()))
        assertTrue(md.contains("HTTP 401 Unauthorized"))
        assertTrue(md.contains("FAILED"))
    }

    @Test
    fun `long response is truncated with notice`() {
        val longResponse = "x".repeat(600)
        val result = successResult().copy(response = longResponse)
        val md = MarkdownReporter.render(report(result))
        assertTrue(md.contains("truncated"), "Expected truncation notice")
        assertFalse(md.contains(longResponse), "Full 600-char response should not appear verbatim")
    }

    // ── Summary section ───────────────────────────────────────────────────────

    @Test
    fun `summary section is present`() {
        val md = MarkdownReporter.render(report(successResult()))
        assertTrue(md.contains("## Overall Summary"))
    }

    @Test
    fun `summary totals tokens across prompts for same provider`() {
        val results = listOf(
            successResult(promptIndex = 0, prompt = "Q1", tokensUsed = 80),
            successResult(promptIndex = 1, prompt = "Q2", tokensUsed = 120)
        )
        val md = MarkdownReporter.render(
            BenchmarkReport(1_000_000_000L, BenchmarkConfig(listOf("Q1", "Q2")), results, "out.md")
        )
        assertTrue(md.contains("200"), "Expected total token count 200 in summary")
    }

    @Test
    fun `summary shows correct success ratio`() {
        val results = listOf(
            successResult(promptIndex = 0, prompt = "Q1"),
            failedResult(promptIndex = 1, prompt = "Q2")
        )
        val md = MarkdownReporter.render(
            BenchmarkReport(1_000_000_000L, BenchmarkConfig(listOf("Q1", "Q2")), results, "out.md")
        )
        assertTrue(md.contains("1/2"), "Expected 1/2 success ratio in summary")
    }
}
