package com.pekorinz.via2.adblock

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Suscripciones a listas de filtros (EasyList, EasyPrivacy, AdGuard...).
 *
 * Es lo mismo que hace Via en b5.d: cada lista es una URL .txt en formato
 * Adblock Plus que se descarga y se guarda en disco. El gestor de reglas
 * (AdBlockerManager) lee despues los archivos descargados.
 *
 * URLs tomadas literalmente de b5.d de Via 7.3.3, no inventadas.
 *
 * Formato en disco:
 *   filesDir/filters/<id>.txt   -> las reglas, tal cual vienen
 *   filesDir/filters/meta.json  -> nombre, url, fecha, numero de reglas
 *
 * Las listas NO se activan solas: el usuario las marca. EasyList viene
 * marcada por defecto porque es la base de cualquier bloqueador.
 */
class FilterSubscriptions(private val context: Context) {

    /** Una lista disponible para suscribirse. */
    data class Filter(
        val id: String,
        val name: String,
        val url: String,
        val description: String,
        val defaultOn: Boolean
    )

    /** Estado de una lista ya descargada. */
    data class State(
        val rules: Int = 0,
        val updatedAt: Long = 0L,
        val enabled: Boolean = false
    ) {
        fun dateLabel(): String {
            if (updatedAt <= 0L) return ""
            val f = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            return f.format(Date(updatedAt))
        }
    }

    private val dir: File
        get() = File(context.filesDir, "filters").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.txt")

    private fun metaFile() = File(dir, "meta.json")

    /**
     * Catalogo de listas, copiado de b5.d de Via.
     * Las tres primeras son las que Via trae por defecto.
     */
    val catalog: List<Filter> = listOf(
        Filter(
            "easylist",
            "EasyList",
            "https://easylist-downloads.adblockplus.org/easylist.txt",
            "Lista base de anuncios, la que usan casi todos los bloqueadores.",
            true
        ),
        Filter(
            "easyprivacy",
            "EasyPrivacy",
            "https://easylist-downloads.adblockplus.org/easyprivacy.txt",
            "Rastreadores, analitica y beacons de seguimiento.",
            true
        ),
        Filter(
            "antiadblock",
            "Adblock Warning Removal List",
            "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
            "Quita los avisos de \"desactiva tu bloqueador\".",
            true
        ),
        Filter(
            "adguard_es",
            "AdGuard Spanish/Portuguese",
            "https://filters.adtidy.org/extension/ublock/filters/9.txt",
            "Filtros para webs en espanol y portugues.",
            false
        ),
        Filter(
            "fanboy_annoyance",
            "Fanboy's Annoyances",
            "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
            "Elementos molestos: ventanas, banners, avisos de cookies.",
            false
        ),
        Filter(
            "peter_lowe",
            "Peter Lowe's List",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&mimetype=plaintext",
            "Servidores de publicidad, lista pequena y rapida.",
            false
        ),
        Filter(
            "urlhaus",
            "URLhaus Malicious",
            "https://curben.gitlab.io/malware-filter/urlhaus-filter-agh.txt",
            "Dominios que reparten malware.",
            false
        )
    )

    fun filterById(id: String): Filter? = catalog.firstOrNull { it.id == id }

    // ------------------------------------------------------------------
    //  Estado persistido
    // ------------------------------------------------------------------

    /** Lee el estado guardado de todas las listas. */
    fun states(): Map<String, State> {
        val out = mutableMapOf<String, State>()
        // Por defecto: las tres de Via activas si nunca se descargo nada
        for (f in catalog) {
            out[f.id] = State(enabled = f.defaultOn && !fileFor(f.id).exists())
        }
        val mf = metaFile()
        if (!mf.exists()) return out
        try {
            val arr = JSONArray(mf.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out[id] = State(
                    rules = o.optInt("rules", 0),
                    updatedAt = o.optLong("updatedAt", 0L),
                    enabled = o.optBoolean("enabled", false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "meta.json ilegible: ${e.message}")
        }
        return out
    }

    private fun saveStates(states: Map<String, State>) {
        val arr = JSONArray()
        for ((id, s) in states) {
            arr.put(
                JSONObject().apply {
                    put("id", id)
                    put("rules", s.rules)
                    put("updatedAt", s.updatedAt)
                    put("enabled", s.enabled)
                }
            )
        }
        metaFile().writeText(arr.toString())
    }

    /** Marca o desmarca una lista. Descargarla es cosa de [download]. */
    fun setEnabled(id: String, enabled: Boolean) {
        val st = states().toMutableMap()
        val cur = st[id] ?: State()
        st[id] = cur.copy(enabled = enabled)
        saveStates(st)
    }

    fun isEnabled(id: String): Boolean = states()[id]?.enabled == true

    fun enabledIds(): List<String> =
        states().filterValues { it.enabled }.keys.toList()

    // ------------------------------------------------------------------
    //  Descarga real
    // ------------------------------------------------------------------

    /** Resultado de descargar una lista. */
    data class Result(val id: String, val ok: Boolean, val rules: Int, val error: String?)

    /**
     * Descarga una lista por HTTP y cuenta las reglas utiles.
     *
     * Es una descarga de verdad: si no hay red o el servidor falla, se
     * devuelve el motivo y no se toca lo que ya habia.
     */
    fun download(filter: Filter): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(filter.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Via2/1.0")
            }
            val code = conn.responseCode
            if (code != 200) {
                return Result(filter.id, false, 0, "HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                return Result(filter.id, false, 0, "respuesta vacia")
            }

            // Se guarda tal cual: el parser de AdBlockerManager lo lee despues
            fileFor(filter.id).writeText(body)
            val rules = countRules(body)

            val st = states().toMutableMap()
            st[filter.id] = (st[filter.id] ?: State()).copy(
                rules = rules,
                updatedAt = System.currentTimeMillis(),
                // Descargar una lista la activa: es lo que el usuario pidio
                enabled = true
            )
            saveStates(st)
            Result(filter.id, true, rules, null)
        } catch (e: Exception) {
            Log.w(TAG, "descarga fallida ${filter.url}: ${e.message}")
            Result(filter.id, false, 0, e.message ?: "error de red")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Cuenta las reglas reales: ignora comentarios (!), cabeceras ([...]) y
     * lineas vacias. Si se contaran todas, el numero seria falso.
     */
    private fun countRules(body: String): Int {
        var n = 0
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("!")) continue
            if (line.startsWith("[") && line.endsWith("]")) continue
            n++
        }
        return n
    }

    /** Descarga todas las listas ACTIVAS. Devuelve un resultado por lista. */
    fun downloadEnabled(onEach: (Result) -> Unit): List<Result> {
        val ids = enabledIds().toSet()
        val out = mutableListOf<Result>()
        for (f in catalog) {
            if (f.id !in ids) continue
            val r = download(f)
            out.add(r)
            onEach(r)
        }
        return out
    }

    /** Numero total de reglas de todas las listas activas y descargadas. */
    fun activeRuleCount(): Int = states()
        .filterValues { it.enabled && it.rules > 0 }
        .values
        .sumOf { it.rules }

    /** Lineas de reglas activas, para pasarselas al matcher. */
    fun activeRuleLines(): List<String> {
        val st = states()
        val out = mutableListOf<String>()
        for (f in catalog) {
            val s = st[f.id] ?: continue
            if (!s.enabled) continue
            val file = fileFor(f.id)
            if (!file.exists()) continue
            file.bufferedReader().use { br ->
                br.forEachLine { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) return@forEachLine
                    out.add(t)
                }
            }
        }
        return out
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "FilterSubscriptions"
    }
}
