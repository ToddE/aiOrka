package org.aiorka.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.gmtime
import platform.posix.strftime

// MinGW provides POSIX-compatible wrappers; GetTickCount64 gives ms since system boot.
// For absolute epoch time, offset is computed at startup via time(null).
@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long {
    // platform.posix.time returns seconds since Unix epoch on MinGW
    return platform.posix.time(null) * 1000L
}

@OptIn(ExperimentalForeignApi::class)
actual fun getEnvVariable(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
actual fun writeTextFile(path: String, content: String) {
    val file = fopen(path, "w") ?: error("Cannot open file for writing: $path")
    try {
        fputs(content, file)
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun formatTimestamp(millis: Long): String = memScoped {
    val seconds = (millis / 1000L)
    val tVar = alloc<LongVar>()
    tVar.value = seconds
    val tmPtr = gmtime(tVar.ptr.reinterpret()) ?: return@memScoped millis.toString()
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32u, "%Y-%m-%d %H:%M:%S", tmPtr)
    buf.toKString() + " UTC"
}
