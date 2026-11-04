package com.pekorinz.via2.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import com.pekorinz.via2.dns.DnsManager
import java.lang.reflect.Proxy

/**
 * WebView del navegador, optimizado para TV.
 *
 * Ademas de los ajustes de seguridad, es el encargado de enchufar el DNS propio:
 * Android no expone setProxyOverride() en el SDK publico (es @SystemApi), asi que
 * se aplica por reflexion sobre androidx.webkit. Si el WebView de la TV no lo
 * soporta, la app sigue funcionando con el DNS del sistema y se registra el aviso.
 */
class TVWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** true si el DNS propio quedo realmente aplicado al motor del WebView. */
    @Volatile
    var dnsProxyApplied: Boolean = false
        private set

    init {
        configureSecuritySettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSecuritySettings() {
        val settings = this.settings

        // JavaScript habilitado sin exposicion de interfaces nativas
        settings.javaScriptEnabled = true

        // Sin acceso a archivos locales desde origenes web
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // Optimizaciones de visualizacion para TV (16:9) y reproduccion multimedia
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.mediaPlaybackRequiresUserGesture = false

        // Seguridad y cache
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Sin cache de formularios ni geolocalizacion automatica
        settings.saveFormData = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            startSafeBrowsing(context.applicationContext, null)
        }

        applyDnsProxyIfNeeded()
    }

    /**
     * Enchufa el proxy DNS local al motor del WebView.
     *
     * WebView.setProxyOverride(ProxyConfig) existe desde Chromium 79 pero es
     * SystemApi. Se resuelve por reflexion; el callback ProxyOverride que exige
     * se construye con java.lang.reflect.Proxy para no depender de esa clase en
     * tiempo de compilacion.
     */
    fun applyDnsProxyIfNeeded() {
        val proxyUrl = DnsManager.proxyUrl()
        if (proxyUrl == null) {
            dnsProxyApplied = false
            return
        }
        if (dnsProxyApplied) return

        val ok = try {
            val webViewClass = Class.forName("android.webkit.WebView")
            val proxyConfigClass = Class.forName("android.net.ProxyConfig")

            // ProxyConfig.Builder con una unica regla "127.0.0.1:PUERTO".
            val builder = proxyConfigClass.getDeclaredConstructor().newInstance()
            val builderClass = builder.javaClass
            builderClass.getMethod("addProxyRule", String::class.java)
                .invoke(builder, proxyUrl)

            // Constructor privado ProxyConfig(ProxyConfig.Builder).
            val configCtor = proxyConfigClass.getDeclaredConstructor(builderClass)
            configCtor.isAccessible = true
            val config = configCtor.newInstance(builder)

            val overrideClass = Class.forName("android.webkit.WebView\$ProxyOverride")
            val proxyInstance = Proxy.newProxyInstance(
                overrideClass.classLoader,
                arrayOf(overrideClass)
            ) { _, method, _ ->
                when (method.name) {
                    "getProxyConfig" -> config
                    "isValid" -> true
                    else -> null
                }
            }

            val method = webViewClass.getDeclaredMethod(
                "setProxyOverride",
                proxyConfigClass,
                overrideClass,
                android.os.Handler::class.java
            )
            method.isAccessible = true
            method.invoke(this, config, proxyInstance, null)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "El WebView de esta TV no acepta proxy propio: ${e.message}")
            false
        }

        dnsProxyApplied = ok
        if (!ok) {
            // Fallback: cargar indices "a mano" resolviendo con el DNS elegido
            // es imposible en un motor cerrado; se deja constancia para la UI.
            Log.i(TAG, "DNS propio activo solo para descargas y utilidades")
        }
    }

    fun setupDownloadListener(onDownloadRequested: (String, String?, String?, Long) -> Unit) {
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            onDownloadRequested(url, userAgent, mimetype, contentLength)
        }
    }

    /**
     * Registra el manejador de descargas con la firma simple (url, mimeType)
     * que usa la interfaz principal. Si el MIME viene vacio se deduce de la URL.
     */
    fun setDownloadHandler(onDownload: (String, String) -> Unit) {
        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val mime = mimetype
                ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(url.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            onDownload(url, mime)
        }
    }

    companion object {
        private const val TAG = "TVWebView"
    }
}
