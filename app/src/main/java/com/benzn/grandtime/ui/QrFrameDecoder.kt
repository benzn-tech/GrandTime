package com.benzn.grandtime.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** What one analysed frame produced, and the payload if it was readable. */
data class FrameDecode(val outcome: ScanFrame, val text: String?)

/**
 * The decode half of the QR scanner, with no camera in it.
 *
 * It lives apart from [QrScanScaffold] because the version inlined there could not be tested and
 * was broken from the day it shipped. That version decoded the frame upright and then, if that
 * found nothing, retried a quarter-turned copy obtained from
 * `PlanarYUVLuminanceSource.rotateCounterClockwise()`. ZXing's `LuminanceSource` base class
 * implements that method as an unconditional `throw UnsupportedOperationException`
 * (`isRotateSupported` returns false) and `PlanarYUVLuminanceSource` does not override it. The
 * throw is not one of the three ZXing exceptions the retry caught, so it fell through to a blanket
 * `catch (e: Exception)` around the whole frame and vanished. On device that produced 1065
 * analysed frames and zero log lines, with the preview running normally at 16.7fps: every frame
 * that did not decode upright died before reaching the log, so `ScanHints` — which is unit-tested
 * and whose only producer is this path — never received a single frame, and the operator saw
 * "Scanning…" forever with no way to find out why.
 *
 * There is no rotated retry here, because ZXing does not need one: its QR detector locates the
 * three finder patterns and derives the code's orientation from them, so a quarter-turned code
 * decodes from the upright frame. `code_rotated_a_quarter_turn_decodes` holds that.
 */
object QrFrameDecoder {

    private val reader = QRCodeReader()

    /** TRY_HARDER trades a little CPU for codes that are small or off-axis, which is every code
     *  held up to a 320dp screen at arm's length. */
    private val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

    /** Decode one Y plane. [rowStride] is the HAL's row pitch, which is >= [width]. */
    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int): FrameDecode {
        var located = false
        val text = decodeOrClassify(source(y, rowStride, width, height)) { located = true }
        return when {
            text != null -> FrameDecode(ScanFrame.DECODED, text)
            located -> FrameDecode(ScanFrame.LOCATED_UNREADABLE, null)
            else -> FrameDecode(ScanFrame.NOTHING, null)
        }
    }

    /**
     * ZXing is told the plane is `rowStride * height` bytes, so a short buffer has to be padded or
     * the constructor's own crop check throws — once per frame, from inside the camera thread.
     * Many HALs hand back `rowStride * (height - 1) + width`.
     */
    private fun source(y: ByteArray, rowStride: Int, width: Int, height: Int): PlanarYUVLuminanceSource {
        val needed = rowStride * height
        val padded = if (y.size >= needed) y else y.copyOf(needed)
        return PlanarYUVLuminanceSource(padded, rowStride, height, 0, 0, width, height, false)
    }

    /**
     * Decode one image, and tell the caller whether a code was at least *located*.
     *
     * ChecksumException and FormatException mean the finder patterns were found and the code was
     * sampled, but the payload could not be recovered — too small, too blurry, or damaged. That is
     * the one state with a concrete remedy ("move closer"), and `MultiFormatReader` would collapse
     * it into not-found.
     */
    private fun decodeOrClassify(src: PlanarYUVLuminanceSource, onLocated: () -> Unit): String? =
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            onLocated(); null
        } catch (e: FormatException) {
            onLocated(); null
        } finally {
            reader.reset()
        }
}
