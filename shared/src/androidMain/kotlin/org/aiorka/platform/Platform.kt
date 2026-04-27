package org.aiorka.platform

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun getEnvVariable(name: String): String? = System.getenv(name)

actual fun writeTextFile(path: String, content: String) {
    File(path).writeText(content, Charsets.UTF_8)
}

actual fun formatTimestamp(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(millis)) + " UTC"
}
