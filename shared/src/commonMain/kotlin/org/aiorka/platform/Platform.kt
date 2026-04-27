package org.aiorka.platform

expect fun currentTimeMillis(): Long

expect fun getEnvVariable(name: String): String?

expect fun writeTextFile(path: String, content: String)

expect fun formatTimestamp(millis: Long): String
