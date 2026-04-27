package org.aiorka.benchmark

import org.aiorka.platform.formatTimestamp

internal object MarkdownReporter {

    private const val RESPONSE_PREVIEW_CHARS = 500

    fun render(report: BenchmarkReport): String = buildString {
        appendHeader(report)
        report.config.prompts.forEachIndexed { idx, prompt ->
            appendPromptSection(report, idx, prompt)
        }
        appendSummary(report)
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendHeader(report: BenchmarkReport) {
        val providerList = report.config.providerIds
            ?: report.results.map { it.providerId }.distinct()

        appendLine("# aiOrka Benchmark Report")
        appendLine()
        appendLine("**Generated:** ${formatTimestamp(report.generatedAtMs)}  ")
        appendLine("**Providers tested:** ${providerList.size} (${providerList.joinToString(", ")})  ")
        appendLine("**Prompts:** ${report.config.prompts.size}")
        appendLine()
        appendLine("---")
        appendLine()
    }

    // ── Per-prompt section ────────────────────────────────────────────────────

    private fun StringBuilder.appendPromptSection(
        report: BenchmarkReport,
        idx: Int,
        prompt: String
    ) {
        val promptResults = report.results
            .filter { it.promptIndex == idx }
            .sortedBy { it.latencyMs }

        appendLine("## Prompt ${idx + 1} of ${report.config.prompts.size}")
        appendLine()
        appendLine("> ${prompt.replace("\n", "  \n> ")}")
        appendLine()

        // Performance table
        appendLine("### Performance")
        appendLine()
        appendLine("| Provider | Model | Latency | Tokens | Est. Cost | Status |")
        appendLine("|:---|:---|---:|---:|---:|:---:|")
        promptResults.forEach { r ->
            val latency = "${r.latencyMs}ms"
            val tokens = r.tokensUsed?.toString() ?: "—"
            val cost = formatCost(r.estimatedCostUsd)
            val status = if (r.succeeded) "✓" else "✗"
            appendLine("| ${r.providerId} | `${r.modelRef}` | $latency | $tokens | $cost | $status |")
        }
        appendLine()
        appendLine("*Sorted by latency ascending.*")
        appendLine()

        // Responses
        appendLine("### Responses")
        appendLine()
        promptResults.forEach { r ->
            appendLine("---")
            appendLine()
            if (r.succeeded) {
                appendLine("**${r.providerId}** · `${r.modelRef}` · ${r.latencyMs}ms")
                appendLine()
                val preview = r.response!!
                if (preview.length > RESPONSE_PREVIEW_CHARS) {
                    appendLine(preview.take(RESPONSE_PREVIEW_CHARS))
                    appendLine()
                    appendLine("*(response truncated — ${preview.length} chars total)*")
                } else {
                    appendLine(preview)
                }
            } else {
                appendLine("**${r.providerId}** · `${r.modelRef}` · ✗ FAILED")
                appendLine()
                appendLine("```")
                appendLine(r.error)
                appendLine("```")
            }
            appendLine()
        }

        appendLine("---")
        appendLine()
    }

    // ── Overall summary ───────────────────────────────────────────────────────

    private fun StringBuilder.appendSummary(report: BenchmarkReport) {
        val promptCount = report.config.prompts.size
        val providerIds = report.results.map { it.providerId }.distinct()

        appendLine("## Overall Summary")
        appendLine()
        appendLine("| Provider | Model | Avg Latency | Prompts OK | Total Tokens | Total Est. Cost |")
        appendLine("|:---|:---|---:|---:|---:|---:|")

        providerIds.forEach { pid ->
            val providerResults = report.results.filter { it.providerId == pid }
            val model = providerResults.first().modelRef
            val successes = providerResults.count { it.succeeded }
            val avgLatency = if (providerResults.isNotEmpty())
                providerResults.sumOf { it.latencyMs } / providerResults.size else 0L
            val totalTokens = providerResults.sumOf { it.tokensUsed ?: 0 }
            val totalCost = providerResults.sumOf { it.estimatedCostUsd ?: 0.0 }

            appendLine(
                "| $pid | `$model` | ${avgLatency}ms | $successes/$promptCount" +
                    " | $totalTokens | ${formatCost(totalCost)} |"
            )
        }

        appendLine()
        appendLine("---")
        appendLine()
        appendLine(
            "*Review responses above and adjust your `policies.yaml` accordingly. " +
                "Report written by aiOrka.*"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatCost(cost: Double?): String {
        if (cost == null || cost == 0.0) return "free"
        val microcents = (cost * 1_000_000).toLong()
        if (microcents == 0L) return "<\$0.000001"
        val s = microcents.toString()
        return if (s.length <= 6) {
            "\$0." + s.padStart(6, '0')
        } else {
            "\$" + s.dropLast(6) + "." + s.takeLast(6)
        }
    }
}
