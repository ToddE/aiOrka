@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package org.aiorka

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── JSON DTOs for the C boundary ─────────────────────────────────────────────
// Uses snake_case so every language binding gets idiomatic field names.

@Serializable
private data class CMessageDto(val role: String, val content: String)

@Serializable
private data class CResponseDto(
    val content: String,
    val provider_id: String,
    val model_used: String,
    val duration_ms: Long,
    val tokens_used: Int? = null,
    val cost: Double? = null
)

@Serializable
private data class CErrorDto(val error: String)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

// ── Thread-local error slot ───────────────────────────────────────────────────
// C consumers call aiorka_last_error() after a null return to retrieve details.

private var lastError: String? = null

private fun setError(msg: String) { lastError = msg }
private fun clearError()          { lastError = null }

// ── Memory helpers ────────────────────────────────────────────────────────────
// Strings returned to C callers are heap-allocated. The caller owns them and
// MUST free them with aiorka_free_string().

private fun allocCString(s: String): CPointer<ByteVar> {
    val bytes = s.encodeToByteArray()
    val ptr = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { i, b -> ptr[i] = b }
    ptr[bytes.size] = 0
    return ptr
}

// ── Public C API ──────────────────────────────────────────────────────────────

/**
 * Create and initialize an AiOrka instance.
 *
 * @param configYaml  Optional aiOrka.yaml content as a C string.
 *                    Pass NULL to use the bundled library defaults.
 * @return  Opaque handle passed to all subsequent calls, or NULL on failure.
 *          Check aiorka_last_error() on NULL.
 */
@CName("aiorka_create")
fun aiOrkaCreate(configYaml: CPointer<ByteVar>?): COpaquePointer? {
    clearError()
    return try {
        val yamlStr = configYaml?.toKString()
        val instance = runBlocking {
            AiOrka.initialize {
                if (yamlStr != null) this.configYaml = yamlStr
            }
        }
        StableRef.create(instance).asCOpaquePointer()
    } catch (e: Exception) {
        setError(e.message ?: "Failed to initialize AiOrka")
        null
    }
}

/**
 * Execute a policy and return a JSON response string.
 *
 * @param handle        Handle returned by aiorka_create().
 * @param policyId      Name of the policy to execute (e.g. "fast-chat").
 * @param messagesJson  JSON array of message objects: [{"role":"user","content":"..."}]
 * @return  Heap-allocated JSON string — caller must free with aiorka_free_string().
 *          Returns NULL on failure; check aiorka_last_error().
 */
@CName("aiorka_execute")
fun aiOrkaExecute(
    handle: COpaquePointer?,
    policyId: CPointer<ByteVar>?,
    messagesJson: CPointer<ByteVar>?
): CPointer<ByteVar>? {
    clearError()
    if (handle == null || policyId == null || messagesJson == null) {
        setError("aiorka_execute: handle, policyId, and messagesJson must not be NULL")
        return null
    }
    return try {
        val orka = handle.asStableRef<AiOrka>().get()
        val policy = policyId.toKString()
        val messages = json.decodeFromString<List<CMessageDto>>(messagesJson.toKString())
            .map { org.aiorka.models.Message(it.role, it.content) }

        val response = runBlocking { orka.execute(policy, messages) }

        val dto = CResponseDto(
            content     = response.content,
            provider_id = response.metadata.providerId,
            model_used  = response.metadata.modelUsed,
            duration_ms = response.metadata.durationMs,
            tokens_used = response.metadata.tokensUsed,
            cost        = response.metadata.cost
        )
        allocCString(json.encodeToString(dto))
    } catch (e: Exception) {
        setError(e.message ?: "Execution failed")
        null
    }
}

/**
 * Inject an API key at runtime.
 * Equivalent to setting an environment variable before calling aiorka_create(),
 * but works in sandboxed or containerized runtimes where env-var mutation is
 * not possible.
 *
 * @param handle      Handle returned by aiorka_create().
 * @param envVarName  The env-var name the provider is configured to read
 *                    (e.g. "ANTHROPIC_API_KEY").
 * @param keyValue    The secret key value.
 */
@CName("aiorka_set_key")
fun aiOrkaSetKey(
    handle: COpaquePointer?,
    envVarName: CPointer<ByteVar>?,
    keyValue: CPointer<ByteVar>?
) {
    clearError()
    if (handle == null || envVarName == null || keyValue == null) {
        setError("aiorka_set_key: all arguments must be non-NULL")
        return
    }
    try {
        handle.asStableRef<AiOrka>().get()
            .setApiKey(envVarName.toKString(), keyValue.toKString())
    } catch (e: Exception) {
        setError(e.message ?: "Failed to set API key")
    }
}

/**
 * Return the last error message as a heap-allocated C string, or NULL if none.
 * The caller must free the returned string with aiorka_free_string().
 */
@CName("aiorka_last_error")
fun aiOrkaLastError(): CPointer<ByteVar>? = lastError?.let { allocCString(it) }

/**
 * Free a string previously returned by this library.
 * Passing NULL is safe (no-op).
 */
@CName("aiorka_free_string")
fun aiOrkaFreeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) nativeHeap.free(ptr)
}

/**
 * Return the health status of all configured providers as a JSON object.
 * The caller must free the returned string with aiorka_free_string().
 */
@CName("aiorka_health")
fun aiOrkaHealth(handle: COpaquePointer?): CPointer<ByteVar>? {
    clearError()
    if (handle == null) { setError("aiorka_health: handle must not be NULL"); return null }
    return try {
        val snapshot = handle.asStableRef<AiOrka>().get().healthSnapshot()
        val map = snapshot.mapValues { (_, v) ->
            mapOf("alive" to v.isAlive, "failures" to v.consecutiveFailures,
                  "latency_ms" to v.lastLatencyMs)
        }
        allocCString(json.encodeToString(map))
    } catch (e: Exception) {
        setError(e.message ?: "Failed to get health snapshot")
        null
    }
}

/**
 * Shut down the AiOrka instance and release all resources.
 * The handle is invalid after this call.
 */
@CName("aiorka_destroy")
fun aiOrkaDestroy(handle: COpaquePointer?) {
    if (handle == null) return
    try {
        val ref = handle.asStableRef<AiOrka>()
        ref.get().shutdown()
        ref.dispose()
    } catch (_: Exception) {}
}

/** Return the library version string. Caller must free with aiorka_free_string(). */
@CName("aiorka_version")
fun aiOrkaVersion(): CPointer<ByteVar> = allocCString("0.1.0")
