package com.benzn.grandtime.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the meeting code the lead device shows for others to scan.
 *
 * Uses the zxing encoder already vendored for scanning, so no new dependency —
 * the device ABI is armeabi-only and the project takes no native libraries.
 */
object MeetingCode {

    /**
     * The code as a module grid.
     *
     * Split out from [bitmap] because this half is pure JVM and can therefore be
     * round-tripped through the real decoder in a test. That test is the point:
     * a code this device displays but our own scanner cannot read would fail
     * only on a second physical device, which is the most expensive place to
     * find it.
     *
     * Error correction is HIGH, not the default MEDIUM: this is read off a
     * scratched 3-inch screen held at arm's length on a building site, and the
     * payload is 36 characters — there is room to spend on redundancy.
     */
    fun matrix(text: String, sizePx: Int): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            // Default is 4 modules of quiet zone. Kept explicit because dropping
            // it makes the code unreadable against a dark background, and the
            // failure looks like a camera problem rather than a rendering one.
            EncodeHintType.MARGIN to 2,
        ),
    )

    /** Black-on-white bitmap for display. White stays opaque so a dark theme cannot swallow it. */
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val m = matrix(text, sizePx)
        val pixels = IntArray(m.width * m.height)
        for (y in 0 until m.height) {
            val row = y * m.width
            for (x in 0 until m.width) pixels[row + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }
}
