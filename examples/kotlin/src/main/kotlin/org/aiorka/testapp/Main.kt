package org.aiorka.testapp

import kotlinx.coroutines.runBlocking
import org.aiorka.AiOrka
import org.aiorka.models.Message
import java.io.File

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    println(banner("aiOrka Kotlin Test App"))

    val env = loadEnv()
    val config = loadConfig(env)

    println("Config  : examples/aiOrka.yaml")
    println("Endpoint: ${env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434 (default)"}")
    println("Keys    : ${activeKeys(env)}\n")

    val orka = runBlocking {
        AiOrka.initialize {
            configYaml = config
            env["ANTHROPIC_API_KEY"]?.ifNotBlank { apiKeys["ANTHROPIC_API_KEY"] = it }
            env["OPENAI_API_KEY"]?.ifNotBlank    { apiKeys["OPENAI_API_KEY"] = it }
            env["GEMINI_API_KEY"]?.ifNotBlank    { apiKeys["GEMINI_API_KEY"] = it }
            env["DEEPSEEK_API_KEY"]?.ifNotBlank  { apiKeys["DEEPSEEK_API_KEY"] = it }
        }
    }

    val prompt = env["AIORKA_TEST_PROMPT"] ?: "Hello! Reply in exactly five words."
    val results = mutableListOf<TestResult>()

    try {
        results += runTest("1. Health Snapshot")    { testHealth(orka) }
        results += runTest("2. Local Inference")    { testPolicy(orka, "test-local", prompt) }
        results += runTest("3. General Chat")       { testPolicy(orka, "test-chat", prompt) }
        results += runTest("4. Reasoning")          { testPolicy(orka, "test-reasoning", prompt) }
        results += runTest("5. Fallback Chain")     { testFallback(orka, env, prompt) }
        results += runTest("6. Multi-turn")         { testMultiTurn(orka) }
        results += runTest("7. Runtime Key Inject") { testKeyInjection(orka, env, prompt) }
    } finally {
        orka.shutdown()
    }

    printSummary(results)
}

// ── Individual tests ──────────────────────────────────────────────────────────

private fun testHealth(orka: AiOrka): String {
    val snapshot = orka.healthSnapshot()
    val lines = snapshot.entries.joinToString("\n") { (id, s) ->
        val latency = s.lastLatencyMs?.let { "${it}ms" } ?: "no data"
        "   ${if (s.isAlive) "✓" else "✗"} $id  failures=${s.consecutiveFailures}  latency=$latency"
    }
    return "${snapshot.size} providers registered:\n$lines"
}

private fun testPolicy(orka: AiOrka, policyId: String, prompt: String): String {
    val resp = runBlocking { orka.execute(policyId, listOf(Message.user(prompt))) }
    return "via ${resp.providerId} (${resp.modelUsed}) ${resp.durationMs}ms\n   \"${resp.content.take(120)}\""
}

private fun testFallback(orka: AiOrka, env: Map<String, String>, prompt: String): String {
    // Build a fresh instance with a bad Anthropic key — first provider in the fallback chain
    val orkaFallback = runBlocking {
        AiOrka.initialize {
            configYaml = loadConfig(env)
            apiKeys["ANTHROPIC_API_KEY"] = "sk-ant-INVALID-FOR-FALLBACK-TEST"
            env["OPENAI_API_KEY"]?.ifNotBlank   { apiKeys["OPENAI_API_KEY"] = it }
            env["GEMINI_API_KEY"]?.ifNotBlank   { apiKeys["GEMINI_API_KEY"] = it }
            env["DEEPSEEK_API_KEY"]?.ifNotBlank { apiKeys["DEEPSEEK_API_KEY"] = it }
        }
    }
    return try {
        val resp = runBlocking { orkaFallback.execute("test-fallback", listOf(Message.user(prompt))) }
        "skipped anthropic-sonnet (bad key) → landed on ${resp.providerId} (${resp.durationMs}ms)\n   \"${resp.content.take(120)}\""
    } finally {
        orkaFallback.shutdown()
    }
}

private fun testMultiTurn(orka: AiOrka): String {
    val messages = mutableListOf(
        Message("system", "You are a concise assistant. Keep replies under 15 words."),
        Message.user("My favourite colour is indigo. Remember that.")
    )
    val t1 = runBlocking { orka.execute("test-chat", messages) }
    messages += Message("assistant", t1.content)
    messages += Message.user("What is my favourite colour?")
    val t2 = runBlocking { orka.execute("test-chat", messages) }
    val remembered = t2.content.contains("indigo", ignoreCase = true)
    return "context preserved=$remembered  via ${t2.providerId}\n   Turn 2: \"${t2.content.take(120)}\""
}

private fun testKeyInjection(orka: AiOrka, env: Map<String, String>, prompt: String): String {
    val key = env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }
        ?: return "SKIPPED — GEMINI_API_KEY not set"
    // Create a keyless instance then inject at runtime
    val noKeys = runBlocking {
        AiOrka.initialize { configYaml = loadConfig(env) }
    }
    return try {
        noKeys.setApiKey("GEMINI_API_KEY", key)
        val resp = runBlocking { noKeys.execute("test-chat", listOf(Message.user(prompt))) }
        "key injected after init → ${resp.providerId} responded in ${resp.durationMs}ms"
    } finally {
        noKeys.shutdown()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private data class TestResult(val name: String, val passed: Boolean, val detail: String)

private fun runTest(name: String, block: () -> String): TestResult {
    print("Running: $name ... ")
    return try {
        val detail = block()
        println("PASS")
        TestResult(name, true, detail)
    } catch (e: Exception) {
        println("FAIL  ${e.message?.take(80)}")
        TestResult(name, false, e.message ?: "exception")
    }
}

private fun printSummary(results: List<TestResult>) {
    println(banner("Results"))
    results.forEach { r ->
        val mark = if (r.passed) "✓ PASS" else "✗ FAIL"
        println("$mark  ${r.name}")
        println("       ${r.detail.replace("\n", "\n       ")}")
    }
    val passed = results.count { it.passed }
    println("\n${passed}/${results.size} tests passed")
}

private fun loadEnv(): Map<String, String> {
    // Search upward from the working directory for .env
    var dir = File(System.getProperty("user.dir"))
    repeat(4) {
        val f = dir.resolve(".env")
        if (f.exists()) {
            return f.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    val i = line.indexOf('=')
                    line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
        }
        dir = dir.parentFile ?: return emptyMap()
    }
    return emptyMap()
}

private fun loadConfig(env: Map<String, String>): String {
    val selfhostedEndpoint = env["SELFHOSTED_ENDPOINT"] ?: "http://localhost:11434"
    val candidates = listOf(
        File(System.getProperty("user.dir")).resolve("../aiOrka.yaml"),
        File(System.getProperty("user.dir")).resolve("examples/aiOrka.yaml"),
        File(System.getProperty("user.dir")).resolve("aiOrka.yaml"),
    )
    val yaml = candidates.firstOrNull { it.exists() }?.readText()
        ?: error("Cannot locate examples/aiOrka.yaml — run from the repo root")
    return yaml.replace("http://localhost:11434", selfhostedEndpoint)
}

private fun activeKeys(env: Map<String, String>): String {
    val found = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY")
        .filter { env[it]?.isNotBlank() == true }
        .map { it.removeSuffix("_API_KEY").lowercase() }
    return if (found.isEmpty()) "none (self-hosted only)" else found.joinToString(", ")
}

private fun String.ifNotBlank(block: (String) -> Unit) {
    if (isNotBlank()) block(this)
}

private fun banner(title: String) =
    "\n${"═".repeat(60)}\n  $title\n${"═".repeat(60)}"
