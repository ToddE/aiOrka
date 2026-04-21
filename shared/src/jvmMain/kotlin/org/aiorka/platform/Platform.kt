package org.aiorka.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun getEnvVariable(name: String): String? = System.getenv(name)
