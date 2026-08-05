package com.benzn.grandtime

import com.benzn.grandtime.capture.SessionGroup
import com.benzn.grandtime.ui.MeetingCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lead device displays a code; a second device reads it with the scanner in
 * [com.benzn.grandtime.ui.QrScanScreen]. Nothing in the app exercises both ends,
 * so this drives the real encoder into the real decoder — a code that renders
 * but does not scan would otherwise surface only on two physical devices.
 */
class MeetingCodeTest {

    private val sid = "3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c"

    /** Reads a [BitMatrix] as an image: set module = black (0), clear = white (255). */
    private class MatrixSource(private val m: BitMatrix) : LuminanceSource(m.width, m.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (m.get(x, y)) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) getRow(y, ByteArray(width)).copyInto(out, y * width)
            return out
        }
    }

    private fun decode(text: String, sizePx: Int): String =
        MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(MatrixSource(MeetingCode.matrix(text, sizePx)))))
            .text

    @Test
    fun aDisplayedMeetingCodeScansBackToTheSameSession() {
        val shown = SessionGroup.format(sid)
        assertEquals(sid, SessionGroup.parse(decode(shown, 512)))
    }

    @Test
    fun theCodeSurvivesTheSmallestScreenWeRenderItOn() {
        // The F2SP screen is small and the dialog does not fill it. If the
        // payload ever outgrows what fits at this size, this fails here rather
        // than as "the camera won't pick it up" on site.
        assertEquals(sid, SessionGroup.parse(decode(SessionGroup.format(sid), 256)))
    }

    @Test
    fun aLoginCodeIsNotMistakenForAMeetingCode() {
        // Both flows show a QR on the same hardware. Reading one with the other
        // scanner must fail cleanly, not join a group named after a login code.
        assertEquals(null, SessionGroup.parse(decode("fs-login:abc123", 512)))
    }
}
