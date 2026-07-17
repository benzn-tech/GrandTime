package com.benzn.grandtime.capture.camera2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.benzn.grandtime.capture.WatermarkContent

/** 把 4 行水印内容画到一张 ARGB 位图:白字黑描边,背景透明。视频(GL 纹理)与照片(直接叠)共用。 */
object WatermarkRenderer {
    fun render(content: WatermarkContent, widthPx: Int): Bitmap {
        val lines = content.lines
        val textSize = (widthPx * 0.028f).coerceAtLeast(18f)
        val pad = textSize * 0.4f
        val lineH = textSize * 1.25f
        val height = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = Paint().apply {
            color = Color.BLACK; this.textSize = textSize; isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = textSize * 0.16f
        }
        val fill = Paint().apply {
            color = Color.WHITE; this.textSize = textSize; isAntiAlias = true; style = Paint.Style.FILL
        }
        var y = pad + textSize
        for (line in lines) {
            c.drawText(line, pad, y, stroke)
            c.drawText(line, pad, y, fill)
            y += lineH
        }
        return bmp
    }
}
