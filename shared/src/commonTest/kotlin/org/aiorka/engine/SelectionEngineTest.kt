package org.aiorka.engine

import kotlinx.coroutines.test.runTest
import org.aiorka.models.*
import org.aiorka.monitoring.HealthMonitor
import kotlin.test.*

class SelectionEngineTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val registry = ModelRegistry(
        models = mapOf(
            "qwen3.5:9b" to ModelMetadata(
                type = "local",
                capabilities = listOf("fast-chat", "json_mode", "tools"),
                costPer1k = 0.0,
                contextWindow = 128000,
                scores = mapOf("logic" to 0.78, "speed" to 0.92)
            ),
            "gemini-3.1-flash" to ModelMetadata(
                type = "cloud",
                capabilities = listOf("extreme-speed", "vision", "json_mode"),
                costPer1k = 0.0001,
                contextWindow = 1000000,
                scores = mapOf("logic" to 0.82, "speed" to 0.96)
            ),
            "claude-4.6-sonnet" to ModelMetadata(
                type = "cloud",
                capabilities = listOf("agentic-coding", "tools", "json_mode"),
                costPer1k = 0.003,
                contextWindow = 1000000,
                scores = mapOf("logic" to 0.95, "speed" to 0.75)
            ),
            "deepseek-r1-distill-llama-8b" to ModelMetadata(
                type = "local",
                capabilities = listOf("reasoning", "chain-of-thought"),
                costPer1k = 0.0,
                contextWindow = 128000,
                scores = mapOf("logic" to 0.82, "speed" to 0.80)
            )
        )
    )

    private val providers = mapOf(
        "local-qwen" to ProviderConfig(
            type = "ollama",
            modelRef = "qwen3.5:9b",
            endpoint = "http://localhost:11434"
        ),
        "google-flash" to ProviderConfig(
            type = "gemini",
            modelRef = "gemini-3.1-flash",
            apiKeyEnv = "GEMINI_API_KEY"
        ),
        "anthropic-sonnet" to ProviderConfig(
            type = "anthropic",
            modelRef = "claude-4.6-sonnet",
            apiKeyEnv = "ANTHROPIC_API_KEY"
        ),
        "local-deepseek" to ProviderConfig(
            type = "ollama",
            modelRef = "deepseek-r1-distill-llama-8b",
            endpoint = "http://localhost:11434"
        )
    )

    private fun engine(
        monitor: HealthMonitor = HealthMonitor(),
        credentialCheck: (String, ProviderConfig) -> Boolean = { _, _ -> true }
    ) = SelectionEngine(registry, monitor, credentialCheck)

    // -------------------------------------------------------------------------
    // Stage 1: Candidate resolution
    // -------------------------------------------------------------------------

    @Test
    fun `stage1 maps policy selection to provider configs`() {
        val policy = SelectionPolicy(strategy = "first", selection = listOf("local-qwen", "google-flash"))
        val (id, cfg) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
        assertEquals("qwen3.5:9b", cfg.modelRef)
    }

    @Test
    fun `stage1 silently drops unknown provider IDs`() {
        val policy = SelectionPolicy(strategy = "first", selection = listOf("nonexistent", "local-qwen"))
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `unknown policyId throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            engine().selectBestProvider("missing", emptyMap(), providers)
        }
        assertTrue(ex.message!!.contains("missing"))
    }

    // -------------------------------------------------------------------------
    // Stage 2: Capability filter
    // -------------------------------------------------------------------------

    @Test
    fun `stage2 filters providers missing required features`() {
        // local-qwen has no "vision" capability
        val policy = SelectionPolicy(
            strategy = "first",
            requirements = PolicyRequirements(features = listOf("vision")),
            selection = listOf("local-qwen", "google-flash")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("google-flash", id)
    }

    @Test
    fun `stage2 filters providers below minContext`() {
        // local-qwen: 128k, google-flash: 1M
        val policy = SelectionPolicy(
            strategy = "first",
            requirements = PolicyRequirements(minContext = 500000),
            selection = listOf("local-qwen", "google-flash")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("google-flash", id)
    }

    @Test
    fun `stage2b local-only connectivity filters cloud providers`() {
        val policy = SelectionPolicy(
            strategy = "first",
            requirements = PolicyRequirements(connectivity = "local-only"),
            selection = listOf("local-qwen", "google-flash", "anthropic-sonnet")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `all providers filtered by capability throws NoValidProviderException`() {
        val policy = SelectionPolicy(
            strategy = "first",
            requirements = PolicyRequirements(features = listOf("web-search")),
            selection = listOf("local-qwen", "google-flash")
        )
        assertFailsWith<NoValidProviderException> {
            engine().selectBestProvider("p", mapOf("p" to policy), providers)
        }
    }

    // -------------------------------------------------------------------------
    // Stage 3: Health and credential filter
    // -------------------------------------------------------------------------

    @Test
    fun `stage3 excludes dead providers`() = runTest {
        val monitor = HealthMonitor()
        repeat(3) { monitor.recordFailure("local-qwen", failureThreshold = 3) }
        val policy = SelectionPolicy(strategy = "first", selection = listOf("local-qwen", "google-flash"))
        val (id, _) = engine(monitor).selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("google-flash", id)
    }

    @Test
    fun `stage3 excludes providers with no credentials`() {
        val policy = SelectionPolicy(strategy = "first", selection = listOf("google-flash", "local-qwen"))
        val (id, _) = engine(
            credentialCheck = { id, _ -> id != "google-flash" }
        ).selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `all providers dead throws NoValidProviderException`() = runTest {
        val monitor = HealthMonitor()
        listOf("local-qwen", "google-flash").forEach { id ->
            repeat(3) { monitor.recordFailure(id, failureThreshold = 3) }
        }
        val policy = SelectionPolicy(strategy = "first", selection = listOf("local-qwen", "google-flash"))
        assertFailsWith<NoValidProviderException> {
            engine(monitor).selectBestProvider("p", mapOf("p" to policy), providers)
        }
    }

    // -------------------------------------------------------------------------
    // Stage 4: Optimization strategies
    // -------------------------------------------------------------------------

    @Test
    fun `strategy first returns declaration-order winner`() {
        val policy = SelectionPolicy(
            strategy = "first",
            selection = listOf("local-qwen", "google-flash", "anthropic-sonnet")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `strategy least-cost picks cheapest provider`() {
        // local-qwen: 0.0 < google-flash: 0.0001 < anthropic-sonnet: 0.003
        val policy = SelectionPolicy(
            strategy = "least-cost",
            selection = listOf("google-flash", "local-qwen", "anthropic-sonnet")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `strategy quality picks highest logic score`() {
        // anthropic-sonnet: 0.95 > google-flash: 0.82 > local-qwen: 0.78
        val policy = SelectionPolicy(
            strategy = "quality",
            selection = listOf("local-qwen", "google-flash", "anthropic-sonnet")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("anthropic-sonnet", id)
    }

    @Test
    fun `strategy fastest picks provider with lowest median latency`() = runTest {
        val monitor = HealthMonitor()
        monitor.recordSuccess("local-qwen", 450L)
        monitor.recordSuccess("google-flash", 210L)
        monitor.recordSuccess("anthropic-sonnet", 780L)

        val policy = SelectionPolicy(
            strategy = "fastest",
            selection = listOf("local-qwen", "google-flash", "anthropic-sonnet")
        )
        val (id, _) = engine(monitor).selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("google-flash", id)
    }

    @Test
    fun `strategy fastest falls back to first when no latency data`() {
        val policy = SelectionPolicy(
            strategy = "fastest",
            selection = listOf("local-qwen", "google-flash")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("local-qwen", id)
    }

    @Test
    fun `unrecognised strategy key defaults to first`() {
        val policy = SelectionPolicy(
            strategy = "balanced",
            selection = listOf("google-flash", "anthropic-sonnet")
        )
        val (id, _) = engine().selectBestProvider("p", mapOf("p" to policy), providers)
        assertEquals("google-flash", id)
    }

    // -------------------------------------------------------------------------
    // Strategy enum
    // -------------------------------------------------------------------------

    @Test
    fun `Strategy from resolves all known keys`() {
        assertEquals(Strategy.FIRST, Strategy.from("first"))
        assertEquals(Strategy.LEAST_COST, Strategy.from("least-cost"))
        assertEquals(Strategy.FASTEST, Strategy.from("fastest"))
        assertEquals(Strategy.QUALITY, Strategy.from("quality"))
    }

    @Test
    fun `Strategy from unknown key returns FIRST`() {
        assertEquals(Strategy.FIRST, Strategy.from("unknown-strategy"))
    }
}
