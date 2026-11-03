package com.pekorinz.via2.data

import android.content.Context
import android.content.SharedPreferences
import com.pekorinz.via2.dpad.DPadMode

/**
 * Gestor de almacenamiento local ligero de preferencias del usuario sin telemetría ni conectividad externa.
 */
class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedMode(): DPadMode {
        val modeName = prefs.getString(KEY_DPAD_MODE, DPadMode.CURSOR.name)
        return try {
            DPadMode.valueOf(modeName ?: DPadMode.CURSOR.name)
        } catch (e: Exception) {
            DPadMode.CURSOR
        }
    }

    fun saveMode(mode: DPadMode) {
        prefs.edit().putString(KEY_DPAD_MODE, mode.name).apply()
    }

    fun isAdBlockEnabled(): Boolean {
        return prefs.getBoolean(KEY_ADBLOCK_ENABLED, true)
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, enabled).apply()
    }

    fun getCursorSpeedMultiplier(): Float {
        return prefs.getFloat(KEY_CURSOR_SPEED, 1.0f)
    }

    fun setCursorSpeedMultiplier(speed: Float) {
        prefs.edit().putFloat(KEY_CURSOR_SPEED, speed).apply()
    }

    // ------------------------------------------------------------------ DNS

    fun getDnsProvider(): String = prefs.getString(KEY_DNS_PROVIDER, "system") ?: "system"

    fun setDnsProvider(id: String) {
        prefs.edit().putString(KEY_DNS_PROVIDER, id).apply()
    }

    fun getDnsCustomUrl(): String = prefs.getString(KEY_DNS_CUSTOM_URL, "") ?: ""

    fun setDnsCustomUrl(url: String) {
        prefs.edit().putString(KEY_DNS_CUSTOM_URL, url).apply()
    }

    /** Estadisticas acumuladas de bloqueo, para mostrarlas en ajustes. */
    fun getBlockedTotal(): Long = prefs.getLong(KEY_BLOCKED_TOTAL, 0L)

    fun addBlockedCount(count: Long) {
        if (count <= 0) return
        prefs.edit().putLong(KEY_BLOCKED_TOTAL, getBlockedTotal() + count).apply()
    }

    fun resetBlockedTotal() {
        prefs.edit().putLong(KEY_BLOCKED_TOTAL, 0L).apply()
    }

    fun isShowBlockedBadge(): Boolean = prefs.getBoolean(KEY_SHOW_BLOCKED_BADGE, true)

    fun setShowBlockedBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BLOCKED_BADGE, show).apply()
    }

    // ------------------------------------------------------------ marcadores

    /**
     * Marcadores guardados como pares (url, titulo).
     *
     * Se almacenan en una sola cadena con separador de unidad (U+001F), que no
     * puede aparecer en una URL valida. Asi no hace falta una base de datos
     * para algo que en TV se usa poco.
     */
    fun getBookmarks(): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_BOOKMARKS, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\u001F')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }
    }

    fun setBookmarks(items: List<Pair<String, String>>) {
        val raw = items.joinToString("\n") { "${it.first}\u001F${it.second}" }
        prefs.edit().putString(KEY_BOOKMARKS, raw).apply()
    }

    // -------------------------------------------------------------- historial

    fun getHistory(): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_HISTORY, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\u001F')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }
    }

    /** Anade una visita al historial, sin duplicados y con tope de 200. */
    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("file://")) return
        val current = getHistory().filter { it.first != url }
        val updated = (listOf(url to title) + current).take(MAX_HISTORY)
        val raw = updated.joinToString("\n") { "${it.first}\u001F${it.second}" }
        prefs.edit().putString(KEY_HISTORY, raw).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // ------------------------------------------------------------ incognito

    fun isIncognito(): Boolean = prefs.getBoolean(KEY_INCOGNITO, false)

    fun setIncognito(on: Boolean) {
        prefs.edit().putBoolean(KEY_INCOGNITO, on).apply()
    }

    // ------------------------------------------------- bloqueo por fuente

    /**
     * Filtros integrados (simple.txt). Separado del maestro, como en Via:
     * se puede tener el bloqueo encendido y solo las listas suscritas activas.
     */
    fun isAdBlockBuiltinEnabled(): Boolean =
        prefs.getBoolean(KEY_ADBLOCK_BUILTIN, true)

    fun setAdBlockBuiltinEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ADBLOCK_BUILTIN, on).apply()
    }

    /** Filtros de las listas descargadas (EasyList y compania). */
    fun isAdBlockSubscriptionsEnabled(): Boolean =
        prefs.getBoolean(KEY_ADBLOCK_SUBS, true)

    fun setAdBlockSubscriptionsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ADBLOCK_SUBS, on).apply()
    }

    /** Ultima vez que se actualizaron las suscripciones (0 = nunca). */
    fun getSubsLastUpdate(): Long = prefs.getLong(KEY_SUBS_LAST_UPDATE, 0L)

    fun setSubsLastUpdate(timeMs: Long) {
        prefs.edit().putLong(KEY_SUBS_LAST_UPDATE, timeMs).apply()
    }

    /** Intervalo de actualizacion automatica, en dias (0 = nunca). */
    fun getSubsIntervalDays(): Int = prefs.getInt(KEY_SUBS_INTERVAL, 0)

    fun setSubsIntervalDays(days: Int) {
        prefs.edit().putInt(KEY_SUBS_INTERVAL, days).apply()
    }

    companion object {
        private const val MAX_HISTORY = 200
        private const val PREFS_NAME = "navegador_tv_prefs"
        private const val KEY_DPAD_MODE = "key_dpad_mode"
        private const val KEY_ADBLOCK_ENABLED = "key_adblock_enabled"
        private const val KEY_CURSOR_SPEED = "key_cursor_speed"
        private const val KEY_DNS_PROVIDER = "key_dns_provider"
        private const val KEY_DNS_CUSTOM_URL = "key_dns_custom_url"
        private const val KEY_BLOCKED_TOTAL = "key_blocked_total"
        private const val KEY_SHOW_BLOCKED_BADGE = "key_show_blocked_badge"
        private const val KEY_BOOKMARKS = "key_bookmarks"
        private const val KEY_HISTORY = "key_history"
        private const val KEY_INCOGNITO = "key_incognito"
        private const val KEY_ADBLOCK_BUILTIN = "key_adblock_builtin"
        private const val KEY_ADBLOCK_SUBS = "key_adblock_subs"
        private const val KEY_SUBS_LAST_UPDATE = "key_subs_last_update"
        private const val KEY_SUBS_INTERVAL = "key_subs_interval"
    }
}
