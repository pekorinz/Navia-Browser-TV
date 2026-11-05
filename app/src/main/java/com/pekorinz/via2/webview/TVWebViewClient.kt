package com.pekorinz.via2.webview

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pekorinz.via2.adblock.AdBlockerManager
import com.pekorinz.via2.dns.DohResolver

/**
 * WebViewClient del navegador.
 *
 * Intercepta solicitudes de red para bloqueo de anuncios y DNS DoH,
 * gestiona acciones internas mediante navia:// y preserva el estado y reproduccion de video.
 *
 * Tambien asegura que el perfil de TV (User-Agent) se aplique ANTES de cargar
 * cualquier URL https, ya sea por clic del usuario, por redireccion del servidor
 * o por navegacion programatica. Sin esto, YouTube TV detectaba un UA de movil
 * y redirigia a la pagina normal.
 */
class TVWebViewClient(
    private val adBlockerManager: AdBlockerManager,
    private val dohResolver: DohResolver?,
    private val onPageUrlChanged: (String) -> Unit,
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onHttpWarning: (String, () -> Unit) -> Unit,
    private val onBlockedCountChanged: (Int) -> Unit = {},
    private val onProgress: (Int) -> Unit = {},
    private val onPageError: (Int, String, String) -> Unit = { _, _, _ -> },
    private val onAppAction: (String) -> Unit = {}
) : WebViewClient() {

    private var blockedThisPage = 0

    /** La UI puede leer esto para el contador de la barra de estado. */
    @Volatile
    var totalBlocked: Int = 0
        private set

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (!request.isForMainFrame) return false

        if (url.startsWith("navia://")) {
            val uri = Uri.parse(url)
            when (uri.host) {
                "restore-tabs" -> onAppAction(ACTION_RESTORE_TABS)
                "show-toolbar" -> onAppAction(ACTION_SHOW_TOOLBAR)
                "close-tab" -> onAppAction(ACTION_CLOSE_CURRENT_TAB)
                "save-doh" -> {
                    val dohUrl = uri.getQueryParameter("url").orEmpty()
                    onAppAction("$ACTION_SAVE_DOH:$dohUrl")
                }
                else -> onAppAction(uri.host.orEmpty())
            }
            return true
        }

        // Esquemas locales y web validos en el WebView
        if (url.startsWith("file://") || url.startsWith("about:") ||
            url.startsWith("data:") || url.startsWith("javascript:")
        ) {
            return false
        }

        // Esquemas externos desconocidos: se derivan o cancelan
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return true
        }

        if (url.startsWith("http://")) {
            onHttpWarning(url) {
                view.loadUrl(url)
            }
            return true
        }

        // Navegacion normal https: asegurar que el perfil (UA) corresponda ANTES de que
        // WebView pida la URL al servidor. Esto es lo que faltaba para youtube.com/tv.
        (view as? TVWebView)?.applySiteProfile(url)
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()

        // Filtro de anuncios: solo lee valores volatiles, seguro en este hilo.
        if (adBlockerManager.shouldBlock(url, request)) {
            blockedThisPage++
            totalBlocked++
            onBlockedCountChanged(blockedThisPage)
            return adBlockerManager.createEmptyResponse()
        }

        // Si el DNS propio bloqueo el dominio, se responde vacio y se cuenta.
        val host = request.url.host
        if (dohResolver != null && host != null && dohResolver.blockedAt(host) != null) {
            blockedThisPage++
            totalBlocked++
            onBlockedCountChanged(blockedThisPage)
            return adBlockerManager.createEmptyResponse()
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Asegurar el perfil (UA) en cada arranque de pagina principal.
        (view as? TVWebView)?.applySiteProfile(url)
        Log.d(TAG, "onPageStarted url=$url ua=${(view as? TVWebView)?.currentUserAgent()}")
        blockedThisPage = 0
        onBlockedCountChanged(0)
        onPageUrlChanged(url)
        onLoadingStateChanged(true)
        onProgress(0)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        onPageUrlChanged(url)
        onLoadingStateChanged(false)
        onProgress(100)

        // Inyectar rastreador y auto-restaurador de progreso de video y scroll en paginas web
        if (url.startsWith("http://") || url.startsWith("https://")) {
            view.evaluateJavascript(VIDEO_RESTORE_JS, null)
        }

        onPageFinishedCallback?.invoke(view, url)
    }

    /** Se llama al terminar de cargar: aqui se reaplica el modo nocturno. */
    var onPageFinishedCallback: ((WebView, String) -> Unit)? = null

    companion object {
        private const val TAG = "TVWebViewClient"

        const val ACTION_RESTORE_TABS = "restore-tabs"
        const val ACTION_SHOW_TOOLBAR = "show-toolbar"
        const val ACTION_CLOSE_CURRENT_TAB = "close-current-tab"
        const val ACTION_SAVE_DOH = "save-doh"

        /**
         * Script ligero que preserva continuamente el progreso de video y scroll en sessionStorage.
         * Si el usuario sufre redirecciones por clic de anuncios y luego retrocede, el video
         * se auto-posiciona de inmediato en el segundo exacto y en la posicion previa.
         */
        private const val VIDEO_RESTORE_JS = """
        (function() {
            if (window.__naviaProgressTracker) return;
            window.__naviaProgressTracker = true;
            var key = 'navia_vid_' + location.origin + location.pathname;

            function saveState() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var v = vids[i];
                    if (v && v.duration > 0 && !v.ended) {
                        try {
                            sessionStorage.setItem(key, JSON.stringify({
                                t: v.currentTime,
                                y: window.scrollY || 0,
                                x: window.scrollX || 0
                            }));
                            break;
                        } catch(e) {}
                    }
                }
            }

            function restoreState() {
                try {
                    var raw = sessionStorage.getItem(key);
                    if (!raw) return;
                    var d = JSON.parse(raw);
                    if (d && d.t > 0) {
                        var vids = document.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            var v = vids[i];
                            if (v && Math.abs(v.currentTime - d.t) > 1) {
                                v.currentTime = d.t;
                                break;
                            }
                        }
                        if (d.y > 0) window.scrollTo(d.x || 0, d.y);
                    }
                } catch(e) {}
            }

            setInterval(saveState, 1000);
            window.addEventListener('beforeunload', saveState);
            window.addEventListener('pagehide', saveState);
            window.addEventListener('pageshow', function() {
                setTimeout(restoreState, 300);
                setTimeout(restoreState, 1000);
            });
            setTimeout(restoreState, 600);
        })();
        """
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        val host = request.url.host ?: return
        val code = error.errorCode
        val description = error.description?.toString() ?: "Error de red"

        val blockedByDns = dohResolver?.blockedAt(host) != null
        onPageError(code, description, if (blockedByDns) host else "")
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // Las redirecciones del servidor (302) tambien deben respetar el perfil de TV.
        (view as? TVWebView)?.applySiteProfile(url)
        Log.d(TAG, "doUpdateVisitedHistory url=$url ua=${(view as? TVWebView)?.currentUserAgent()}")
        onPageUrlChanged(url)
    }
}
