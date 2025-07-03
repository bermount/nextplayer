package dev.anilbeesetti.nextplayer.feature.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class PlayerRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF0000.toInt() // Red color, customize as needed
        strokeCap = Paint.Cap.ROUND
    }

    var durationMs: Long = 0L
        set(value) { field = value; invalidate() }

    var thinBarHeightPx: Float = 2f // Adjust as needed

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (durationMs <= 0) return

        val widthPx = width.toFloat()
        val widthPerMs = widthPx / durationMs

        val baseLineHeight = thinBarHeightPx
        val longLineHeight = baseLineHeight * 1.5f

        val baseLineWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics
        )
        val longLineWidth = baseLineWidth * 2

        // Draw tick marks every 10 minutes (600000 ms), longer every 30 minutes
        var markerMs = durationMs - 10 * 60_000L
        while (markerMs > 0) {
            val isLong = ((markerMs / (10 * 60_000L)) % 3 == 0L)
            val x = widthPx - (markerMs * widthPerMs)  // right to left

            linePaint.strokeWidth = if (isLong) longLineWidth else baseLineWidth
            val lineHeight = if (isLong) longLineHeight else baseLineHeight

            canvas.drawLine(
                x, 0f, x, lineHeight, linePaint
            )
            markerMs -= 10 * 60_000L
        }
    }
}
