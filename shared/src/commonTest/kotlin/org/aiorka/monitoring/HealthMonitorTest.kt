package org.aiorka.monitoring

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HealthMonitorTest {

    @Test
    fun `unknown provider is alive by default`() {
        val monitor = HealthMonitor()
        assertTrue(monitor.isAlive("provider-never-seen"))
    }

    @Test
    fun `registered provider starts alive`() = runTest {
        val monitor = HealthMonitor()
        monitor.registerProviders(setOf("p1", "p2"))
        assertTrue(monitor.isAlive("p1"))
        assertTrue(monitor.isAlive("p2"))
    }

    @Test
    fun `provider stays alive when failures are below threshold`() = runTest {
        val monitor = HealthMonitor()
        monitor.recordFailure("p1", failureThreshold = 3)
        monitor.recordFailure("p1", failureThreshold = 3)
        assertTrue(monitor.isAlive("p1"))  // 2 failures, threshold is 3
    }

    @Test
    fun `provider marked dead when failures reach threshold`() = runTest {
        val monitor = HealthMonitor()
        repeat(3) { monitor.recordFailure("p1", failureThreshold = 3) }
        assertFalse(monitor.isAlive("p1"))
    }

    @Test
    fun `recordSuccess after failures resets to alive`() = runTest {
        val monitor = HealthMonitor()
        repeat(3) { monitor.recordFailure("p1", failureThreshold = 3) }
        assertFalse(monitor.isAlive("p1"))
        monitor.recordSuccess("p1", latencyMs = 100L)
        assertTrue(monitor.isAlive("p1"))
    }

    @Test
    fun `recordSuccess resets consecutive failure count`() = runTest {
        val monitor = HealthMonitor()
        repeat(2) { monitor.recordFailure("p1", failureThreshold = 3) }
        monitor.recordSuccess("p1", latencyMs = 100L)
        // After reset, needs full threshold again to die
        repeat(2) { monitor.recordFailure("p1", failureThreshold = 3) }
        assertTrue(monitor.isAlive("p1"))
    }

    @Test
    fun `getMedianLatencyMs returns null when no samples`() {
        val monitor = HealthMonitor()
        assertNull(monitor.getMedianLatencyMs("p1"))
    }

    @Test
    fun `getMedianLatencyMs returns null for unregistered provider`() {
        val monitor = HealthMonitor()
        assertNull(monitor.getMedianLatencyMs("nonexistent"))
    }

    @Test
    fun `getMedianLatencyMs returns single sample directly`() = runTest {
        val monitor = HealthMonitor()
        monitor.recordSuccess("p1", latencyMs = 250L)
        assertEquals(250L, monitor.getMedianLatencyMs("p1"))
    }

    @Test
    fun `getMedianLatencyMs returns middle value for odd-count samples`() = runTest {
        val monitor = HealthMonitor()
        // Add 5 samples: sorted → [100, 200, 300, 400, 500], median = index 2 = 300
        listOf(300L, 100L, 500L, 200L, 400L).forEach { monitor.recordSuccess("p1", it) }
        assertEquals(300L, monitor.getMedianLatencyMs("p1"))
    }

    @Test
    fun `getMedianLatencyMs returns upper-middle for even-count samples`() = runTest {
        val monitor = HealthMonitor()
        // Add 4 samples: sorted → [100, 200, 300, 400], size/2 = index 2 = 300
        listOf(300L, 100L, 400L, 200L).forEach { monitor.recordSuccess("p1", it) }
        assertEquals(300L, monitor.getMedianLatencyMs("p1"))
    }

    @Test
    fun `latency samples roll over at 20 entries`() = runTest {
        val monitor = HealthMonitor()
        // Record 25 samples
        repeat(25) { i -> monitor.recordSuccess("p1", latencyMs = (i * 10).toLong()) }
        val snapshot = monitor.snapshot()["p1"]
        assertNotNull(snapshot)
        assertEquals(20, snapshot.latencySamples.size)
        // The kept samples should be the last 20 (indices 5..24 → values 50..240)
        assertEquals(50L, snapshot.latencySamples.first())
        assertEquals(240L, snapshot.latencySamples.last())
    }

    @Test
    fun `snapshot reflects current state of all providers`() = runTest {
        val monitor = HealthMonitor()
        monitor.registerProviders(setOf("p1", "p2"))
        monitor.recordSuccess("p1", 120L)
        repeat(3) { monitor.recordFailure("p2", failureThreshold = 3) }

        val snap = monitor.snapshot()
        assertTrue(snap["p1"]!!.isAlive)
        assertFalse(snap["p2"]!!.isAlive)
        assertEquals(120L, snap["p1"]!!.lastLatencyMs)
    }

    @Test
    fun `different providers are tracked independently`() = runTest {
        val monitor = HealthMonitor()
        repeat(3) { monitor.recordFailure("p1", failureThreshold = 3) }
        monitor.recordSuccess("p2", 100L)

        assertFalse(monitor.isAlive("p1"))
        assertTrue(monitor.isAlive("p2"))
    }
}
