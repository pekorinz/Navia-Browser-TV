package com.pekorinz.via2.dns

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy HTTP/HTTPS local de Via2.
 *
 * Es la pieza que permite "cambiar el DNS solo dentro del navegador" sin VPN
 * ni permisos: el WebView apunta a 127.0.0.1, este proxy lee el destino real,
 * resuelve el nombre con [DohResolver] y abre el socket contra esa IP.
 *
 * Modo CONNECT (HTTPS): handshake TLS de punta a punta, el contenido no se
 * toca. Modo absoluto (HTTP): se reescribe la linea de peticion y se reenvia.
 *
 * Importante: se escucha solo en 127.0.0.1, jamas en la red local.
 */
class LocalDnsProxy(private val resolver: DohResolver) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    val activeConnections = AtomicInteger(0)
    val tunneledRequests = AtomicInteger(0)
    val resolvedByDoh = AtomicInteger(0)
    val resolvedBySystem = AtomicInteger(0)

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "via2-dns-proxy").apply { isDaemon = true }
    }

    /** Levanta el proxy. Devuelve el puerto o 0 si no se pudo. */
    @Synchronized
    fun start(): Int {
        if (isRunning) return port
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(LOOPBACK, 0), 64)
            serverSocket = ss
            port = ss.localPort
            isRunning = true
            Thread({ acceptLoop(ss) }, "via2-dns-proxy-accept").apply {
                isDaemon = true
                start()
            }
            port
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el proxy DNS local: ${e.message}")
            isRunning = false
            port = 0
            0
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = 0
        activeConnections.set(0)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "accept: ${e.message}")
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        activeConnections.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val header = readHeaderBlock(input) ?: return
            val lines = header.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return
            val requestLine = lines[0].split(' ')
            if (requestLine.size < 3) return
            val method = requestLine[0]
            val target = requestLine[1]

            val headers = ArrayList<Pair<String, String>>(lines.size)
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers += lines[i].substring(0, idx).trim() to lines[i].substring(idx + 1).trim()
                }
            }
            tunneledRequests.incrementAndGet()

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(client, target, headers)
            } else {
                handlePlainHttp(client, method, target, headers)
            }
        } catch (e: Exception) {
            Log.d(TAG, "conexion cerrada: ${e.message}")
        } finally {
            activeConnections.decrementAndGet()
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- HTTPS

    private fun handleConnect(
        client: Socket,
        target: String,
        headers: List<Pair<String, String>>
    ) {
        val idx = target.lastIndexOf(':')
        if (idx <= 0) {
            writeProxyError(client, 400, "Destino invalido")
            return
        }
        val host = target.substring(0, idx).trim('[', ']')
        val port = target.substring(idx + 1).toIntOrNull() ?: 443

        if (resolver.blockedAt(host) != null) {
            // El proveedor DNS responde que este dominio no existe (filtro de
            // anuncios del propio DNS). Se corta antes de abrir socket.
            writeProxyError(client, 502, "Dominio bloqueado por el DNS: $host")
            return
        }
        val upstream = connectWithOurDns(host, port)
        if (upstream == null) {
            writeProxyError(client, 502, "No se pudo conectar con $host usando el DNS elegido")
            return
        }
        try {
            // El cliente espera "200 Connection established" ANTES del TLS.
            client.soTimeout = 0
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            out.flush()

            val c2u = pool.submit { pump(client.getInputStream(), upstream.getOutputStream()) }
            val u2c = pool.submit { pump(upstream.getInputStream(), client.getOutputStream()) }
            try {
                u2c.get()
            } catch (_: Exception) {
            }
            c2u.cancel(true)
        } finally {
            closeQuietly(upstream)
        }
    }

    // ----------------------------------------------------------------- HTTP

    private fun handlePlainHttp(
        client: Socket,
        method: String,
        target: String,
        headers: List<Pair<String, String>>
    ) {
        val uri = try {
            URI(target)
        } catch (e: Exception) {
            null
        }
        val host = uri?.host
        if (uri == null || host.isNullOrEmpty()) {
            writeProxyError(client, 400, "Peticion HTTP invalida")
            return
        }
        if (resolver.blockedAt(host) != null) {
            writeProxyError(client, 502, "Dominio bloqueado por el DNS: $host")
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        val upstream = connectWithOurDns(host, port)
        if (upstream == null) {
            writeProxyError(client, 502, "No se pudo resolver $host con el DNS elegido")
            return
        }
        try {
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((k, v) in headers) {
                if (k.equals("Proxy-Connection", true)) continue
                if (k.equals("Connection", true)) continue
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            val up = upstream.getOutputStream()
            up.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            up.flush()
            pump(upstream.getInputStream(), client.getOutputStream())
        } finally {
            closeQuietly(upstream)
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * Abre el socket del destino usando el DNS del navegador. Si el proveedor
     * falla, se cae al DNS del sistema para no dejar la pagina en blanco.
     */
    private fun connectWithOurDns(host: String, port: Int): Socket? {
        if (!resolver.isEnabled || isIpLiteral(host)) {
            // Sin DNS cambiado, o es una IP literal: se conecta normalmente.
            return try {
                Socket().apply {
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = IO_TIMEOUT_MS
                    tcpNoDelay = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "conexion directa fallida $host:$port ${e.message}")
                null
            }
        }

        val ips = resolver.resolveAllIpv4(host)
        for (ip in ips) {
            try {
                resolvedByDoh.incrementAndGet()
                return Socket().apply {
                    connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    tcpNoDelay = true
                    soTimeout = IO_TIMEOUT_MS
                }
            } catch (e: Exception) {
                Log.d(TAG, "IP $ip no acepto conexion: ${e.message}")
            }
        }

        // Sin respuesta del proveedor: ultimo recurso, fabrica del sistema.
        for (ip in resolver.fallbackSystemResolve(host)) {
            try {
                resolvedBySystem.incrementAndGet()
                return Socket().apply {
                    connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    tcpNoDelay = true
                    soTimeout = IO_TIMEOUT_MS
                }
            } catch (e: Exception) {
                Log.d(TAG, "fallback $ip fallo: ${e.message}")
            }
        }
        return null
    }

    private fun pump(input: InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readHeaderBlock(input: BufferedInputStream): String? {
        val sb = StringBuilder(1024)
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return sb.toString().dropLast(4)
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
            if (sb.length > MAX_HEADER_BYTES) return null
        }
    }

    private fun writeProxyError(client: Socket, code: Int, message: String) {
        try {
            val body = message.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code Via2 DNS\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    companion object {
        private const val TAG = "LocalDnsProxy"
        private const val LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val IO_TIMEOUT_MS = 60_000
        private const val HEADER_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_BYTES = 64 * 1024
    }
}
