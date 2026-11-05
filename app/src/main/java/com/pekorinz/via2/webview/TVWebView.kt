package com.pekorinz.via2.webview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pekorinz.via2.dns.DnsManager
import java.lang.reflect.Proxy

/**
 * WebView del navegador, optimizado para TV.
 *
 * Ademas de los ajustes de seguridad, es el encargado de enchufar el DNS propio:
 * Android no expone setProxyOverride() en el SDK publico (es @SystemApi), asi que
 * se aplica por reflexion sobre androidx.webkit. Si el WebView de la TV no lo
 * soporta, la app sigue funcionando con el DNS del sistema y se registra el aviso.
 *
 * Tambien gestiona el perfil de TV: al cargar youtube.com/tv usa un User-Agent de
 * televisor (para que YouTube sirva su interfaz de TV) y lo restaura al salir.
 * Ademas inyecta keep_alive.js al inicio del documento en YouTube para que el
 * video no se pause al perder el foco.
 */
class TVWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    @Volatile
    var dnsProxyApplied: Boolean = false
        private set

    @Volatile
    var tvProfileActive: Boolean = false
        private set

    private var uaBeforeTvProfile: String? = null

    init {
        configureSecuritySettings()
        installDocumentStartScripts()
    }

    fun currentUserAgent(): String? = settings.userAgentString

    fun isTvUrlUrl(url: String?): Boolean = isTvUrl(url)

    fun ensureTvProfileFor(url: String?) {
        applySiteProfile(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureSecuritySettings() {
        val settings = this.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.saveFormData = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            startSafeBrowsing(context.applicationContext, null)
        }
        applyDnsProxyIfNeeded()
    }

    private fun installDocumentStartScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.i(TAG, "DOCUMENT_START_SCRIPT no soportado: $KEEP_ALIVE_ASSET no se inyecta al inicio")
            return
        }
        try {
            val js = context.assets.open(KEEP_ALIVE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(this, js, KEEP_ALIVE_ORIGINS)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo inyectar $KEEP_ALIVE_ASSET: ${e.message}")
        }
    }

    fun applySiteProfile(url: String?) {
        val wantTv = isTvUrl(url)
        Log.d(TAG, "applySiteProfile url=$url wantTv=$wantTv active=$tvProfileActive")
        if (wantTv && !tvProfileActive) {
            uaBeforeTvProfile = settings.userAgentString
            settings.userAgentString = TV_USER_AGENT
            tvProfileActive = true
            Log.i(TAG, "Perfil TV activado. UA = $TV_USER_AGENT")
        } else if (!wantTv && tvProfileActive) {
            settings.userAgentString = uaBeforeTvProfile
            uaBeforeTvProfile = null
            tvProfileActive = false
            Log.i(TAG, "Perfil TV desactivado. UA restaurado")
        }
    }

    override fun loadUrl(url: String) {
        applySiteProfile(url)
        super.loadUrl(url)
    }

    fun goBackPreservingState() {
        if (canGoBack()) {
            val history = copyBackForwardList()
            applySiteProfile(history.getItemAtIndex(history.currentIndex - 1)?.url)
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            goBack()
            postDelayed({ settings.cacheMode = WebSettings.LOAD_DEFAULT }, 1200)
        }
    }

    fun applyDnsProxyIfNeeded() {
        val proxyUrl = DnsManager.proxyUrl()
        if (proxyUrl == null) { dnsProxyApplied = false; return }
        if (dnsProxyApplied) return
        val ok = try {
            val webViewClass = Class.forName("android.webkit.WebView")
            val proxyConfigClass = Class.forName("android.net.ProxyConfig")
            val builder = proxyConfigClass.getDeclaredConstructor().newInstance()
            val builderClass = builder.javaClass
            builderClass.getMethod("addProxyRule", String::class.java).invoke(builder, proxyUrl)
            val configCtor = proxyConfigClass.getDeclaredConstructor(builderClass)
            configCtor.isAccessible = true
            val config = configCtor.newInstance(builder)
            val overrideClass = Class.forName("android.webkit.WebView" + "\$ProxyOverride")
            val proxyInstance = Proxy.newProxyInstance(overrideClass.classLoader, arrayOf(overrideClass)) { _, method, _ ->
                when (method.name) {
                    "getProxyConfig" -> config
                    "isValid" -> true
                    else -> null
                }
            }
            val method = webViewClass.getDeclaredMethod("setProxyOverride", proxyConfigClass, overrideClass, android.os.Handler::class.java)
            method.isAccessible = true
            method.invoke(this, config, proxyInstance, null)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "El WebView de esta TV no acepta proxy propio: ${e.message}")
            false
        }
        dnsProxyApplied = ok
        if (!ok) Log.i(TAG, "DNS propio activo solo para descargas y utilidades")
    }

    fun setupDownloadListener(onDownloadRequested: (String, String?, String?, Long) -> Unit) {
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            onDownloadRequested(url, userAgent, mimetype, contentLength)
        }
    }

    fun setDownloadHandler(onDownload: (String, String) -> Unit) {
        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val mime = mimetype ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(url.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            onDownload(url, mime)
        }
    }

    companion object {
        private const val TAG = "TVWebView"
        private const val KEEP_ALIVE_ASSET = "keep_alive.js"
        private val KEEP_ALIVE_ORIGINS = setOf(
            "https://www.youtube.com",
            "https://youtube.com",
            "https://m.youtube.com"
        )
        const val TV_USER_AGENT =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36"

        fun isTvUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            if (host != "youtube.com" && host != "www.youtube.com") return false
            val path = uri.path.orEmpty()
            return path == "/tv" || path.startsWith("/tv/")
        }
    }
}
