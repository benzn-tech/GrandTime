package com.benzn.grandtime.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner analysed 1065 frames on device and logged nothing at all, while the preview ran
 * normally at 16.7fps and the screen said "Scanning…". Every frame that did not decode upright was
 * throwing: the quarter-turn retry called `PlanarYUVLuminanceSource.rotateCounterClockwise()`,
 * which ZXing implements as an unconditional throw, and a blanket `catch (e: Exception)` swallowed
 * it. So `ScanHints` never received a frame outcome and its "move closer" advice — which exists
 * and is unit-tested — could not be shown.
 *
 * The retry was also unnecessary, which is what makes the defect pure loss: ZXing derives a code's
 * orientation from its finder patterns, so the upright pass already handles a turned code.
 * [code_rotated_a_quarter_turn_decodes] is what says so.
 *
 * These tests drive real QR bitmaps through the decoder rather than a fake, because the defect was
 * invisible to anything that fed the units directly.
 */
class QrFrameDecoderTest {

    private val payload = "https://app.fieldsight.co.nz/qr#code=abc123&env=prod"

    /**
     * Pins why there must never be a rotated retry built on ZXing's own rotation. If someone adds
     * `source.rotateCounterClockwise()` back, this is the explanation waiting for them.
     */
    @Test fun zxing_planar_source_cannot_rotate() {
        val src = PlanarYUVLuminanceSource(ByteArray(16), 4, 4, 0, 0, 4, 4, false)
        assertEquals(false, src.isRotateSupported)
        assertThrows(UnsupportedOperationException::class.java) { src.rotateCounterClockwise() }
    }

    @Test fun upright_code_decodes() {
        val f = frame(payload)
        val out = QrFrameDecoder.decode(f.y, f.rowStride, f.width, f.height)
        assertEquals(ScanFrame.DECODED, out.outcome)
        assertEquals(payload, out.text)
    }

    /**
     * The terminal is often held landscape, so a code held upright to the operator arrives turned
     * in sensor coordinates. No retry is needed for it, and this is the test that says so — delete
     * the claim and you invite the retry back.
     */
    @Test fun code_rotated_a_quarter_turn_decodes() {
        for (turns in 1..3) {
            val f = frame(payload).turned(turns)
            val out = QrFrameDecoder.decode(f.y, f.rowStride, f.width, f.height)
            assertEquals("turns=$turns", ScanFrame.DECODED, out.outcome)
            assertEquals(payload, out.text)
        }
    }

    /**
     * HALs hand back a Y plane whose rows are padded: rowStride > width. The device's own frames
     * are 1280x960 and padded, so this is the ordinary case, not an edge one.
     */
    @Test fun padded_row_stride_decodes() {
        val f = frame(payload, extraStride = 61)
        assertNotEquals(f.width, f.rowStride)
        val out = QrFrameDecoder.decode(f.y, f.rowStride, f.width, f.height)
        assertEquals(ScanFrame.DECODED, out.outcome)
        assertEquals(payload, out.text)
    }

    /**
     * A short buffer is what real HALs deliver: rowStride*(h-1)+w, not rowStride*h. Declaring the
     * larger size to ZXing throws once per frame, which is the silent-every-frame shape again.
     */
    @Test fun buffer_shorter_than_row_stride_times_height_still_decodes() {
        val f = frame(payload, extraStride = 32)
        val short = f.y.copyOf(f.rowStride * (f.height - 1) + f.width)
        assertTrue(short.size < f.rowStride * f.height)
        val out = QrFrameDecoder.decode(short, f.rowStride, f.width, f.height)
        assertEquals(ScanFrame.DECODED, out.outcome)
    }

    @Test fun blank_frame_reports_nothing() {
        val y = ByteArray(320 * 240) { -1 } // uniform white
        val out = QrFrameDecoder.decode(y, 320, 320, 240)
        assertEquals(ScanFrame.NOTHING, out.outcome)
        assertNull(out.text)
    }

    /**
     * A code whose payload area is destroyed must not decode. (Whether it comes back
     * LOCATED_UNREADABLE or NOTHING depends on how much of the sampling grid survives, which is
     * ZXing's business — so this pins only the part we own.)
     */
    @Test fun destroyed_payload_does_not_decode() {
        val f = frame(payload)
        // Invert a wide band across the middle, well clear of the three finder patterns.
        for (row in f.height / 3 until f.height * 2 / 3) {
            for (col in 0 until f.width) {
                val i = row * f.rowStride + col
                f.y[i] = (f.y[i].toInt().inv() and 0xFF).toByte()
            }
        }
        val out = QrFrameDecoder.decode(f.y, f.rowStride, f.width, f.height)
        assertNotEquals(ScanFrame.DECODED, out.outcome)
        assertNull(out.text)
    }

    // ---- helpers ----

    private class Frame(val y: ByteArray, val rowStride: Int, val width: Int, val height: Int) {
        /** [turns] quarter-turns clockwise, tightly packed. */
        fun turned(turns: Int): Frame {
            var f = this
            repeat(turns) { f = f.turnedOnce() }
            return f
        }

        private fun turnedOnce(): Frame {
            val out = ByteArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    // clockwise: (col,row) -> (height-1-row, col) in an image height wide
                    out[col * height + (height - 1 - row)] = y[row * rowStride + col]
                }
            }
            return Frame(out, rowStride = height, width = height, height = width)
        }
    }

    private fun frame(text: String, moduleScale: Int = 6, extraStride: Int = 0): Frame {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        // Ask for a size and let ZXing round to whole modules, then read back what it produced.
        val side = 33 * moduleScale
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, hints)
        val w = matrix.width
        val h = matrix.height
        val stride = w + extraStride
        val y = ByteArray(stride * h) { -1 }
        for (row in 0 until h) {
            for (col in 0 until w) {
                y[row * stride + col] = if (matrix.get(col, row)) 0 else -1
            }
        }
        return Frame(y, stride, w, h)
    }
}
