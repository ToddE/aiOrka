package org.aiorka.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSTimeZone
import platform.Foundation.create
import platform.Foundation.writeToURL

actual fun currentTimeMillis(): Long =
    (NSDate.date().timeIntervalSince1970 * 1000).toLong()

actual fun getEnvVariable(name: String): String? =
    NSProcessInfo.processInfo.environment[name] as? String

actual fun writeTextFile(path: String, content: String) {
    val url = NSURL.fileURLWithPath(path)
    NSString.create(string = content)
        .writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

actual fun formatTimestamp(millis: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(millis.toDouble() / 1000.0)
    val fmt = NSDateFormatter()
    fmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
    fmt.timeZone = NSTimeZone.timeZoneWithAbbreviation("UTC")!!
    return fmt.stringFromDate(date) + " UTC"
}
