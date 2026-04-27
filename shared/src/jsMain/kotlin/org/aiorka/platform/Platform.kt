package org.aiorka.platform

actual fun currentTimeMillis(): Long = Date().getTime().toLong()

// Only available in Node.js; returns null in browser environments
actual fun getEnvVariable(name: String): String? =
    try {
        js("(typeof process !== 'undefined' && process.env ? process.env[name] : null)") as? String
    } catch (_: Throwable) {
        null
    }

// Only available in Node.js; no-op in browser environments
actual fun writeTextFile(path: String, content: String) {
    try {
        js("if (typeof require !== 'undefined') { require('fs').writeFileSync(path, content, 'utf8') }")
    } catch (_: Throwable) {}
}

actual fun formatTimestamp(millis: Long): String =
    (js("new Date(millis).toISOString().slice(0, 19).replace('T', ' ')") as? String
        ?: millis.toString()) + " UTC"

private external class Date {
    fun getTime(): Double
}
