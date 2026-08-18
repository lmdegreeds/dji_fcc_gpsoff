package com.dji.fccgpsoff

/**
 * One JSON string escaper for the hand-built `statusJson()` / `json()` bodies
 * scattered across the app. Previously five call sites each rolled their own —
 * four escaped only `\` and `"` (so a tab/newline/control char in a package name,
 * file name or serial produced invalid JSON), and one ([ForegroundGate]) escaped
 * nothing at all. This one is complete: quotes, backslash, the C0 controls.
 */
object Json {

    /** Escape [s] for embedding between JSON double-quotes (no surrounding quotes). */
    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** A complete, quoted JSON string literal for [s]. */
    fun quote(s: String): String = "\"" + esc(s) + "\""
}
