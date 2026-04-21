package org.aiorka.resources

import kotlin.test.*

class YamlParserTest {

    // -------------------------------------------------------------------------
    // ModelRegistry parsing
    // -------------------------------------------------------------------------

    @Test
    fun `parses model registry with all fields`() {
        val yaml = """
            models:
              "qwen3.5:9b":
                type: "local"
                capabilities: ["fast-chat", "json_mode"]
                cost_per_1k: 0.00
                context_window: 128000
                scores:
                  logic: 0.78
                  speed: 0.92
        """.trimIndent()

        val registry = YamlParser.parseModelRegistry(yaml)

        val model = registry.models["qwen3.5:9b"]
        assertNotNull(model)
        assertEquals("local", model.type)
        assertEquals(listOf("fast-chat", "json_mode"), model.capabilities)
        assertEquals(0.0, model.costPer1k)
        assertEquals(128000, model.contextWindow)
        assertEquals(0.78, model.scores["logic"])
        assertEquals(0.92, model.scores["speed"])
    }

    @Test
    fun `parses model registry with multiple models`() {
        val yaml = """
            models:
              "model-a":
                type: "cloud"
                cost_per_1k: 0.001
                context_window: 8192
                scores: {}
              "model-b":
                type: "local"
                cost_per_1k: 0.00
                context_window: 4096
                scores: {}
        """.trimIndent()

        val registry = YamlParser.parseModelRegistry(yaml)
        assertEquals(2, registry.models.size)
        assertNotNull(registry.models["model-a"])
        assertNotNull(registry.models["model-b"])
    }

    @Test
    fun `model registry uses defaults for missing optional fields`() {
        val yaml = """
            models:
              "minimal-model":
                type: "cloud"
        """.trimIndent()

        val registry = YamlParser.parseModelRegistry(yaml)
        val model = registry.models["minimal-model"]!!
        assertEquals(emptyList(), model.capabilities)
        assertEquals(0.0, model.costPer1k)
        assertEquals(4096, model.contextWindow)
        assertEquals(emptyMap(), model.scores)
    }

    // -------------------------------------------------------------------------
    // policies.yaml parsing (wrapped in "policies:" key)
    // -------------------------------------------------------------------------

    @Test
    fun `parses policies file with selection list and requirements`() {
        val yaml = """
            policies:
              "fast-chat":
                description: "Low latency chat"
                strategy: "fastest"
                requirements:
                  min_context: 4096
                selection: ["provider-a", "provider-b"]
        """.trimIndent()

        val policies = YamlParser.parsePoliciesFile(yaml)

        val policy = policies["fast-chat"]
        assertNotNull(policy)
        assertEquals("fastest", policy.strategy)
        assertEquals(4096, policy.requirements.minContext)
        assertEquals(listOf("provider-a", "provider-b"), policy.selection)
    }

    @Test
    fun `parses policies file with retry override`() {
        val yaml = """
            policies:
              "critical-task":
                strategy: "quality"
                selection: ["provider-a"]
                retry_override:
                  max_attempts: 5
                  initial_backoff_ms: 1000
                  max_backoff_ms: 10000
                  strategy: "exponential"
        """.trimIndent()

        val policies = YamlParser.parsePoliciesFile(yaml)
        val retryOverride = policies["critical-task"]?.retryOverride
        assertNotNull(retryOverride)
        assertEquals(5, retryOverride.maxAttempts)
        assertEquals(1000L, retryOverride.initialBackoffMs)
    }

    @Test
    fun `parses policies file with local-only connectivity`() {
        val yaml = """
            policies:
              "private":
                strategy: "least-cost"
                requirements:
                  connectivity: "local-only"
                selection: ["local-provider"]
        """.trimIndent()

        val policies = YamlParser.parsePoliciesFile(yaml)
        assertEquals("local-only", policies["private"]?.requirements?.connectivity)
    }

    @Test
    fun `empty policies file returns empty map`() {
        val yaml = "policies: {}"
        val policies = YamlParser.parsePoliciesFile(yaml)
        assertTrue(policies.isEmpty())
    }

    // -------------------------------------------------------------------------
    // AppConfig parsing (aiOrka.yaml)
    // -------------------------------------------------------------------------

    @Test
    fun `parses app config with monitoring settings`() {
        val yaml = """
            monitoring:
              heartbeat:
                enabled: true
                interval_ms: 60000
                timeout_ms: 2000
                failure_threshold: 3
                minimal_prompt: "hi"
              telemetry:
                enabled: true
                sample_rate: 0.5
        """.trimIndent()

        val config = YamlParser.parseAppConfig(yaml)
        val hb = config.monitoring?.heartbeat
        assertNotNull(hb)
        assertTrue(hb.enabled)
        assertEquals(60000L, hb.intervalMs)
        assertEquals(2000L, hb.timeoutMs)
        assertEquals(3, hb.failureThreshold)
        assertEquals("hi", hb.minimalPrompt)
        assertEquals(0.5, config.monitoring?.telemetry?.sampleRate)
    }

    @Test
    fun `parses app config with providers map`() {
        val yaml = """
            providers:
              local-qwen:
                type: "ollama"
                model_ref: "qwen3.5:9b"
                endpoint: "http://localhost:11434"
                config:
                  num_ctx: "32768"
              google-flash:
                type: "gemini"
                model_ref: "gemini-3.1-flash"
                api_key_env: "GEMINI_API_KEY"
        """.trimIndent()

        val config = YamlParser.parseAppConfig(yaml)
        val ollama = config.providers["local-qwen"]
        assertNotNull(ollama)
        assertEquals("ollama", ollama.type)
        assertEquals("qwen3.5:9b", ollama.modelRef)
        assertEquals("32768", ollama.config["num_ctx"])

        val gemini = config.providers["google-flash"]
        assertNotNull(gemini)
        assertEquals("GEMINI_API_KEY", gemini.apiKeyEnv)
    }

    @Test
    fun `parses app config with defaults retry and caching`() {
        val yaml = """
            defaults:
              retry:
                max_attempts: 4
                initial_backoff_ms: 750
                max_backoff_ms: 8000
                strategy: "exponential"
              caching:
                enabled: true
                ttl_ms: 7200000
        """.trimIndent()

        val config = YamlParser.parseAppConfig(yaml)
        val retry = config.defaults?.retry
        assertNotNull(retry)
        assertEquals(4, retry.maxAttempts)
        assertEquals(750L, retry.initialBackoffMs)
        assertEquals(8000L, retry.maxBackoffMs)
        assertTrue(config.defaults?.caching?.enabled == true)
    }

    @Test
    fun `unknown yaml keys do not cause parsing failure`() {
        // strictMode = false should silently ignore unknown fields
        val yaml = """
            providers:
              test-provider:
                type: "gemini"
                model_ref: "gemini-flash"
                unknown_field_xyz: "this should be ignored"
                another_unknown: 999
        """.trimIndent()

        val config = YamlParser.parseAppConfig(yaml)
        assertNotNull(config.providers["test-provider"])
    }

    @Test
    fun `minimal app config with all defaults`() {
        val config = YamlParser.parseAppConfig("{}")
        assertNull(config.monitoring)
        assertNull(config.defaults)
        assertTrue(config.providers.isEmpty())
        assertTrue(config.policies.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Policy merge behaviour (simulated here, validated in AiOrkaTest)
    // -------------------------------------------------------------------------

    @Test
    fun `kotlin map plus operator user policies override library policies`() {
        val library = mapOf(
            "fast-chat" to org.aiorka.models.SelectionPolicy(strategy = "fastest", selection = listOf("a", "b")),
            "apex-logic" to org.aiorka.models.SelectionPolicy(strategy = "quality", selection = listOf("c"))
        )
        val user = mapOf(
            "fast-chat" to org.aiorka.models.SelectionPolicy(strategy = "least-cost", selection = listOf("x")),
            "custom-policy" to org.aiorka.models.SelectionPolicy(strategy = "first", selection = listOf("y"))
        )
        val merged = library + user
        assertEquals("least-cost", merged["fast-chat"]?.strategy)   // user wins
        assertEquals("quality", merged["apex-logic"]?.strategy)      // library survives
        assertNotNull(merged["custom-policy"])                         // user supplement added
        assertEquals(3, merged.size)
    }
}
