package com.example.coach_android.util

object SafeUrl {
    // Both sockets carry their credential in the query string, because the
    // browser clients cannot set headers and the server had to speak one dialect.
    // That puts the token anywhere a URL is printed: logcat, readable by anything
    // holding adb, and the in-app Logs screen, whose Copy button hands the whole
    // buffer to the clipboard.
    //
    // The whole query goes rather than the token alone — for these URLs the query
    // is only ever the token, and dropping it cannot leak what a pattern match
    // might miss.
    fun redacted(url: String): String {
        val cut = url.indexOf('?')
        return if (cut < 0) url else url.substring(0, cut) + "?<redacted>"
    }
}
