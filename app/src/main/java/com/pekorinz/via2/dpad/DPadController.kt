package com.pekorinz.via2.dpad

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.pekorinz.via2.data.PreferenceManager

/**
 * Gestor del control D-Pad para Android TV.
 * Controla la navegación inmersiva full-screen, desplazamiento de páginas,
 * interacción con videos en pantalla completa y visibilidad dinámica de la barra de herramientas.
 */
class DPadController(
    private val webView: WebView,
    private val toolbarContainer: ViewGroup,
    private val cursorOverlay: CursorOverlayView,
    private val preferenceManager: PreferenceManager,
    private val onModeChanged: (DPadMode) -> Unit,
    private val onRequestShowToolbar: () -> Unit,
    private val onRequestHideToolbar: () -> Unit,
    private val getActiveFullscreenView: (() -> View?)? = null
) {

    var currentMode: DPadMode = DPadMode.CURSOR
        private set(value) {
            field = value
            cursorOverlay.isCursorVisible = (value == DPadMode.CURSOR)
            webView.isFocusable = (value == DPadMode.FOCO)
            onModeChanged(value)
        }

    /**
     * Cuando es true, las flechas y ENTER/CENTER se dejan pasar a la página
     * en lugar de ser consumidas por el cursor virtual o el scroll.
     * Se activa automáticamente para sitios con navegación D-Pad nativa (YouTube TV).
     */
    private var passthroughToPage: Boolean = false

    init {
        currentMode = preferenceManager.getSavedMode()
    }

    fun setPassthroughToPage(enabled: Boolean) {
        passthroughToPage = enabled
    }

    fun switchMode(newMode: DPadMode? = null) {
        val targetMode = newMode ?: currentMode.next()
        currentMode = targetMode
        preferenceManager.saveMode(targetMode)
    }

    fun centerCursor() {
        cursorOverlay.centerCursor()
    }

    /**
     * Procesa los eventos de teclas del control remoto de la TV.
     * Retorna true si el evento fue consumido.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val fullscreenView = getActiveFullscreenView?.invoke()
        val isFullscreen = fullscreenView != null

        // Si la barra de herramientas está visible y enfocada (solo fuera de pantalla completa)
        if (!isFullscreen && toolbarContainer.visibility == View.VISIBLE && toolbarContainer.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                onRequestHideToolbar()
                return true
            }
            return false
        }

        // Sitios con navegación D-Pad nativa (YouTube TV): dejar pasar las teclas a la página.
        if (passthroughToPage) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> return false
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            if (event.action == KeyEvent.ACTION_UP && currentMode == DPadMode.CURSOR) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    dispatchClickUpAtCursor()
                    return true
                }
            }
            return false
        }

        val stepPx = getMoveStepPx()

        when (currentMode) {
            DPadMode.CURSOR -> {
                val hostView = fullscreenView ?: webView
                val maxY = if (hostView.height > 0) hostView.height.toFloat() - 30f else 1080f
                val maxX = if (hostView.width > 0) hostView.width.toFloat() - 30f else 1920f

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val newY = cursorOverlay.cursorY - stepPx
                        val minTopY = 25f

                        if (newY <= minTopY) {
                            if (!isFullscreen && webView.scrollY > 0) {
                                webView.scrollBy(0, -(stepPx * 2.5f).toInt())
                                cursorOverlay.setCursorPosition(cursorOverlay.cursorX, minTopY)
                            } else if (!isFullscreen) {
                                onRequestShowToolbar()
                            } else {
                                cursorOverlay.setCursorPosition(cursorOverlay.cursorX, minTopY)
                            }
                        } else {
                            cursorOverlay.setCursorPosition(cursorOverlay.cursorX, newY)
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val newY = cursorOverlay.cursorY + stepPx
                        if (newY >= maxY) {
                            if (!isFullscreen) {
                                webView.scrollBy(0, (stepPx * 2.5f).toInt())
                            }
                            cursorOverlay.setCursorPosition(cursorOverlay.cursorX, maxY)
                        } else {
                            cursorOverlay.setCursorPosition(cursorOverlay.cursorX, newY)
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val newX = cursorOverlay.cursorX - stepPx
                        if (newX <= 30f) {
                            if (!isFullscreen && webView.canScrollHorizontally(-1)) {
                                webView.scrollBy(-(stepPx * 2f).toInt(), 0)
                            }
                            cursorOverlay.setCursorPosition(30f, cursorOverlay.cursorY)
                        } else {
                            cursorOverlay.setCursorPosition(newX, cursorOverlay.cursorY)
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val newX = cursorOverlay.cursorX + stepPx
                        if (newX >= maxX) {
                            if (!isFullscreen && webView.canScrollHorizontally(1)) {
                                webView.scrollBy((stepPx * 2f).toInt(), 0)
                            }
                            cursorOverlay.setCursorPosition(maxX, cursorOverlay.cursorY)
                        } else {
                            cursorOverlay.setCursorPosition(newX, cursorOverlay.cursorY)
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        dispatchClickDownAtCursor()
                        return true
                    }
                    else -> return false
                }
            }

            DPadMode.DESPLAZAMIENTO -> {
                if (isFullscreen) return false
                val scrollAmountVertical = (webView.height * 0.30f).toInt().coerceAtLeast(120)
                val scrollAmountHorizontal = (webView.width * 0.15f).toInt().coerceAtLeast(80)

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (webView.scrollY == 0) {
                            onRequestShowToolbar()
                            return true
                        }
                        webView.scrollBy(0, -scrollAmountVertical)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        webView.scrollBy(0, scrollAmountVertical)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        webView.scrollBy(-scrollAmountHorizontal, 0)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        webView.scrollBy(scrollAmountHorizontal, 0)
                        return true
                    }
                    else -> return false
                }
            }

            DPadMode.FOCO -> {
                if (isFullscreen) return false
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && webView.scrollY == 0) {
                    onRequestShowToolbar()
                    return true
                }
                return false
            }
        }
    }

    private fun dispatchClickDownAtCursor() {
        val downTime = SystemClock.uptimeMillis()
        lastClickDownTime = downTime
        val x = cursorOverlay.cursorX
        val y = cursorOverlay.cursorY

        val fullscreenView = getActiveFullscreenView?.invoke()
        if (fullscreenView == null && toolbarContainer.visibility == View.VISIBLE) {
            val targetNativeView = findViewAtScreenLocation(toolbarContainer, x, y)
            if (targetNativeView != null) {
                targetNativeView.requestFocus()
                targetNativeView.performClick()
                return
            }
        }

        val targetView = fullscreenView ?: webView
        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        val targetX = x - location[0]
        val targetY = y - location[1]

        val event = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, targetX, targetY, 0
        )
        targetView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun dispatchClickUpAtCursor() {
        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (lastClickDownTime > 0) lastClickDownTime else eventTime
        val x = cursorOverlay.cursorX
        val y = cursorOverlay.cursorY

        val fullscreenView = getActiveFullscreenView?.invoke()
        if (fullscreenView == null && toolbarContainer.visibility == View.VISIBLE) {
            val targetNativeView = findViewAtScreenLocation(toolbarContainer, x, y)
            if (targetNativeView != null) {
                lastClickDownTime = 0
                return
            }
        }

        val targetView = fullscreenView ?: webView
        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        val targetX = x - location[0]
        val targetY = y - location[1]

        val event = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_UP, targetX, targetY, 0
        )
        targetView.dispatchTouchEvent(event)
        event.recycle()
        lastClickDownTime = 0
    }

    private fun findViewAtScreenLocation(parent: ViewGroup, x: Float, y: Float): View? {
        val location = IntArray(2)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.getLocationOnScreen(location)
            val childX = location[0].toFloat()
            val childY = location[1].toFloat()
            val childWidth = child.width.toFloat()
            val childHeight = child.height.toFloat()

            if (x >= childX && x <= childX + childWidth && y >= childY && y <= childY + childHeight) {
                return child
            }
        }
        return null
    }

    private fun getMoveStepPx(): Float {
        val density = webView.resources.displayMetrics.density
        val speedMultiplier = preferenceManager.getCursorSpeedMultiplier()
        return 22f * density * speedMultiplier
    }

    companion object {
        private var lastClickDownTime: Long = 0
    }
}
