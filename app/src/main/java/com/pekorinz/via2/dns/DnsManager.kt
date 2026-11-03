package com.pekorinz.via2.dns

import android.content.Context
import android.util.Log
import com.pekorinz.via2.data.PreferenceManager

/**
 * Punto unico de acceso al DNS propio del navegador.
 *
 * Ciclo de vida: se configura en cada arranque y al guardar ajustes. El proxy
 * local NO se apaga al destruir la Activity, porque al cambiar de pestana puede
 * haber peticiones en vuelo; solo se detiene con [stop] al cerrar la app.
 */
object DnsManager {

    private const val TAG = "DnsManager"

    @Volatile
    private var _resolver: DohResolver? = null

    @Volatile
    private var proxy: LocalDnsProxy? = null

    @Volatile
    var isApplied: Boolean = false
        private set

    /** Resolver actual. Null hasta que se llame a [applyFromPreferences]. */
    val currentResolver: DohResolver?
        get() = _resolver

    /** Puerto del proxy local, o 0 si no esta activo. */
    val proxyPort: Int
        get() = if (isApplied) proxy?.port ?: 0 else 0

    /** Crea (una sola vez) el resolver y lo devuelve. */
    fun resolver(context: Context): DohResolver {
        _resolver?.let { return it }
        synchronized(this) {
            _resolver?.let { return it }
            val r = DohResolver(context.applicationContext)
            _resolver = r
            return r
        }
    }

    /**
     * Lee las preferencias y aplica el DNS. Se llama en cada arranque y cada vez
     * que el usuario guarda ajustes.
     */
    fun applyFromPreferences(context: Context, prefs: PreferenceManager) {
        val r = resolver(context)
        val provider = DnsProvider.fromId(prefs.getDnsProvider())
        val custom = prefs.getDnsCustomUrl()
        r.configure(provider, custom)
        r.clearCache()

        if (r.isEnabled) {
            val p = proxy ?: LocalDnsProxy(r).also { proxy = it }
            val port = p.start()
            if (port > 0) {
                isApplied = true
                Log.i(TAG, "DNS ${provider.id} aplicado en 127.0.0.1:$port")
            } else {
                isApplied = false
                Log.e(TAG, "No se pudo abrir el proxy local; se usa el DNS del sistema")
            }
        } else {
            proxy?.stop()
            isApplied = false
            Log.i(TAG, "DNS del sistema (sin cambios)")
        }
    }

    /** "127.0.0.1:PUERTO" que se pasa a WebView.setProxyOverride. */
    fun proxyUrl(): String? {
        val p = proxy
        return if (isApplied && p != null && p.port > 0) "127.0.0.1:${p.port}" else null
    }

    /** Detiene el proxy y libera recursos. Llamar al cerrar la app. */
    fun stop() {
        proxy?.stop()
        proxy = null
        isApplied = false
        Log.i(TAG, "DNS proxy detenido")
    }

    /** Alias por compatibilidad con el nombre anterior. */
    fun shutdown() = stop()

    /** Texto de estado para la pantalla de ajustes. */
    fun stats(): String {
        val r = _resolver ?: return "DNS del sistema (aun sin inicializar)"
        if (!r.isEnabled) return "DNS del sistema (sin cambios)"
        val p = proxy
        return buildString {
            append(r.provider.displayName)
            append(" · consultas ").append(r.queryCount.get())
            append(" · cache ").append(r.cacheSize())
            append(" · cache usada ").append(r.cacheHits.get())
            if (p != null && p.isRunning) {
                append(" · tuneles ").append(p.tunneledRequests.get())
                append(" · DoH ").append(p.resolvedByDoh.get())
                append(" · sistema ").append(p.resolvedBySystem.get())
            }
            r.lastError?.let { append(" · ultimo error: ").append(it) }
        }
    }
}
