package com.pekorinz.via2.dns

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolucion DNS propia del navegador via DNS-over-HTTPS (RFC 8484).
 *
 * Que hace y que NO hace:
 *  - Pregunta directo al proveedor elegido (Cloudflare, Google, Quad9, AdGuard,
 *    NextDNS, DNS0, Mullvad o una URL propia).
 *  - Guarda cache en memoria con TTL propio.
 *  - Detecta los dominios que el proveedor responde como inexistentes y los
 *    expone para poder mostrarlos como "bloqueado por DNS".
 *  - NO toca el DNS del sistema Android ni usa VpnService. Solo afecta a las
 *    peticiones que Via2 decide resolver aqui.
 *
 * Uso: se llama a [resolveAll] y despues el socket se abre contra la IP devuelta.
 */
class DohResolver(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    var provider: DnsProvider = DnsProvider.SYSTEM
        private set

    @Volatile
    var customUrl: String = ""
        private set

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    val queryCount = AtomicInteger(0)
    val cacheHits = AtomicInteger(0)
    val blockedCount = AtomicInteger(0)

    private class Entry(val ips: List<String>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Hosts que el proveedor respondio como NXDOMAIN (tipico en filtros DNS). */
    private val blockedHosts = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun configure(provider: DnsProvider, customUrl: String) {
        this.provider = provider
        this.customUrl = customUrl
        this.isEnabled = provider.isUsable(customUrl)
        this.lastError = null
        cache.clear()
    }

    fun blockedAt(host: String): Long? = blockedHosts[host.lowercase()]

    fun clearCache() {
        cache.clear()
        blockedHosts.clear()
        lastError = null
    }

    fun cacheSize(): Int = cache.size

    /**
     * Devuelve las IPv4 conocidas para [host]. Cadena vacia si no hay nada.
     *
     * [allowBlocking] = false se usa en rutas donde no se puede esperar (por
     * ejemplo un hilo que no debe bloquearse); en ese caso solo se devuelve lo
     * que ya este en cache.
     */
    fun resolveIpv4(host: String, allowBlocking: Boolean = true): String? {
        val key = host.lowercase()
        cache[key]?.let {
            if (it.expiresAt > System.currentTimeMillis()) {
                cacheHits.incrementAndGet()
                return it.ips.firstOrNull()
            }
            cache.remove(key)
        }
        if (!isEnabled) return null
        if (!allowBlocking) return null
        val ips = resolveBlocking(key, preferIpv4 = true)
        return ips?.firstOrNull()
    }

    /** Todos los registros A disponibles (para reintentar si uno falla). */
    fun resolveAllIpv4(host: String): List<String> {
        val key = host.lowercase()
        cache[key]?.let {
            if (it.expiresAt > System.currentTimeMillis()) {
                cacheHits.incrementAndGet()
                return it.ips
            }
            cache.remove(key)
        }
        if (!isEnabled) return emptyList()
        return resolveBlocking(key, preferIpv4 = true) ?: emptyList()
    }

    private fun resolveBlocking(host: String, preferIpv4: Boolean): List<String>? {
        val url = provider.urlFor(customUrl) ?: return null
        val qType = if (preferIpv4) DnsMessage.TYPE_A else DnsMessage.TYPE_AAAA
        val lock = CountDownLatch(1)
        // AtomicReference porque el resultado lo escribe otro hilo y se lee aqui:
        // Kotlin no deja capturar una var local desde un lambda y leerla fuera.
        val result = AtomicReference<List<String>?>(null)

        startQuery(host, qType, url) { ips, ttl, answer ->
            if (answer == DnsMessage.RCODE_NXDOMAIN) {
                blockedHosts[host] = System.currentTimeMillis()
                blockedCount.incrementAndGet()
                cache[host] = Entry(emptyList(), System.currentTimeMillis() + NXDOMAIN_TTL_MS)
            } else if (ips.isNotEmpty()) {
                cache[host] = Entry(ips, System.currentTimeMillis() + ttl)
                result.set(ips)
            }
            lock.countDown()
        }

        return try {
            if (!lock.await(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                lastError = "El DNS no respondio a tiempo"
                cache[host] = Entry(emptyList(), System.currentTimeMillis() + FAILURE_TTL_MS)
                null
            } else {
                result.get()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun startQuery(
        host: String,
        qType: Int,
        dohUrl: String,
        onDone: (ips: List<String>, ttlMs: Long, rcode: Int) -> Unit
    ) {
        Thread {
            val body: ByteArray
            var conn: HttpURLConnection? = null
            try {
                val qId = ThreadLocalRandom.current().nextInt(0x10000)
                body = DnsMessage.buildQuery(host, qType, qId)
                if (body.isEmpty()) {
                    onDone(emptyList(), 0, DnsMessage.RCODE_FORMERR)
                    return@Thread
                }
                queryCount.incrementAndGet()
                conn = (URL(dohUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = QUERY_TIMEOUT_MS.toInt()
                    readTimeout = QUERY_TIMEOUT_MS.toInt()
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/dns-message")
                    setRequestProperty("Accept", "application/dns-message")
                }
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode != 200) {
                    lastError = "El DNS respondio HTTP ${conn.responseCode}"
                    onDone(emptyList(), 0, -1)
                    return@Thread
                }
                val bytes = conn.inputStream.readBytes()
                val answer = DnsMessage.parseResponse(bytes)
                if (answer.rcode == DnsMessage.RCODE_OK && answer.addresses.isEmpty() &&
                    answer.cnames.isNotEmpty()
                ) {
                    // Se resolvio a un CNAME: se vuelve a preguntar por el destino.
                    lastError = null
                    startQuery(answer.cnames.first(), qType, dohUrl, onDone)
                    return@Thread
                }
                lastError = null
                onDone(answer.addresses, MIN_TTL_MS, answer.rcode)
            } catch (e: Exception) {
                Log.w(TAG, "DoH fallo para $host: ${e.message}")
                lastError = e.message ?: "Fallo de red"
                onDone(emptyList(), 0, -1)
            } finally {
                conn?.disconnect()
            }
        }.apply {
            name = "via2-doh"
            isDaemon = true
            start()
        }
    }

    /**
     * Ultimo recurso: pide al Solver del sistema (que es lo que ya usa la app)
     * para no dejar la web sin cargar cuando el proveedor esta bloqueado.
     */
    fun fallbackSystemResolve(host: String): List<String> = try {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private const val TAG = "DohResolver"
        private const val QUERY_TIMEOUT_MS = 3_500L
        private const val MIN_TTL_MS = 120_000L
        private const val FAILURE_TTL_MS = 30_000L
        private const val NXDOMAIN_TTL_MS = 300_000L
    }
}
