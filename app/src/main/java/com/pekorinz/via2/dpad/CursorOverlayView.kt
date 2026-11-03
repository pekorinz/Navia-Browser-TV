package com.pekorinz.via2.dpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.pekorinz.via2.R

/**
 * Vista superpuesta nativa para renderizar el puntero del cursor en pantalla
 * durante el modo CURSOR sin requerir permisos de accesibilidad ni overlays de sistema.
 */
class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    var isCursorVisible: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.cursor_pointer_color)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.cursor_pointer_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val radius = 14f * resources.displayMetrics.density

    fun setCursorPosition(x: Float, y: Float) {
        val clampedX = x.coerceIn(radius, width.toFloat() - radius)
        val clampedY = y.coerceIn(radius, height.toFloat() - radius)
        if (cursorX != clampedX || cursorY != clampedY) {
            cursorX = clampedX
            cursorY = clampedY
            invalidate()
        }
    }

    fun centerCursor() {
        if (width > 0 && height > 0) {
            setCursorPosition(width / 2f, height / 2f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX == 0f && cursorY == 0f) {
            centerCursor()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCursorVisible) return

        // Dibujar borde blanco exterior
        canvas.drawCircle(cursorX, cursorY, radius, strokePaint)
        // Dibujar relleno principal
        canvas.drawCircle(cursorX, cursorY, radius * 0.75f, fillPaint)
        // Dibujar punto central
        canvas.drawCircle(cursorX, cursorY, radius * 0.25f, centerPaint)
    }
}
