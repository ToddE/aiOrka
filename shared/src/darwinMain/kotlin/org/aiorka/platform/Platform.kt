package org.aiorka.platform

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo

actual fun currentTimeMillis(): Long =
    (NSDate.date().timeIntervalSince1970 * 1000).toLong()

actual fun getEnvVariable(name: String): String? =
    NSProcessInfo.processInfo.environment[name] as? String
