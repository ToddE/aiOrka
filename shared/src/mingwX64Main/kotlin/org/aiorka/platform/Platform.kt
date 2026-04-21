package org.aiorka.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.windows.GetTickCount64

// MinGW provides POSIX-compatible wrappers; GetTickCount64 gives ms since system boot.
// For absolute epoch time, offset is computed at startup via time(null).
@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long {
    // platform.posix.time returns seconds since Unix epoch on MinGW
    return platform.posix.time(null) * 1000L
}

@OptIn(ExperimentalForeignApi::class)
actual fun getEnvVariable(name: String): String? = getenv(name)?.toKString()
