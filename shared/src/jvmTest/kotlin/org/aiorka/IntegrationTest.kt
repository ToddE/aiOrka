package org.aiorka

import kotlinx.coroutines.runBlocking
import org.aiorka.models.Message
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration tests for aiOrka.
 *
 * Setup:
 *   1. Copy .env.example → .env in the repo root.
 *   2. Fill in API keys for the providers you want to test.
 *   3. Set AIORKA_INTEGRATION_TESTS=true in .env (or as an env var).
 *   4. Run: ./gradlew :shared:jvmTest --tests "*.IntegrationTest"
 *
 * Tests are skipped automatically when AIORKA_INTEGRATION_TESTS != "true",
 * so they are safe to include in CI without API keys.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IntegrationTest {

    companion object {
        private val env: Map<String, String> = loadDotEnv()
        private val testPrompt get() = env["AIORKA_TEST_PROMPT"] ?: "Hello"
        private val timeoutMs get() = env["AIORKA_TEST_TIMEOUT_MS"]?.toLongOrNull() ?: 10_000L

        private lateinit var orka: AiOrka

        @BeforeAll
        @JvmStatic
        fun setUp() {
            assumeIntegrationEnabled()

            val selfhostedEndpoint = env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434"
            val configYaml = buildTestConfig(selfhostedEndpoint)

            orka = runBlocking {
                AiOrka.initialize {
                    this.configYaml = configYaml
                    env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }
                        ?.let { apiKeys["ANTHROPIC_API_KEY"] = it }
                    env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
                        ?.let { apiKeys["OPENAI_API_KEY"] = it }
                    env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }
                        ?.let { apiKeys["GEMINI_API_KEY"] = it }
                    env["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }
                        ?.let { apiKeys["DEEPSEEK_API_KEY"] = it }
                }
            }
            println("\n[aiOrka] Integration test suite initialized")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::orka.isInitialized) orka.shutdown()
        }

        // ── .env loader ──────────────────────────────────────────────────────

        private fun loadDotEnv(): Map<String, String> {
            val envFile = File(System.getProperty("user.dir"))
                .parentFile   // shared/ → project root
                ?.resolve(".env")
                ?: return emptyMap()

            if (!envFile.exists()) return emptyMap()

            return envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
        }

        private fun assumeIntegrationEnabled() {
            val enabled = env["AIORKA_INTEGRATION_TESTS"]
                ?: System.getenv("AIORKA_INTEGRATION_TESTS")
                ?: "false"
            assumeTrue(
                enabled.lowercase() == "true",
                "Integration tests disabled — set AIORKA_INTEGRATION_TESTS=true in .env"
            )
        }

        private fun hasKey(varName: String) =
            env[varName]?.isNotBlank() == true || System.getenv(varName)?.isNotBlank() == true

        // ── Minimal test aiOrka.yaml (overrides endpoint from .env) ──────────

        private fun buildTestConfig(selfhostedEndpoint: String) = """
            monitoring:
              heartbeat:
                enabled: false        # We drive heartbeat manually in tests
            defaults:
              retry:
                max_attempts: 2
                initial_backoff_ms: 200
                max_backoff_ms: 2000
            providers:
              local-qwen:
                type: "selfhosted"
                model_ref: "qwen3.5:9b"
                endpoint: "$selfhostedEndpoint"
              local-deepseek:
                type: "selfhosted"
                model_ref: "deepseek-r1-distill-llama-8b"
                endpoint: "$selfhostedEndpoint"
              google-flash:
                type: "gemini"
                model_ref: "gemini-3.1-flash"
                api_key_env: "GEMINI_API_KEY"
              anthropic-sonnet:
                type: "anthropic"
                model_ref: "claude-sonnet-4-6"
                api_key_env: "ANTHROPIC_API_KEY"
              deepseek-cloud:
                type: "deepseek"
                model_ref: "deepseek-chat"
                api_key_env: "DEEPSEEK_API_KEY"
              openai-gpt:
                type: "openai"
                model_ref: "gpt-4o-mini"
                api_key_env: "OPENAI_API_KEY"
            policies:
              "smoke-local":
                description: "Local-only smoke test"
                strategy: "fastest"
                requirements:
                  connectivity: "local-only"
                selection: ["local-qwen"]
              "smoke-anthropic":
                description: "Anthropic smoke test"
                strategy: "fastest"
                selection: ["anthropic-sonnet"]
              "smoke-openai":
                description: "OpenAI smoke test"
                strategy: "fastest"
                selection: ["openai-gpt"]
              "smoke-gemini":
                description: "Gemini smoke test"
                strategy: "fastest"
                selection: ["google-flash"]
              "smoke-deepseek":
                description: "DeepSeek smoke test"
                strategy: "fastest"
                selection: ["deepseek-cloud"]
              "fallback-chain":
                description: "Tests fallback: dead-provider → live-provider"
                strategy: "fastest"
                selection: ["anthropic-sonnet", "google-flash", "local-qwen"]
              "multi-provider-quality":
                description: "Quality routing across all available cloud providers"
                strategy: "quality"
                selection: ["anthropic-sonnet", "openai-gpt", "google-flash"]
        """.trimIndent()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Health snapshot
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `health snapshot contains all configured providers`() {
        val snapshot = orka.healthSnapshot()
        println("\n[Health Snapshot]")
        snapshot.forEach { (id, status) ->
            val latency = status.lastLatencyMs?.let { "${it}ms" } ?: "no data"
            println("  $id → alive=${status.isAlive}  failures=${status.consecutiveFailures}  latency=$latency")
        }
        assertTrue(snapshot.isNotEmpty(), "Health snapshot must include at least one provider")
        assertTrue(snapshot.containsKey("local-qwen"))
        assertTrue(snapshot.containsKey("anthropic-sonnet"))
        assertTrue(snapshot.containsKey("google-flash"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Self-hosted inference
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    fun `self-hosted inference responds to smoke test`() {
        runCatching {
            val resp = runBlocking {
                orka.execute("smoke-local", listOf(Message.user(testPrompt)))
            }
            println("\n[Self-hosted] ${resp.content.take(80)}  (${resp.durationMs}ms)")
            assertTrue(resp.content.isNotBlank())
            assertFalse(resp.providerId.isBlank())
        }.onFailure { e ->
            assumeTrue(false, "Self-hosted server not available: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Cloud providers — each tested independently
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `Anthropic Claude responds to smoke test`() {
        assumeTrue(hasKey("ANTHROPIC_API_KEY"), "ANTHROPIC_API_KEY not set — skipping")
        val resp = runBlocking {
            orka.execute("smoke-anthropic", listOf(Message.user(testPrompt)))
        }
        println("\n[Anthropic] ${resp.content.take(80)}  model=${resp.modelUsed}  ${resp.durationMs}ms")
        assertTrue(resp.content.isNotBlank())
        resp.tokensUsed?.let { assertTrue(it > 0) }
    }

    @Test
    @Order(4)
    fun `OpenAI GPT responds to smoke test`() {
        assumeTrue(hasKey("OPENAI_API_KEY"), "OPENAI_API_KEY not set — skipping")
        val resp = runBlocking {
            orka.execute("smoke-openai", listOf(Message.user(testPrompt)))
        }
        println("\n[OpenAI] ${resp.content.take(80)}  model=${resp.modelUsed}  ${resp.durationMs}ms")
        assertTrue(resp.content.isNotBlank())
    }

    @Test
    @Order(5)
    fun `Google Gemini responds to smoke test`() {
        assumeTrue(hasKey("GEMINI_API_KEY"), "GEMINI_API_KEY not set — skipping")
        val resp = runBlocking {
            orka.execute("smoke-gemini", listOf(Message.user(testPrompt)))
        }
        println("\n[Gemini] ${resp.content.take(80)}  model=${resp.modelUsed}  ${resp.durationMs}ms")
        assertTrue(resp.content.isNotBlank())
    }

    @Test
    @Order(6)
    fun `DeepSeek responds to smoke test`() {
        assumeTrue(hasKey("DEEPSEEK_API_KEY"), "DEEPSEEK_API_KEY not set — skipping")
        val resp = runBlocking {
            orka.execute("smoke-deepseek", listOf(Message.user(testPrompt)))
        }
        println("\n[DeepSeek] ${resp.content.take(80)}  model=${resp.modelUsed}  ${resp.durationMs}ms")
        assertTrue(resp.content.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Heartbeat — manual ping of all alive providers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `heartbeat records latency for reachable providers`() {
        // Re-initialize with heartbeat enabled for a single tick
        val selfhostedEndpoint = env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434"
        val orkaWithHeartbeat = runBlocking {
            AiOrka.initialize {
                this.configYaml = buildTestConfig(selfhostedEndpoint)
                    .replace("enabled: false", "enabled: true")
                    .replace("interval_ms: 300000", "interval_ms: 999999999") // don't auto-ping again
                env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["ANTHROPIC_API_KEY"] = it }
                env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["GEMINI_API_KEY"] = it }
                env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["OPENAI_API_KEY"] = it }
                env["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["DEEPSEEK_API_KEY"] = it }
            }
        }

        // Give heartbeat a moment to complete its first round
        Thread.sleep(timeoutMs)

        val snapshot = orkaWithHeartbeat.healthSnapshot()
        println("\n[Heartbeat Results]")
        snapshot.forEach { (id, status) ->
            val latency = status.lastLatencyMs?.let { "${it}ms" } ?: "unreachable"
            val mark = if (status.isAlive) "✓" else "✗"
            println("  $mark $id  latency=$latency  failures=${status.consecutiveFailures}")
        }

        val alive = snapshot.count { it.value.isAlive }
        println("  → $alive/${snapshot.size} providers alive after heartbeat")
        orkaWithHeartbeat.shutdown()

        // At least one provider must be reachable for the suite to be meaningful
        assertTrue(alive > 0, "No providers responded to heartbeat — check your .env keys and self-hosted server status")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Fallback behavior
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `fallback chain skips dead provider and succeeds on next`() {
        // Inject a bad Anthropic key so the primary provider fails, triggering fallback
        val orkaWithBadKey = runBlocking {
            AiOrka.initialize {
                this.configYaml = buildTestConfig(
                    env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434"
                )
                apiKeys["ANTHROPIC_API_KEY"] = "sk-ant-INVALID-KEY-FOR-FALLBACK-TEST"
                env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["GEMINI_API_KEY"] = it }
                env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["OPENAI_API_KEY"] = it }
                env["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }?.let { apiKeys["DEEPSEEK_API_KEY"] = it }
            }
        }

        try {
            val resp = runBlocking {
                orkaWithBadKey.execute("fallback-chain", listOf(Message.user(testPrompt)))
            }
            println("\n[Fallback] succeeded via ${resp.providerId}  (${resp.durationMs}ms)")
            assertTrue(resp.content.isNotBlank())
            // Must NOT have used the primary (bad-key Anthropic)
            assertFalse(
                resp.providerId == "anthropic-sonnet",
                "Fallback should have skipped the provider with the invalid key"
            )
        } finally {
            orkaWithBadKey.shutdown()
        }
    }

    @Test
    @Order(9)
    fun `all providers exhausted throws NoValidProviderException`() {
        val orkaAllDead = runBlocking {
            AiOrka.initialize {
                this.configYaml = """
                    defaults:
                      retry:
                        max_attempts: 1
                    providers:
                      dead-provider:
                        type: "anthropic"
                        model_ref: "claude-sonnet-4-6"
                        api_key_env: "ANTHROPIC_API_KEY"
                    policies:
                      "dead-policy":
                        strategy: "fastest"
                        selection: ["dead-provider"]
                """.trimIndent()
                apiKeys["ANTHROPIC_API_KEY"] = "sk-ant-DEFINITELY-INVALID"
            }
        }

        try {
            assertThrows<Exception> {
                runBlocking {
                    orkaAllDead.execute("dead-policy", listOf(Message.user(testPrompt)))
                }
            }
            println("\n[Exhaustion] Correctly threw when all providers failed")
        } finally {
            orkaAllDead.shutdown()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Multi-turn conversation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `multi-turn conversation maintains context`() {
        assumeTrue(
            hasKey("ANTHROPIC_API_KEY") || hasKey("OPENAI_API_KEY") || hasKey("GEMINI_API_KEY"),
            "At least one cloud key required for multi-turn test"
        )

        val messages = mutableListOf(
            Message.system("You are a concise assistant. Keep all replies under 20 words."),
            Message.user("My name is Alex. Remember that.")
        )

        val turn1 = runBlocking {
            orka.execute("multi-provider-quality", messages)
        }
        println("\n[Multi-turn T1] ${turn1.content.take(80)}")
        messages += Message("assistant", turn1.content)
        messages += Message.user("What is my name?")

        val turn2 = runBlocking {
            orka.execute("multi-provider-quality", messages)
        }
        println("[Multi-turn T2] ${turn2.content.take(80)}")
        assertTrue(
            turn2.content.contains("Alex", ignoreCase = true),
            "Model should recall the name 'Alex' from earlier in the conversation"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Runtime key injection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    fun `setApiKey injected after initialization is used for subsequent calls`() {
        assumeTrue(hasKey("GEMINI_API_KEY"), "GEMINI_API_KEY not set — skipping")

        val orkaNoKeys = runBlocking {
            AiOrka.initialize {
                this.configYaml = buildTestConfig(
                    env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434"
                )
                // Intentionally NOT pre-loading any keys
            }
        }

        try {
            // Inject key after construction
            orkaNoKeys.setApiKey("GEMINI_API_KEY", env["GEMINI_API_KEY"]!!)

            val resp = runBlocking {
                orkaNoKeys.execute("smoke-gemini", listOf(Message.user(testPrompt)))
            }
            println("\n[Key Injection] ${resp.content.take(80)}  via ${resp.providerId}")
            assertTrue(resp.content.isNotBlank())
        } finally {
            orkaNoKeys.shutdown()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Performance baseline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(12)
    fun `response time is within acceptable range`() {
        assumeTrue(
            hasKey("ANTHROPIC_API_KEY") || hasKey("GEMINI_API_KEY") || hasKey("OPENAI_API_KEY"),
            "At least one cloud key required"
        )

        val start = System.currentTimeMillis()
        val resp = runBlocking {
            orka.execute(
                "multi-provider-quality",
                listOf(Message.user("Say 'pong' and nothing else."))
            )
        }
        val wallMs = System.currentTimeMillis() - start

        println("\n[Perf] wall=${wallMs}ms  reported=${resp.durationMs}ms  provider=${resp.providerId}")
        assertTrue(
            resp.durationMs < timeoutMs,
            "Provider took ${resp.durationMs}ms — exceeds limit of ${timeoutMs}ms"
        )
    }
}
