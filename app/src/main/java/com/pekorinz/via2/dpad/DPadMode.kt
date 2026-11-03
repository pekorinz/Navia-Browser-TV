package com.pekorinz.via2.dpad

import com.pekorinz.via2.R

/**
 * Representa los 3 modos de navegacion D-Pad para Android TV.
 */
enum class DPadMode(val labelResId: Int, val badgeColorResId: Int) {
    CURSOR(R.string.mode_cursor, R.color.mode_badge_cursor),
    DESPLAZAMIENTO(R.string.mode_scroll, R.color.mode_badge_scroll),
    FOCO(R.string.mode_focus, R.color.mode_badge_focus);

    fun next(): DPadMode {
        val values = entries.toTypedArray()
        return values[(ordinal + 1) % values.size]
    }
}
