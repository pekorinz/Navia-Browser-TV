package com.pekorinz.via2.adblock

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.pekorinz.via2.data.PreferenceManager
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestor de bloqueo de publicidad para Android TV.
 *
 * Tiene DOS fuentes de reglas, como Via (b5.c + b5.d):
 *   1. Los filtros INTEGRADOS: el archivo simple.txt empaquetado en assets.
 *   2. Las SUSCRIPCIONES: listas EasyList/EasyPrivacy/... descargadas por
 *      FilterSubscriptions.
 *
 * Cada fuente se puede apagar por separado desde Ajustes > Privacidad >
 * Bloqueo de anuncios, igual que en Via.
 *
 * Las reglas se guardan en CONJUNTOS DE DOMINIOS, no en una lista lineal:
 * con ~90 000 reglas de varias listas, recorrer una lista por cada peticion
 * congelaria el WebView. Por eso se indexa por dominio y la consulta es O(1)
 * en el caso normal (hash del host y de cada sufijo).
 */
class AdBlockerManager(
    context: Context,
    private val preferenceManager: PreferenceManager
) {

    val domainWhitelist = DomainWhitelist(context)
    val subscriptions = FilterSubscriptions(context)

    private val appContext = context.applicationContext

    /** Dominios de simple.txt (filtros integrados). */
    private val builtinBlocked = HashSet<String>()
    private val builtinExceptions = HashSet<String>()

    /** Dominios de las suscripciones descargadas. */
    private val subBlocked = HashSet<String>()
    private val subExceptions = HashSet<String>()

    /** Reglas con ruta (||dominio.com/ruta), que no se pueden indexar por host. */
    private val pathRules = ArrayList<PathRule>()

    private val subsLoaded = AtomicBoolean(false)

    private class PathRule(val hostPart: String, val pathPart: String, val isException: Boolean)

    init {
        loadBuiltinRules()
        loadSubscriptionRules()
    }

    // ------------------------------------------------------------------
    //  Carga de reglas
    // ------------------------------------------------------------------

    private fun loadBuiltinRules() {
        try {
            appContext.assets.open("simple.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                        parseRule(line, builtinBlocked, builtinExceptions)
                    }
                }
            }
            Log.i(TAG, "integrados: ${builtinBlocked.size} dominios")
        } catch (e: Exception) {
            Log.w(TAG, "simple.txt no se pudo leer: ${e.message}")
            builtinBlocked.addAll(
                setOf(
                    "doubleclick.net", "googlesyndication.com",
                    "google-analytics.com", "adservice.google.com", "popads.net"
                )
            )
        }
    }

    /**
     * Carga las listas suscritas. Se puede volver a llamar tras descargar
     * una lista nueva (reloadSubscriptions).
     */
    fun loadSubscriptionRules() {
        subBlocked.clear()
        subExceptions.clear()
        synchronized(pathRules) { pathRules.clear() }
        try {
            val lines = subscriptions.activeRuleLines()
            for (line in lines) {
                parseRule(line, subBlocked, subExceptions)
            }
            subsLoaded.set(true)
            Log.i(TAG, "suscripciones: ${subBlocked.size} dominios de ${lines.size} reglas")
        } catch (e: Exception) {
            Log.w(TAG, "fallo cargando suscripciones: ${e.message}")
        }
    }

    /** Vuelve a leer las suscripciones desde disco. */
    fun reloadSubscriptions() {
        loadSubscriptionRules()
    }

    /**
     * Interpreta una linea en formato Adblock Plus y la mete en el conjunto
     * que corresponda.
     *
     * Soporta lo que de verdad aparece en EasyList:
     *   ||dominio.com^            -> bloquea el dominio y sus subdominios
     *   ||dominio.com/ruta^       -> bloquea esa ruta (va a pathRules)
     *   @@||dominio.com^          -> excepcion (nunca se bloquea)
     *   dominio.com##.clase       -> cosmetica, se ignora aqui (no es red)
     *   ...$third-party           -> se aplica igual: en TV no hay ventaja
     *                                en distinguir origen, y bloquearlo de mas
     *                                rompe menos que bloquear de menos.
     */
    private fun parseRule(
        line: String,
        blocked: HashSet<String>,
        exceptions: HashSet<String>
    ) {
        var rule = line.trim()
        if (rule.isEmpty()) return

        // Cosmetica (##, #@#) no es una regla de red
        if (rule.contains("##") || rule.contains("#@#") || rule.contains("#?#")) return
        // Options $ (third-party, script, image...): se cortan y se aplica igual
        rule = rule.substringBefore('$')

        var isException = false
        if (rule.startsWith("@@")) {
            isException = true
            rule = rule.substring(2)
        }

        // Solo interesan las reglas ancladas al dominio
        if (!rule.startsWith("||")) return
        rule = rule.substring(2)

        // Quitar el ancla de fin ^ y separadores
        rule = rule.substringBefore('^')

        if (rule.isEmpty()) return

        // Si tiene ruta (dominio.com/algo), no se puede indexar solo por host
        if (rule.contains('/')) {
            val host = rule.substringBefore('/')
            val path = rule.substring(host.length)
            if (host.isNotEmpty() && path.length > 1) {
                synchronized(pathRules) {
                    pathRules.add(PathRule(host.lowercase(), path.lowercase(), isException))
                }
            }
            return
        }

        // Wildcards (* o |) dentro del dominio: no se pueden indexar, se ignoran
        if (rule.contains('*') || rule.contains('|')) return

        val domain = rule.lowercase().trim('.')
        if (domain.isEmpty()) return

        if (isException) exceptions.add(domain) else blocked.add(domain)
    }

    // ------------------------------------------------------------------
    //  Consulta
    // ------------------------------------------------------------------

    /** Comprueba si un host o cualquiera de sus sufijos esta en un conjunto. */
    private fun matchesDomain(host: String, set: HashSet<String>): Boolean {
        if (set.isEmpty()) return false
        if (set.contains(host)) return true
        // Recorrer los sufijos: a.b.c -> b.c -> c
        var idx = host.indexOf('.')
        while (idx != -1 && idx < host.length - 1) {
            if (set.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    fun shouldBlock(urlStr: String?, request: WebResourceRequest): Boolean {
        if (!preferenceManager.isAdBlockEnabled()) return false
        if (request.isForMainFrame) return false
        if (urlStr.isNullOrEmpty()) return false
        if (domainWhitelist.isWhitelisted(urlStr)) return false

        val targetUrl = urlStr.lowercase()
        val uri = android.net.Uri.parse(targetUrl)
        val host = uri.host?.lowercase() ?: return false
        val path = (uri.encodedPath ?: "").lowercase()

        val useBuiltin = preferenceManager.isAdBlockBuiltinEnabled()
        val useSubs = preferenceManager.isAdBlockSubscriptionsEnabled()

        // Excepciones de CUALQUIER fuente activa ganan siempre
        if (useBuiltin && matchesDomain(host, builtinExceptions)) return false
        if (useSubs && matchesDomain(host, subExceptions)) return false

        // Reglas con ruta (EasyList trae muchas)
        if (useSubs) {
            synchronized(pathRules) {
                for (pr in pathRules) {
                    if (!path.contains(pr.pathPart)) continue
                    val hostOk = host == pr.hostPart || host.endsWith("." + pr.hostPart)
                    if (!hostOk) continue
                    // Si la regla es excepcion, no bloquear
                    if (pr.isException) return false
                    return true
                }
            }
        }

        if (useBuiltin && matchesDomain(host, builtinBlocked)) return true
        if (useSubs && matchesDomain(host, subBlocked)) return true

        return false
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /** Resumen para la pantalla de ajustes. */
    fun statsLine(): String =
        "${builtinBlocked.size + subBlocked.size} dominios · ${pathRules.size} rutas"

    companion object {
        private const val TAG = "AdBlockerManager"
    }
}
