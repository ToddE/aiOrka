package org.aiorka.platform

actual fun currentTimeMillis(): Long = Date().getTime().toLong()

// Only available in Node.js; returns null in browser environments
actual fun getEnvVariable(name: String): String? =
    try {
        js("(typeof process !== 'undefined' && process.env ? process.env[name] : null)") as? String
    } catch (_: Throwable) {
        null
    }

private external class Date {
    fun getTime(): Double
}
