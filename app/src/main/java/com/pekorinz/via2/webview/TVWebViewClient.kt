package com.pekorinz.via2.webview

import android.graphics.Bitmap
import android.net.Uri
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
 * Diferencias importantes con la version anterior:
 *  - [shouldOverrideUrlLoading] devuelve false siempre que la navegacion sea
 *    normal, asi un clic ya no queda en blanco al decir "No" al aviso HTTP.
 *  - El filtro de anuncios NUNCA lee estado compartido del hilo de UI.
 *  - Los dominios que el DNS elegido responde como inexistentes se cuentan como
 *    bloqueados y se avisa a la UI para mostrarlo.
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

        if (url == RESTORE_TABS_URL &&
            view.url?.substringBefore('#') == HOME_PAGE_URL
        ) {
            onAppAction(ACTION_RESTORE_TABS)
            return true
        }

        // Esquemas que el WebView no puede abrir: se derivan al sistema.
        if (!url.startsWith("http://") && !url.startsWith("https://") &&
            !url.startsWith("file://") && !url.startsWith("about:") &&
            !url.startsWith("data:") && !url.startsWith("javascript:")
        ) {
            return true
        }

        if (url.startsWith("http://")) {
            // Aviso de conexion no cifrada. Si el usuario cancela, se queda donde
            // esta: se devuelve true SOLO mientras se decide, y si en verdad hay
            // que cargar la URL, la carga el callback (no se cancela la actual).
            onHttpWarning(url) {
                view.loadUrl(url)
            }
            return true
        }
        // Navegacion normal: no se intercepta, el WebView sigue solo.
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
        // Solo se mira la cache en memoria: aqui no se puede esperar una consulta.
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
        onPageFinishedCallback?.invoke(view, url)
    }

    /** Se llama al terminar de cargar: aqui se reaplica el modo nocturno. */
    var onPageFinishedCallback: ((WebView, String) -> Unit)? = null

    companion object {
        private const val HOME_PAGE_URL = "file:///android_asset/home_page.html"
        private const val RESTORE_TABS_URL = "navia://restore-tabs"
        const val ACTION_RESTORE_TABS = "restore-tabs"
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
        onPageUrlChanged(url)
    }
}
