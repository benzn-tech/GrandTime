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
 * Does the code still scan at the size this screen can actually give it?
 *
 * The F2SP is 480x640 px at 240dpi — 320x427 dp. Full screen, minus a caption
 * and a Done button, leaves roughly 300 dp square for the code, which is 450
 * PHYSICAL pixels. The previous dialog layout left far less, and the shortfall
 * was invisible until someone tried to scan it.
 */
class CodeLayoutTest {
    private val sid = "3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c"

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

    private fun scans(px: Int): Boolean = runCatching {
        val text = SessionGroup.format(sid, env = "test")
        val m = MeetingCode.matrix(text, px)
        val decoded = MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(MatrixSource(m)))).text
        SessionGroup.parse(decoded, env = "test") == SessionGroup.Scan.Ok(sid)
    }.getOrDefault(false)

    @Test fun scansAtTheSizeFullScreenGivesIt() = assertEquals(true, scans(450))

    @Test fun scansAtTheSizeTheOldDialogGaveIt() {
        // ~224dp of content width at 1.5x = ~336px. It encoded fine — the old
        // layout's problem was never the encoder, it was that the bottom of the
        // code fell outside the dialog.
        assertEquals(true, scans(336))
    }

    @Test fun stillScansIfTheScreenIsSmallerThanWeThink() = assertEquals(true, scans(240))
}
