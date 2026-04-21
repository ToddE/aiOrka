package org.aiorka.platform

expect fun currentTimeMillis(): Long

expect fun getEnvVariable(name: String): String?
