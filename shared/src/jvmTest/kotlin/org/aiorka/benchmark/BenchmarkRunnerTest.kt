package org.aiorka.benchmark

import kotlinx.coroutines.runBlocking
import org.aiorka.adapters.FakeAdapter
import org.aiorka.credentials.CredentialResolver
import org.aiorka.models.ModelMetadata
import org.aiorka.models.ModelRegistry
import org.aiorka.models.ProviderConfig
import org.junit.jupiter.api.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BenchmarkRunnerTest {

    private val registry = ModelRegistry(
        models = mapOf(
            "fast-model" to ModelMetadata(type = "fake", costPer1k = 0.001, contextWindow = 128000),
            "cheap-model" to ModelMetadata(type = "fake", costPer1k = 0.0, contextWindow = 128000)
        )
    )

    private val providers = mapOf(
        "provider-fast" to ProviderConfig(type = "fake", modelRef = "fast-model"),
        "provider-cheap" to ProviderConfig(type = "fake", modelRef = "cheap-model")
    )

    private val credentialResolver = CredentialResolver()

    private lateinit var reportFile: File

    @BeforeEach
    fun setUp() {
        reportFile = File.createTempFile("aiorka-benchmark-test", ".md")
        reportFile.deleteOnExit()
    }

    @AfterEach
    fun tearDown() {
        reportFile.delete()
    }

    // ── Results collection ────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `runner collects one result per provider per prompt`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "hello")
        val runner = BenchmarkRunner(providers, listOf(adapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("Prompt A", "Prompt B"),
            outputPath = reportFile.absolutePath
        )

        val report = runner.run(config)

        // 2 providers × 2 prompts = 4 results
        assertEquals(4, report.results.size)
    }

    @Test
    @Order(2)
    fun `all results succeed when adapter does not fail`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "good response")
        val runner = BenchmarkRunner(providers, listOf(adapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("ping"),
            outputPath = reportFile.absolutePath
        )

        val report = runner.run(config)

        assertTrue(report.results.all { it.succeeded })
        assertTrue(report.results.all { it.response == "good response" })
        assertTrue(report.results.all { it.error == null })
    }

    @Test
    @Order(3)
    fun `failed provider produces error result without throwing`() = runBlocking {
        val failingAdapter = FakeAdapter(failTimes = Int.MAX_VALUE, supportedType = "fake")
        val runner = BenchmarkRunner(providers, listOf(failingAdapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("test"),
            outputPath = reportFile.absolutePath
        )

        val report = runner.run(config)

        assertTrue(report.results.all { !it.succeeded })
        assertTrue(report.results.all { it.error != null })
        assertTrue(report.results.all { it.response == null })
    }

    @Test
    @Order(4)
    fun `provider with no matching adapter records an error result`() = runBlocking {
        val wrongTypeAdapter = FakeAdapter(supportedType = "wrong-type")
        val runner = BenchmarkRunner(providers, listOf(wrongTypeAdapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("test"),
            outputPath = reportFile.absolutePath
        )

        val report = runner.run(config)

        // Both providers have type "fake", adapter supports "wrong-type" → all fail
        assertTrue(report.results.all { !it.succeeded })
        assertTrue(report.results.all { it.error!!.contains("No adapter") })
    }

    // ── Cost calculation ──────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `cost is computed from registry cost_per_1k and token count`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "ok")
        val runner = BenchmarkRunner(
            mapOf("provider-fast" to ProviderConfig(type = "fake", modelRef = "fast-model")),
            listOf(adapter),
            credentialResolver,
            registry
        )
        val config = BenchmarkConfig(prompts = listOf("test"), outputPath = reportFile.absolutePath)

        val report = runner.run(config)
        val result = report.results.single()

        // FakeAdapter returns tokensUsed = 10, fast-model costs $0.001/1k → 10 * 0.001 / 1000 = $0.00001
        assertNotNull(result.estimatedCostUsd)
        assertEquals(10 * 0.001 / 1000.0, result.estimatedCostUsd!!, 1e-10)
    }

    @Test
    @Order(6)
    fun `zero cost_per_1k model reports zero cost`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "ok")
        val runner = BenchmarkRunner(
            mapOf("provider-cheap" to ProviderConfig(type = "fake", modelRef = "cheap-model")),
            listOf(adapter),
            credentialResolver,
            registry
        )
        val config = BenchmarkConfig(prompts = listOf("test"), outputPath = reportFile.absolutePath)

        val report = runner.run(config)

        assertEquals(0.0, report.results.single().estimatedCostUsd)
    }

    // ── Headers passthrough ───────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `resolved headers from provider headersEnv are passed to adapter`() = runBlocking {
        val resolver = CredentialResolver(injectedKeys = mapOf("MY_HEADER_VAR" to "token-123"))
        val provider = ProviderConfig(
            type = "fake",
            modelRef = "fast-model",
            headersEnv = mapOf("X-Custom-Auth" to "MY_HEADER_VAR")
        )
        val adapter = FakeAdapter()
        val runner = BenchmarkRunner(
            mapOf("p" to provider),
            listOf(adapter),
            resolver,
            registry
        )
        val config = BenchmarkConfig(prompts = listOf("test"), outputPath = reportFile.absolutePath)

        runner.run(config)

        assertEquals("token-123", adapter.lastResolvedHeaders["X-Custom-Auth"])
    }

    // ── File output ───────────────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `report file is written to the specified outputPath`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "written")
        val runner = BenchmarkRunner(providers, listOf(adapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("write test"),
            outputPath = reportFile.absolutePath
        )

        val report = runner.run(config)

        assertEquals(reportFile.absolutePath, report.outputPath)
        assertTrue(reportFile.exists())
        assertTrue(reportFile.length() > 0)
    }

    @Test
    @Order(9)
    fun `report file content is valid markdown with expected structure`() = runBlocking {
        val adapter = FakeAdapter(responseContent = "model replied")
        val runner = BenchmarkRunner(providers, listOf(adapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("Explain something."),
            outputPath = reportFile.absolutePath
        )

        runner.run(config)

        val content = reportFile.readText()
        assertTrue(content.startsWith("# aiOrka Benchmark Report"))
        assertTrue(content.contains("## Prompt 1 of 1"))
        assertTrue(content.contains("## Overall Summary"))
        assertTrue(content.contains("provider-fast"))
        assertTrue(content.contains("provider-cheap"))
        assertTrue(content.contains("model replied"))
    }

    // ── Provider filtering ────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `only specified providerIds are benchmarked`() = runBlocking {
        val adapter = FakeAdapter()
        val runner = BenchmarkRunner(providers, listOf(adapter), credentialResolver, registry)
        val config = BenchmarkConfig(
            prompts = listOf("test"),
            providerIds = listOf("provider-cheap"),
            outputPath = reportFile.absolutePath
        )

        // BenchmarkRunner receives providers already filtered by AiOrka.benchmark(),
        // so pass only the filtered subset directly
        val filteredRunner = BenchmarkRunner(
            mapOf("provider-cheap" to providers["provider-cheap"]!!),
            listOf(adapter),
            credentialResolver,
            registry
        )
        val report = filteredRunner.run(config)

        assertEquals(1, report.results.size)
        assertEquals("provider-cheap", report.results.single().providerId)
    }
}
