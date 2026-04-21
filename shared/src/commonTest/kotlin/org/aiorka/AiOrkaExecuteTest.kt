package org.aiorka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.aiorka.adapters.AlwaysFailAdapter
import org.aiorka.adapters.FakeAdapter
import org.aiorka.credentials.CredentialResolver
import org.aiorka.engine.SelectionEngine
import org.aiorka.models.*
import org.aiorka.monitoring.HeartbeatManager
import org.aiorka.monitoring.HealthMonitor
import kotlin.test.*

/**
 * Integration tests for AiOrka.execute() using in-memory fakes.
 * Bypasses resource loading (Res.readBytes) by constructing AiOrka directly
 * via its internal constructor.
 */
class AiOrkaExecuteTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val registry = ModelRegistry(
        models = mapOf(
            "fake-model" to ModelMetadata(
                type = "fake",
                capabilities = listOf("chat"),
                costPer1k = 0.001,
                contextWindow = 128000,
                scores = mapOf("logic" to 0.90)
            ),
            "cheap-model" to ModelMetadata(
                type = "fake",
                capabilities = listOf("chat"),
                costPer1k = 0.0001,
                contextWindow = 128000,
                scores = mapOf("logic" to 0.70)
            )
        )
    )

    private val providers = mapOf(
        "provider-a" to ProviderConfig(type = "fake", modelRef = "fake-model"),
        "provider-b" to ProviderConfig(type = "fake", modelRef = "cheap-model")
    )

    private val defaultPolicy = SelectionPolicy(
        strategy = "first",
        selection = listOf("provider-a")
    )

    private fun buildOrka(
        policies: Map<String, SelectionPolicy> = mapOf("default" to defaultPolicy),
        adapter: FakeAdapter = FakeAdapter(),
        healthMonitor: HealthMonitor = HealthMonitor(),
        retryConfig: RetryConfig = RetryConfig(
            maxAttempts = 3,
            initialBackoffMs = 1,   // 1ms so tests are fast
            maxBackoffMs = 5
        ),
        credentialResolver: CredentialResolver = CredentialResolver()
    ): AiOrka {
        val selectionEngine = SelectionEngine(registry, healthMonitor) { _, _ -> true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val heartbeatManager = HeartbeatManager(healthMonitor, listOf(adapter), credentialResolver, scope)

        return AiOrka(
            mergedPolicies = policies,
            mergedProviders = providers,
            selectionEngine = selectionEngine,
            adapters = listOf(adapter),
            heartbeatManager = heartbeatManager,
            healthMonitor = healthMonitor,
            credentialResolver = credentialResolver,
            retryConfig = retryConfig,
            scope = scope
        )
    }

    // -------------------------------------------------------------------------
    // Basic execution
    // -------------------------------------------------------------------------

    @Test
    fun `execute returns response from winning provider`() = runTest {
        val adapter = FakeAdapter(responseContent = "hello world")
        val orka = buildOrka(adapter = adapter)
        val response = orka.execute("default", listOf(Message.user("hi")))
        assertEquals("hello world", response.content)
        assertEquals("provider-a", response.metadata.providerId)
        assertEquals("fake-model", response.metadata.modelUsed)
    }

    @Test
    fun `execute throws IllegalArgumentException for unknown policy`() = runTest {
        val orka = buildOrka()
        assertFailsWith<IllegalArgumentException> {
            orka.execute("nonexistent-policy", listOf(Message.user("hi")))
        }
    }

    // -------------------------------------------------------------------------
    // Retry logic
    // -------------------------------------------------------------------------

    @Test
    fun `execute succeeds after transient failures within retry limit`() = runTest {
        // Fails first 2 times, succeeds on 3rd
        val adapter = FakeAdapter(responseContent = "recovered", failTimes = 2)
        val orka = buildOrka(
            adapter = adapter,
            retryConfig = RetryConfig(maxAttempts = 3, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        val response = orka.execute("default", listOf(Message.user("test")))
        assertEquals("recovered", response.content)
        assertEquals(3, adapter.callCount)
    }

    @Test
    fun `execute throws after exhausting all retry attempts`() = runTest {
        val adapter = FakeAdapter(failTimes = 5)  // Always fails within 3 attempts
        val orka = buildOrka(
            adapter = adapter,
            retryConfig = RetryConfig(maxAttempts = 3, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        assertFailsWith<Exception> {
            orka.execute("default", listOf(Message.user("test")))
        }
        assertEquals(3, adapter.callCount)
    }

    @Test
    fun `execute respects policy retry_override over global config`() = runTest {
        val adapter = FakeAdapter(failTimes = 4)
        val policy = SelectionPolicy(
            strategy = "first",
            selection = listOf("provider-a"),
            retryOverride = RetryConfig(maxAttempts = 5, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        val orka = buildOrka(
            policies = mapOf("high-retry" to policy),
            adapter = adapter,
            retryConfig = RetryConfig(maxAttempts = 2, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        // Global config says 2 attempts, but policy override says 5 → should succeed on attempt 5
        val response = orka.execute("high-retry", listOf(Message.user("test")))
        assertEquals(5, adapter.callCount)
        assertNotNull(response.content)
    }

    @Test
    fun `execute does not retry NoValidProviderException`() = runTest {
        // Policy selection list references a provider not in mergedProviders
        val policy = SelectionPolicy(strategy = "first", selection = listOf("nonexistent-provider"))
        val adapter = FakeAdapter()
        val orka = buildOrka(
            policies = mapOf("bad-policy" to policy),
            adapter = adapter,
            retryConfig = RetryConfig(maxAttempts = 3, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        assertFailsWith<NoValidProviderException> {
            orka.execute("bad-policy", listOf(Message.user("test")))
        }
        // Adapter should never have been called
        assertEquals(0, adapter.callCount)
    }

    // -------------------------------------------------------------------------
    // Health recording
    // -------------------------------------------------------------------------

    @Test
    fun `successful execute records latency in health monitor`() = runTest {
        val monitor = HealthMonitor()
        val orka = buildOrka(healthMonitor = monitor)
        orka.execute("default", listOf(Message.user("hi")))
        // After success, median latency should be available
        assertNotNull(monitor.getMedianLatencyMs("provider-a"))
    }

    @Test
    fun `failed execute records failure in health monitor`() = runTest {
        val monitor = HealthMonitor()
        monitor.registerProviders(setOf("provider-a"))
        val adapter = FakeAdapter(failTimes = 10)
        val orka = buildOrka(
            adapter = adapter,
            healthMonitor = monitor,
            retryConfig = RetryConfig(maxAttempts = 3, initialBackoffMs = 1, maxBackoffMs = 5)
        )
        runCatching { orka.execute("default", listOf(Message.user("hi"))) }
        // After 3 failures with threshold=3, provider should be dead
        assertFalse(monitor.isAlive("provider-a"))
    }

    // -------------------------------------------------------------------------
    // Policy merging
    // -------------------------------------------------------------------------

    @Test
    fun `user policy overrides library policy with same name`() = runTest {
        val libraryPolicy = SelectionPolicy(strategy = "quality", selection = listOf("provider-b"))
        val userPolicy = SelectionPolicy(strategy = "least-cost", selection = listOf("provider-a"))
        val merged = mapOf("shared-name" to libraryPolicy) + mapOf("shared-name" to userPolicy)

        val adapter = FakeAdapter(responseContent = "from-user-policy")
        val orka = buildOrka(policies = merged, adapter = adapter)
        val response = orka.execute("shared-name", listOf(Message.user("hi")))
        // User policy selects provider-a, not provider-b
        assertEquals("provider-a", response.metadata.providerId)
    }

    @Test
    fun `library policy survives when not overridden by user`() = runTest {
        val policies = mapOf(
            "library-only" to SelectionPolicy(strategy = "first", selection = listOf("provider-a")),
            "user-only" to SelectionPolicy(strategy = "first", selection = listOf("provider-b"))
        )
        val adapter = FakeAdapter()
        val orka = buildOrka(policies = policies, adapter = adapter)
        // Both policies should be callable
        assertNotNull(orka.execute("library-only", listOf(Message.user("hi"))))
        assertNotNull(orka.execute("user-only", listOf(Message.user("hi"))))
    }
}
