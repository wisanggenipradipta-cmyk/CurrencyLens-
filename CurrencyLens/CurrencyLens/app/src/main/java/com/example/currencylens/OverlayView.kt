package com.example.currencylens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Draws "chips" (rounded rectangles with converted prices) on top of the
 * camera preview, at the exact locations where original prices were detected.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Chip(val box: RectF, val label: String)

    @Volatile
    private var chips: List<Chip> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 105, 92) // teal, near-opaque so it covers the original price
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun submit(newChips: List<Chip>) {
        chips = newChips
        postInvalidate()
    }

    fun clear() = submit(emptyList())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padH = 10f
        val padV = 6f

        for (chip in chips) {
            // Text size proportional to the detected text height
            val ts = (chip.box.height() * 0.72f).coerceIn(26f, 72f)
            textPaint.textSize = ts

            val textWidth = textPaint.measureText(chip.label)
            val fm = textPaint.fontMetrics
            val textHeight = fm.descent - fm.ascent

            // Chip covers the original price box, growing if the label is wider
            val box = RectF(chip.box)
            val neededW = textWidth + 2 * padH
            if (box.width() < neededW) {
                val grow = (neededW - box.width()) / 2f
                box.left -= grow
                box.right += grow
            }
            val neededH = textHeight + 2 * padV
            if (box.height() < neededH) {
                val grow = (neededH - box.height()) / 2f
                box.top -= grow
                box.bottom += grow
            }

            val radius = box.height() * 0.25f
            canvas.drawRoundRect(box, radius, radius, bgPaint)
            canvas.drawRoundRect(box, radius, radius, strokePaint)

            val tx = box.centerX() - textWidth / 2f
            val ty = box.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(chip.label, tx, ty, textPaint)
        }
    }
}
