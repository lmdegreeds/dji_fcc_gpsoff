package com.dji.fccgpsoff

/**
 * Holds the most recent screen PNG captured by [DjiFlyAccessibilityService] via
 * AccessibilityService.takeScreenshot() (Android 11+, no MediaProjection prompt).
 * The device only captures + PNG-encodes; the browser displays the raw bytes, so
 * almost no work stays on device.
 */
object ScreenshotStore {
    @Volatile var png: ByteArray? = null; private set
    @Volatile var atMs: Long = 0L; private set
    @Volatile var error: String? = null; private set

    fun put(bytes: ByteArray) { png = bytes; atMs = System.currentTimeMillis(); error = null }
    fun fail(msg: String) { error = msg }
}
