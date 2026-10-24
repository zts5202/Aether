package com.zhousl.aether.phoneuse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/**
 * Draws a readable 8×12 grid on a real-screen screenshot.
 *
 * Each cell is labeled with its id (`r3c5`, or `r3` / `c5` when the cell is narrow).
 * Lines use the same pixel ranges as [phoneUseGridCellCenter], so the badge sits on
 * the point the host will tap. The model copies the label; it does not measure pixels.
 */
internal fun drawPhoneUseGrid(source: Bitmap): Bitmap {
    val bitmap = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Unable to prepare a screenshot for the Phone Use grid.")
    }
    val canvas = Canvas(bitmap)
    val width = bitmap.width
    val height = bitmap.height
    val stroke = (min(width, height) / 220f).coerceIn(2f, 6f)
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke + 3f
        color = Color.BLACK
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.rgb(0, 229, 255)
    }
    for (col in 1 until PhoneUseGridCols) {
        val x = phoneUseGridCellPixelRange(col, PhoneUseGridCols, width).first.toFloat()
        canvas.drawLine(x, 0f, x, height.toFloat(), shadow)
        canvas.drawLine(x, 0f, x, height.toFloat(), line)
    }
    for (row in 1 until PhoneUseGridRows) {
        val y = phoneUseGridCellPixelRange(row, PhoneUseGridRows, height).first.toFloat()
        canvas.drawLine(0f, y, width.toFloat(), y, shadow)
        canvas.drawLine(0f, y, width.toFloat(), y, line)
    }
    canvas.drawRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, shadow)
    canvas.drawRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, line)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(232, 0, 0, 0)
    }
    val badgeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (stroke * 0.55f).coerceAtLeast(1.5f)
        color = Color.WHITE
    }
    for (row in 0 until PhoneUseGridRows) {
        for (col in 0 until PhoneUseGridCols) {
            drawPhoneUseGridLabel(canvas, text, badge, badgeEdge, bitmap.width, bitmap.height, row, col, stroke)
        }
    }
    return bitmap
}

private fun drawPhoneUseGridLabel(
    canvas: Canvas,
    text: Paint,
    badge: Paint,
    badgeEdge: Paint,
    bitmapWidth: Int,
    bitmapHeight: Int,
    row: Int,
    col: Int,
    stroke: Float,
) {
    val xRange = phoneUseGridCellPixelRange(col, PhoneUseGridCols, bitmapWidth)
    val yRange = phoneUseGridCellPixelRange(row, PhoneUseGridRows, bitmapHeight)
    val cellW = (xRange.last - xRange.first + 1).toFloat()
    val cellH = (yRange.last - yRange.first + 1).toFloat()
    val full = phoneUseGridLabel(row, col)
    val maxTextWidth = cellW * 0.86f
    var size = (cellH * 0.22f).coerceAtMost(cellW * 0.36f).coerceIn(8f, 48f)
    text.textSize = size
    val stacked = text.measureText(full) > maxTextWidth
    val lines = if (stacked) listOf("r$row", "c$col") else listOf(full)
    while (lines.any { text.measureText(it) > maxTextWidth } && size > 8f) {
        size *= 0.9f
        text.textSize = size
    }
    val (centerX, centerY) = phoneUseGridCellCenter(PhoneUseGridCell(row, col), bitmapWidth, bitmapHeight)
    val cx = centerX.toFloat()
    val cy = centerY.toFloat()
    val metrics = text.fontMetrics
    val lineHeight = metrics.descent - metrics.ascent
    val blockHeight = lineHeight * lines.size
    val widest = lines.maxOf { text.measureText(it) }
    val padX = size * 0.22f
    val padY = size * 0.12f
    val inset = stroke + 1f
    val rect = RectF(
        (cx - widest / 2f - padX).coerceAtLeast(xRange.first + inset),
        (cy - blockHeight / 2f - padY).coerceAtLeast(yRange.first + inset),
        (cx + widest / 2f + padX).coerceAtMost(xRange.last - inset),
        (cy + blockHeight / 2f + padY).coerceAtMost(yRange.last - inset),
    )
    if (rect.width() < 4f || rect.height() < 4f) return
    val radius = (size * 0.22f).coerceAtMost(rect.height() / 2f)
    canvas.drawRoundRect(rect, radius, radius, badge)
    canvas.drawRoundRect(rect, radius, radius, badgeEdge)
    lines.forEachIndexed { index, label ->
        val baseline = rect.top + padY - metrics.ascent + index * lineHeight
        canvas.drawText(label, cx.coerceIn(rect.left, rect.right), baseline, text)
    }
}
