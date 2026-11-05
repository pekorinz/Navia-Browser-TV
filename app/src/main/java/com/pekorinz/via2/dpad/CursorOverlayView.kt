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
 *
 * Diseño moderno de precisión para TV: retícula de mira circular con alto contraste,
 * micro-guías y sombra difusa para visibilidad perfecta en fondos claros y oscuros.
 * Tamaño reducido un 15% (radio de 11.9dp respecto a los 14dp originales).
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

    private val density = resources.displayMetrics.density

    // Radio 15% más pequeño: 14dp * 0.85 = 11.9dp
    private val radius = 11.9f * density

    // Sombra perimetral para contraste universal sobre cualquier fondo
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }

    // Anillo exterior blanco de alto contraste
    private val strokeOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    // Anillo principal acentuado
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.cursor_pointer_color)
        style = Paint.Style.FILL
    }

    // Disco interior suave
    private val innerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    // Punto central de mira de precisión
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Micro-guías de mira en cruz para apuntar con precisión en TV
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

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

        // 1. Sombra exterior difusa para contraste en fondos claros y oscuros
        canvas.drawCircle(cursorX, cursorY + (1f * density), radius + (1.5f * density), shadowPaint)

        // 2. Anillo de halo exterior blanco
        canvas.drawCircle(cursorX, cursorY, radius, strokeOuterPaint)

        // 3. Relleno principal acentuado
        canvas.drawCircle(cursorX, cursorY, radius * 0.78f, fillPaint)

        // 4. Disco interior brillante
        canvas.drawCircle(cursorX, cursorY, radius * 0.45f, innerDiscPaint)

        // 5. Punto central de precisión nítido
        canvas.drawCircle(cursorX, cursorY, radius * 0.22f, centerPaint)

        // 6. Micro-guías en cruz exteriores (arriba, abajo, izquierda, derecha)
        val chStart = radius * 0.88f
        val chEnd = radius + (2.5f * density)
        canvas.drawLine(cursorX, cursorY - chEnd, cursorX, cursorY - chStart, crosshairPaint)
        canvas.drawLine(cursorX, cursorY + chStart, cursorX, cursorY + chEnd, crosshairPaint)
        canvas.drawLine(cursorX - chEnd, cursorY, cursorX - chStart, cursorY, crosshairPaint)
        canvas.drawLine(cursorX + chStart, cursorY, cursorX + chEnd, cursorY, crosshairPaint)
    }
}
