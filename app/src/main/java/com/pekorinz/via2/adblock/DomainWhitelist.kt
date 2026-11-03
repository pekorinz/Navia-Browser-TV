package com.pekorinz.via2.adblock

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Gestor de excepciones/lista blanca de dominios en los que el usuario ha desactivado el AdBlocker.
 */
class DomainWhitelist(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(WHITELIST_PREFS, Context.MODE_PRIVATE)

    fun isWhitelisted(urlStr: String?): Boolean {
        if (urlStr.isNullOrEmpty()) return false
        val host = extractHost(urlStr) ?: return false
        return prefs.getBoolean(host, false)
    }

    fun setWhitelisted(urlStr: String, whitelisted: Boolean) {
        val host = extractHost(urlStr) ?: return
        prefs.edit().putBoolean(host, whitelisted).apply()
    }

    private fun extractHost(urlStr: String): String? {
        return try {
            val uri = Uri.parse(urlStr)
            uri.host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val WHITELIST_PREFS = "adblock_domain_whitelist"
    }
}
